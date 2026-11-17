using System.Globalization;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.SharePoint;

namespace Vepagas.Asistencia.Api.Servicios;

/// <summary>
/// Replica el OnStart de Power Apps sobre dispositivos_db / sesion_db:
///  - Sin fila en dispositivos_db  -> se registra este dispositivo (AUTH) y sesion_db = GOOD.
///  - Fila con el mismo dispositivo -> sesion_db = GOOD ("Dispositivo autorizado").
///  - Fila con otro dispositivo     -> sesion_db = BAD y se bloquea (Power Apps hacía Exit()).
/// Además, migración ÚNICA: si la fila viene de Power Apps y el empleado aún no migró, se actualiza
/// ID_DISPOSITIVO con el de la APK (Asistencia:MigrarDispositivosPowerApps).
/// El identificador lo genera la APK al instalarse y lo guarda cifrado con el Android Keystore.
/// </summary>
public sealed class ServicioDispositivo(
    AppDbContext db,
    IRepositorioAsistencia sp,
    RelojEcuador reloj,
    ServicioAuditoria auditoria,
    IOptions<AsistenciaOpciones> opciones,
    ILogger<ServicioDispositivo> log)
{
    private AsistenciaOpciones O => opciones.Value;

    /// <summary>Al abrir la app (GET perfil). Devuelve AUTORIZADO / REGISTRADO / MIGRADO o lanza 403.</summary>
    public async Task<string> VerificarAlIniciarAsync(Empleado e, Guid sesionId, string? idDispositivo, HttpContext http, CancellationToken ct)
    {
        var sesion = await db.Sesiones.FindAsync([sesionId], ct)
            ?? throw new ErrorNegocio(401, "SESION_INVALIDA", "Sesión inválida.");

        if (!O.ControlDispositivos)
        {
            sesion.DispositivoId = idDispositivo ?? "-";
            await db.SaveChangesAsync(ct);
            return "SIN_CONTROL";
        }

        var disp = Normalizar(idDispositivo);

        DispositivoRegistrado? registrado;
        try { registrado = await sp.BuscarDispositivoAsync(e.IdUsuarioSharePoint, ct); }
        catch (ErrorGraph ex)
        {
            log.LogError(ex, "No se pudo consultar dispositivos_db");
            throw new ErrorNegocio(503, "SHAREPOINT_NO_DISPONIBLE", "No se pudo verificar el dispositivo. Intente nuevamente.");
        }

        string resultado;
        try
        {
            if (registrado is null)
            {
                await sp.CrearDispositivoAsync(e.IdUsuarioSharePoint, disp, e.Nombre, ct);
                e.DispositivoMigrado = true;
                resultado = "REGISTRADO";
            }
            else if (string.Equals(registrado.IdDispositivo.Trim(), disp, StringComparison.OrdinalIgnoreCase))
            {
                resultado = "AUTORIZADO";
            }
            else if (O.MigrarDispositivosPowerApps && !e.DispositivoMigrado)
            {
                await sp.ActualizarDispositivoAsync(registrado.ItemId, disp, ct);
                e.DispositivoMigrado = true;
                resultado = "MIGRADO";
            }
            else
            {
                resultado = "BAD";
            }
        }
        catch (ErrorGraph ex)
        {
            log.LogError(ex, "No se pudo escribir dispositivos_db");
            throw new ErrorNegocio(503, "SHAREPOINT_NO_DISPONIBLE", "No se pudo registrar el dispositivo. Intente nuevamente.");
        }

        var ok = resultado != "BAD";
        try { await sp.RegistrarSesionAsync(e.IdUsuarioSharePoint, reloj.AhoraUtc(), ok ? "GOOD" : "BAD", ct); }
        catch (Exception ex) when (ex is ErrorGraph or HttpRequestException) { log.LogWarning(ex, "No se pudo escribir sesion_db"); }

        sesion.DispositivoId = ok ? disp : null;
        await db.SaveChangesAsync(ct);
        await auditoria.RegistrarAsync("DISPOSITIVO", ok ? "GOOD" : "BAD", e.Id, e.Correo, $"{resultado} disp={disp}", http, ct);

        if (!ok)
            throw new ErrorNegocio(403, "DISPOSITIVO_NO_AUTORIZADO",
                "Este teléfono no está autorizado para su usuario. Si cambió de teléfono, contacte a TI para habilitar el nuevo.");
        return resultado;
    }

    /// <summary>Antes de cada operación: la sesión debe haber sido verificada con ESTE dispositivo.</summary>
    public async Task ExigirAsync(Guid sesionId, string? idDispositivo, CancellationToken ct)
    {
        if (!O.ControlDispositivos) return;
        var sesion = await db.Sesiones.FindAsync([sesionId], ct);
        if (sesion?.DispositivoId is null || !string.Equals(sesion.DispositivoId, idDispositivo?.Trim(), StringComparison.OrdinalIgnoreCase))
            throw new ErrorNegocio(403, "DISPOSITIVO_NO_VERIFICADO", "Dispositivo no verificado. Cierre y vuelva a abrir la aplicación.");
    }

    /// <summary>Botón "Tele trabajo": fila en UBICACION_APP con "lat lon" (coma decimal, como Power Apps).</summary>
    public async Task RegistrarUbicacionAppAsync(Empleado e, double? lat, double? lon, CancellationToken ct)
    {
        if (!O.RegistrarUbicacionApp) return;
        var es = CultureInfo.GetCultureInfo("es-EC");
        var texto = $"{lat?.ToString("0.########", es)} {lon?.ToString("0.########", es)}";
        try { await sp.RegistrarUbicacionAppAsync(texto, e.Nombre, ct); }
        catch (Exception ex) when (ex is ErrorGraph or HttpRequestException) { log.LogWarning(ex, "No se pudo escribir UBICACION_APP"); }
    }

    private static string Normalizar(string? id)
    {
        if (string.IsNullOrWhiteSpace(id) || !Guid.TryParse(id.Trim(), out var g))
            throw new ErrorNegocio(400, "DISPOSITIVO_REQUERIDO", "Falta el identificador del dispositivo. Actualice la aplicación.");
        return g.ToString(); // formato estándar en minúsculas, igual que GUID() de Power Apps
    }
}
