using System.Collections.Concurrent;
using System.Globalization;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;

namespace Vepagas.Asistencia.Api.Servicios;

/// <summary>Error de negocio con código HTTP; el middleware lo convierte en { codigo, mensaje, idSolicitud }.</summary>
public sealed class ErrorNegocio(int estadoHttp, string codigo, string mensaje) : Exception(mensaje)
{
    public int EstadoHttp { get; } = estadoHttp;
    public string Codigo { get; } = codigo;
}

/// <summary>Hora oficial = hora del servidor (UTC) convertida a Ecuador para la fecha laboral y el campo HORA.</summary>
public sealed class RelojEcuador
{
    private readonly TimeZoneInfo _zona;

    public RelojEcuador(IOptions<AsistenciaOpciones> opciones)
    {
        var id = opciones.Value.ZonaHoraria;
        try { _zona = TimeZoneInfo.FindSystemTimeZoneById(id); }
        catch (TimeZoneNotFoundException)
        {
            // Windows sin ICU: equivalente de America/Guayaquil (UTC-5 sin horario de verano).
            _zona = TimeZoneInfo.FindSystemTimeZoneById("SA Pacific Standard Time");
        }
    }

    public DateTime AhoraUtc() => DateTime.UtcNow;
    public DateTime ALocal(DateTime utc) => TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utc, DateTimeKind.Utc), _zona);
    public DateTime LocalAUtc(DateTime local) =>
        DateTime.SpecifyKind(TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(local, DateTimeKind.Unspecified), _zona), DateTimeKind.Utc);
    public DateOnly FechaLocal(DateTime utc) => DateOnly.FromDateTime(ALocal(utc));
    public string FechaLocalTexto(DateTime utc) => FechaLocal(utc).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    public string HoraLocalTexto(DateTime utc) => ALocal(utc).ToString("HH:mm", CultureInfo.InvariantCulture);

    /// <summary>Rango UTC [desde, hasta) que corresponde al día local dado.</summary>
    public (DateTime desde, DateTime hasta) RangoUtcDelDia(DateOnly fecha)
    {
        var inicioLocal = DateTime.SpecifyKind(fecha.ToDateTime(TimeOnly.MinValue), DateTimeKind.Unspecified);
        var desde = TimeZoneInfo.ConvertTimeToUtc(inicioLocal, _zona);
        var hasta = TimeZoneInfo.ConvertTimeToUtc(inicioLocal.AddDays(1), _zona);
        return (desde, hasta);
    }
}

/// <summary>Serializa operaciones por empleado (una instancia de backend). Si se escala a varias instancias, reemplazar por bloqueo distribuido.</summary>
public sealed class CandadosEmpleado
{
    private readonly ConcurrentDictionary<int, SemaphoreSlim> _candados = new();

    public async Task<IDisposable> TomarAsync(int empleadoId, CancellationToken ct)
    {
        var s = _candados.GetOrAdd(empleadoId, _ => new SemaphoreSlim(1, 1));
        if (!await s.WaitAsync(TimeSpan.FromSeconds(30), ct))
            throw new ErrorNegocio(409, "OPERACION_EN_CURSO", "Ya hay una marcación en proceso. Espere unos segundos.");
        return new Liberador(s);
    }

    private sealed class Liberador(SemaphoreSlim s) : IDisposable
    {
        private int _liberado;
        public void Dispose() { if (Interlocked.Exchange(ref _liberado, 1) == 0) s.Release(); }
    }
}

public sealed class ServicioAuditoria(AppDbContext db, ILogger<ServicioAuditoria> log)
{
    public async Task RegistrarAsync(string accion, string resultado, int? empleadoId, string? correo, string? detalle, HttpContext? http, CancellationToken ct = default)
    {
        try
        {
            db.Auditoria.Add(new RegistroAuditoria
            {
                Accion = accion,
                Resultado = resultado,
                EmpleadoId = empleadoId,
                Correo = correo,
                Detalle = detalle is { Length: > 1000 } ? detalle[..1000] : detalle,
                IdSolicitud = http?.TraceIdentifier,
                Ip = http?.Connection.RemoteIpAddress?.ToString(),
            });
            await db.SaveChangesAsync(ct);
        }
        catch (Exception ex)
        {
            // La auditoría no debe tumbar la operación principal.
            log.LogError(ex, "No se pudo guardar auditoría {Accion} {Resultado}", accion, resultado);
        }
    }
}
