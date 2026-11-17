using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Contratos;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.SharePoint;

namespace Vepagas.Asistencia.Api.Servicios;

/// <summary>
/// Reglas de ingreso/salida equivalentes a la app Power Apps:
///  - Ingreso único por ID_USUARIO + fecha local Ecuador + TIPO_TRABAJO.
///  - Salida solo con ingreso existente y sin salida previa (PATCH sobre la misma fila).
///  - Cada marcación agrega una fila en ubicacion_db con ID_FECHA = ID_REG.
/// Como Graph no ofrece transacciones entre listas, cada marcación se registra en una bitácora local
/// (OperacionAsistencia) que permite reanudar con la misma Idempotency-Key y conciliar la ubicación.
/// </summary>
public sealed partial class ServicioAsistencia(
    AppDbContext db,
    IRepositorioAsistencia sp,
    RelojEcuador reloj,
    CandadosEmpleado candados,
    IOptions<AsistenciaOpciones> opciones,
    ILogger<ServicioAsistencia> log)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public IReadOnlyList<string> TiposTrabajo => opciones.Value.TiposTrabajo;

    public async Task<EstadoJornadaDto> EstadoAsync(Empleado empleado, string? tipoTrabajo, CancellationToken ct)
    {
        var tipo = ValidarTipo(tipoTrabajo);
        var fecha = reloj.FechaLocal(reloj.AhoraUtc());
        var jornada = await BuscarJornadaAsync(empleado, fecha, tipo, ct);
        var fechaTxt = fecha.ToString("yyyy-MM-dd");

        var pendiente = await db.Operaciones.AnyAsync(o =>
            o.EmpleadoId == empleado.Id && o.FechaLocal == fechaTxt && o.TipoTrabajo == tipo &&
            (o.Estado == EstadoOperacion.UbicacionPendiente || o.Estado == EstadoOperacion.RequiereRevision ||
             o.Estado == EstadoOperacion.RegistroGuardado), ct);

        return new EstadoJornadaDto(
            Fecha: fechaTxt,
            TipoTrabajo: tipo,
            Estado: jornada is null ? "SIN_INGRESO" : jornada.SalidaUtc is null ? "EN_JORNADA" : "JORNADA_CERRADA",
            HoraIngreso: jornada is null ? null : reloj.HoraLocalTexto(jornada.InicioUtc),
            HoraSalida: jornada?.SalidaUtc is { } s ? reloj.HoraLocalTexto(s) : null,
            UbicacionPendiente: pendiente);
    }

    public async Task<MarcacionResponse> MarcarAsync(Empleado empleado, AccionAsistencia accion, MarcacionRequest req, string? clave, CancellationToken ct)
    {
        clave = ValidarClave(clave);
        var tipo = ValidarTipo(req.TipoTrabajo);
        var (lat, lon, precision) = ValidarUbicacion(req);

        using var _ = await candados.TomarAsync(empleado.Id, ct);

        var op = await db.Operaciones.SingleOrDefaultAsync(o => o.EmpleadoId == empleado.Id && o.ClaveIdempotencia == clave, ct);
        var reanudando = op is not null;
        if (op is not null)
        {
            if (op.Accion != accion || op.TipoTrabajo != tipo)
                throw new ErrorNegocio(422, "CLAVE_REUTILIZADA", "La Idempotency-Key ya se usó para otra operación.");
            if (op.RespuestaJson is not null)
                return JsonSerializer.Deserialize<MarcacionResponse>(op.RespuestaJson, Json)!;
        }
        else
        {
            (op, reanudando) = accion == AccionAsistencia.Ingreso
                ? await PrepararIngresoAsync(empleado, tipo, clave, lat, lon, precision, ct)
                : await PrepararSalidaAsync(empleado, tipo, clave, lat, lon, precision, ct);
        }

        // Paso 1: registros_tb
        if (op.Estado == EstadoOperacion.Iniciada)
        {
            try
            {
                if (accion == AccionAsistencia.Ingreso)
                    await EjecutarIngresoAsync(empleado, op, reanudando, ct);
                else
                    await sp.RegistrarSalidaAsync(op.ItemIdRegistro!, op.HoraUtc, ct);

                op.Estado = EstadoOperacion.RegistroGuardado;
                op.UltimoError = null;
                await GuardarAsync(op, ct);
            }
            catch (ErrorGraph ex)
            {
                op.UltimoError = ex.Message;
                await GuardarAsync(op, ct);
                log.LogWarning(ex, "Paso registros_tb falló op={Op} ambiguo={Ambiguo}", op.Id, ex.Ambiguo);
                throw new ErrorNegocio(503, "REINTENTAR",
                    "No se pudo confirmar la marcación en SharePoint. Intente nuevamente: la marcación no se duplicará.");
            }
        }

        // Paso 2: ubicacion_db (un fallo aquí NO revierte la marcación: queda pendiente de conciliación)
        if (op.Estado is EstadoOperacion.RegistroGuardado or EstadoOperacion.UbicacionPendiente)
            await IntentarUbicacionAsync(op, ct);

        var pendiente = op.Estado != EstadoOperacion.Completada;
        var mensaje = (accion, pendiente) switch
        {
            (AccionAsistencia.Ingreso, false) => "Se registró el ingreso correctamente",
            (AccionAsistencia.Salida, false) => "Se registró la salida correctamente",
            (AccionAsistencia.Ingreso, true) => "Ingreso registrado. La ubicación quedó pendiente de sincronizar; no repita la marcación.",
            _ => "Salida registrada. La ubicación quedó pendiente de sincronizar; no repita la marcación.",
        };
        var respuesta = new MarcacionResponse(mensaje, pendiente, new EstadoJornadaDto(
            Fecha: op.FechaLocal,
            TipoTrabajo: op.TipoTrabajo,
            Estado: accion == AccionAsistencia.Ingreso ? "EN_JORNADA" : "JORNADA_CERRADA",
            HoraIngreso: reloj.HoraLocalTexto(op.InicioJornadaUtc),
            HoraSalida: accion == AccionAsistencia.Salida ? reloj.HoraLocalTexto(op.HoraUtc) : null,
            UbicacionPendiente: pendiente));

        op.RespuestaJson = JsonSerializer.Serialize(respuesta, Json);
        await GuardarAsync(op, ct);
        return respuesta;
    }

    /// <summary>Intenta escribir la ubicación de una operación. Usado en línea y por el conciliador.</summary>
    public async Task IntentarUbicacionAsync(OperacionAsistencia op, CancellationToken ct)
    {
        var hora = reloj.HoraLocalTexto(op.HoraUtc);
        try
        {
            // Si ya hubo intentos (posible escritura con respuesta perdida), verificar antes de crear otra fila.
            if (op.IntentosUbicacion > 0 && await sp.ContarUbicacionesAsync(op.IdReg, hora, ct) > 0)
            {
                op.ItemIdUbicacion ??= "verificado";
            }
            else
            {
                op.ItemIdUbicacion = await sp.CrearUbicacionAsync(new NuevaUbicacion(op.IdReg, hora, op.Lat, op.Lon), ct);
            }
            op.Estado = EstadoOperacion.Completada;
            op.UltimoError = null;
        }
        catch (Exception ex) when (ex is ErrorGraph or HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            op.IntentosUbicacion++;
            op.UltimoError = ex.Message;
            op.Estado = op.IntentosUbicacion >= opciones.Value.IntentosMaximosUbicacion
                ? EstadoOperacion.RequiereRevision
                : EstadoOperacion.UbicacionPendiente;
            log.LogWarning(ex, "Ubicación pendiente op={Op} intento={Intento}", op.Id, op.IntentosUbicacion);
        }
        await GuardarAsync(op, ct);
    }

    // ---------- Preparación (validaciones de negocio antes de escribir) ----------

    private async Task<(OperacionAsistencia op, bool reanudando)> PrepararIngresoAsync(
        Empleado empleado, string tipo, string clave, double lat, double lon, double precision, CancellationToken ct)
    {
        var ahora = reloj.AhoraUtc();
        var fecha = reloj.FechaLocal(ahora);
        var fechaTxt = fecha.ToString("yyyy-MM-dd");

        // Bitácora local primero (cubre la latencia de indexación de SharePoint).
        var previa = await db.Operaciones.FirstOrDefaultAsync(o =>
            o.EmpleadoId == empleado.Id && o.FechaLocal == fechaTxt && o.TipoTrabajo == tipo && o.Accion == AccionAsistencia.Ingreso, ct);
        if (previa is { Estado: EstadoOperacion.Iniciada })
            return (previa, true); // intento anterior sin confirmar: se reanuda en vez de crear otro
        if (previa is not null)
            throw new ErrorNegocio(409, "INGRESO_YA_REGISTRADO", "Su ingreso ya fue registrado");

        if (await BuscarJornadaAsync(empleado, fecha, tipo, ct) is not null)
            throw new ErrorNegocio(409, "INGRESO_YA_REGISTRADO", "Su ingreso ya fue registrado");

        var op = NuevaOperacion(empleado, AccionAsistencia.Ingreso, clave, tipo, ahora, lat, lon, precision);
        op.IdReg = Guid.NewGuid().ToString();
        op.InicioJornadaUtc = ahora;
        db.Operaciones.Add(op);
        await db.SaveChangesAsync(ct);
        return (op, false);
    }

    private async Task<(OperacionAsistencia op, bool reanudando)> PrepararSalidaAsync(
        Empleado empleado, string tipo, string clave, double lat, double lon, double precision, CancellationToken ct)
    {
        var ahora = reloj.AhoraUtc();
        var fecha = reloj.FechaLocal(ahora);
        var fechaTxt = fecha.ToString("yyyy-MM-dd");

        var previa = await db.Operaciones.FirstOrDefaultAsync(o =>
            o.EmpleadoId == empleado.Id && o.FechaLocal == fechaTxt && o.TipoTrabajo == tipo && o.Accion == AccionAsistencia.Salida, ct);
        if (previa is { Estado: EstadoOperacion.Iniciada })
            return (previa, true);
        if (previa is not null)
            throw new ErrorNegocio(409, "SALIDA_YA_REGISTRADA", "Su salida ya fue registrada");

        var jornada = await BuscarJornadaAsync(empleado, fecha, tipo, ct)
            ?? throw new ErrorNegocio(409, "SIN_INGRESO", "No se ha registrado su entrada");
        if (jornada.SalidaUtc is not null)
            throw new ErrorNegocio(409, "SALIDA_YA_REGISTRADA", "Su salida ya fue registrada");

        var op = NuevaOperacion(empleado, AccionAsistencia.Salida, clave, tipo, ahora, lat, lon, precision);
        op.IdReg = jornada.IdReg;
        op.ItemIdRegistro = jornada.ItemId;
        op.InicioJornadaUtc = jornada.InicioUtc;
        db.Operaciones.Add(op);
        await db.SaveChangesAsync(ct);
        return (op, false);
    }

    private async Task EjecutarIngresoAsync(Empleado empleado, OperacionAsistencia op, bool reanudando, CancellationToken ct)
    {
        if (reanudando)
        {
            // Un intento anterior pudo haber creado la fila aunque la respuesta se perdió.
            var jornada = await BuscarJornadaAsync(empleado, DateOnly.Parse(op.FechaLocal), op.TipoTrabajo, ct);
            if (jornada is not null && jornada.IdReg == op.IdReg)
            {
                op.ItemIdRegistro = jornada.ItemId;
                return;
            }
            if (jornada is not null)
            {
                // Otra vía (p. ej. Power Apps) registró el ingreso mientras tanto.
                db.Operaciones.Remove(op);
                await db.SaveChangesAsync(ct);
                throw new ErrorNegocio(409, "INGRESO_YA_REGISTRADO", "Su ingreso ya fue registrado");
            }
        }
        op.ItemIdRegistro = await sp.CrearJornadaAsync(empleado.IdUsuarioSharePoint, op.IdReg, op.TipoTrabajo, op.HoraUtc, ct);
    }

    private async Task<RegistroJornada?> BuscarJornadaAsync(Empleado empleado, DateOnly fecha, string tipo, CancellationToken ct)
    {
        var (desde, hasta) = reloj.RangoUtcDelDia(fecha);
        try
        {
            var jornadas = await sp.BuscarJornadasAsync(empleado.IdUsuarioSharePoint, desde, hasta, ct);
            return jornadas
                .Where(j => string.Equals(j.TipoTrabajo.Trim(), tipo, StringComparison.OrdinalIgnoreCase))
                .OrderBy(j => j.InicioUtc)
                .FirstOrDefault();
        }
        catch (ErrorGraph ex)
        {
            log.LogError(ex, "No se pudo consultar registros_tb");
            throw new ErrorNegocio(503, "SHAREPOINT_NO_DISPONIBLE", "No se pudo consultar SharePoint. Intente nuevamente en unos minutos.");
        }
    }

    private OperacionAsistencia NuevaOperacion(Empleado e, AccionAsistencia accion, string clave, string tipo, DateTime ahora, double lat, double lon, double precision) => new()
    {
        EmpleadoId = e.Id,
        ClaveIdempotencia = clave,
        Accion = accion,
        Estado = EstadoOperacion.Iniciada,
        TipoTrabajo = tipo,
        FechaLocal = reloj.FechaLocalTexto(ahora),
        HoraUtc = ahora,
        Lat = lat,
        Lon = lon,
        PrecisionMetros = precision,
    };

    private async Task GuardarAsync(OperacionAsistencia op, CancellationToken ct)
    {
        op.ActualizadaUtc = DateTime.UtcNow;
        // CancellationToken.None: si el cliente se desconecta, igual se persiste el estado ya aplicado en SharePoint.
        await db.SaveChangesAsync(CancellationToken.None);
    }

    // ---------- Validaciones de entrada ----------

    private string ValidarTipo(string? tipo)
    {
        var tipos = opciones.Value.TiposTrabajo;
        var t = tipo?.Trim();
        if (string.IsNullOrEmpty(t))
        {
            if (tipos.Length == 1) return tipos[0];
            throw new ErrorNegocio(400, "TIPO_REQUERIDO", "Seleccione el tipo de trabajo.");
        }
        return tipos.FirstOrDefault(x => string.Equals(x, t, StringComparison.OrdinalIgnoreCase))
            ?? throw new ErrorNegocio(400, "TIPO_INVALIDO", "Tipo de trabajo no válido.");
    }

    private (double lat, double lon, double precision) ValidarUbicacion(MarcacionRequest req)
    {
        if (req.Lat is not { } lat || req.Lon is not { } lon || double.IsNaN(lat) || double.IsNaN(lon))
            throw new ErrorNegocio(400, "UBICACION_REQUERIDA", "No se recibió la ubicación. Active el GPS e intente nuevamente.");
        if (lat is < -90 or > 90 || lon is < -180 or > 180 || (lat == 0 && lon == 0))
            throw new ErrorNegocio(400, "UBICACION_INVALIDA", "Ubicación inválida.");
        var precision = req.AccuracyMeters ?? double.MaxValue;
        if (precision <= 0 || precision > opciones.Value.PrecisionMaximaMetros)
            throw new ErrorNegocio(400, "UBICACION_IMPRECISA",
                $"La ubicación no es suficientemente precisa (máx. {opciones.Value.PrecisionMaximaMetros:0} m). Salga a un lugar abierto e intente nuevamente.");
        return (lat, lon, precision);
    }

    private static string ValidarClave(string? clave)
    {
        if (string.IsNullOrWhiteSpace(clave) || !ClaveValida().IsMatch(clave))
            throw new ErrorNegocio(400, "IDEMPOTENCY_KEY_REQUERIDA", "Falta la cabecera Idempotency-Key (UUID).");
        return clave.ToLowerInvariant();
    }

    [GeneratedRegex("^[A-Za-z0-9-]{16,64}$")]
    private static partial Regex ClaveValida();
}
