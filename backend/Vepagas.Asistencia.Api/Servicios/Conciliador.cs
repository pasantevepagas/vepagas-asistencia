using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;

namespace Vepagas.Asistencia.Api.Servicios;

/// <summary>Reintenta periódicamente las ubicaciones pendientes (registros_tb OK, ubicacion_db falló).</summary>
public sealed class Conciliador(IServiceScopeFactory scopes, IOptions<AsistenciaOpciones> opciones, ILogger<Conciliador> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        var intervalo = TimeSpan.FromMinutes(Math.Max(1, opciones.Value.MinutosReconciliacion));
        while (!ct.IsCancellationRequested)
        {
            try { await CicloAsync(ct); }
            catch (Exception ex) when (!ct.IsCancellationRequested) { log.LogError(ex, "Error en ciclo de conciliación"); }
            await Task.Delay(intervalo, ct);
        }
    }

    private async Task CicloAsync(CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var servicio = scope.ServiceProvider.GetRequiredService<ServicioAsistencia>();
        var candados = scope.ServiceProvider.GetRequiredService<CandadosEmpleado>();

        // RegistroGuardado viejo = el proceso se interrumpió entre registros_tb y ubicacion_db.
        var limite = DateTime.UtcNow.AddMinutes(-2);
        var pendientes = await db.Operaciones
            .Where(o => o.Estado == EstadoOperacion.UbicacionPendiente ||
                        (o.Estado == EstadoOperacion.RegistroGuardado && o.ActualizadaUtc < limite))
            .OrderBy(o => o.Id)
            .Take(50)
            .ToListAsync(ct);

        foreach (var op in pendientes)
        {
            using var _ = await candados.TomarAsync(op.EmpleadoId, ct);
            await db.Entry(op).ReloadAsync(ct);
            if (op.Estado is not (EstadoOperacion.UbicacionPendiente or EstadoOperacion.RegistroGuardado)) continue;
            await servicio.IntentarUbicacionAsync(op, ct);
            log.LogInformation("Conciliación op={Op} -> {Estado}", op.Id, op.Estado);
        }
    }
}
