using System.Collections.Concurrent;

namespace Vepagas.Asistencia.Api.SharePoint;

/// <summary>
/// Implementación en memoria para demo/desarrollo sin credenciales Graph (SharePoint:Modo = "Simulado").
/// Los datos se pierden al reiniciar. NUNCA usar en producción.
/// </summary>
public sealed class RepositorioSimulado(ILogger<RepositorioSimulado> log) : IRepositorioAsistencia
{
    private readonly ConcurrentDictionary<string, RegistroJornada> _jornadas = new();
    private readonly ConcurrentBag<NuevaUbicacion> _ubicaciones = [];
    private int _secuencia;

    public Task<IReadOnlyList<RegistroJornada>> BuscarJornadasAsync(string idUsuario, DateTime desdeUtc, DateTime hastaUtc, CancellationToken ct) =>
        Task.FromResult<IReadOnlyList<RegistroJornada>>(_jornadas.Values
            .Where(j => j.IdUsuario == idUsuario && j.InicioUtc >= desdeUtc && j.InicioUtc < hastaUtc)
            .ToList());

    public Task<string> CrearJornadaAsync(string idUsuario, string idReg, string tipoTrabajo, DateTime inicioUtc, CancellationToken ct)
    {
        var id = Interlocked.Increment(ref _secuencia).ToString();
        _jornadas[id] = new RegistroJornada(id, idUsuario, idReg, tipoTrabajo, inicioUtc, null);
        log.LogInformation("[SIMULADO] registros_tb +{Id} usuario={Usuario} tipo={Tipo}", id, idUsuario, tipoTrabajo);
        return Task.FromResult(id);
    }

    public Task RegistrarSalidaAsync(string itemId, DateTime salidaUtc, CancellationToken ct)
    {
        if (_jornadas.TryGetValue(itemId, out var j))
            _jornadas[itemId] = j with { SalidaUtc = salidaUtc };
        log.LogInformation("[SIMULADO] registros_tb PATCH {Id} salida", itemId);
        return Task.CompletedTask;
    }

    public Task<string> CrearUbicacionAsync(NuevaUbicacion ubicacion, CancellationToken ct)
    {
        _ubicaciones.Add(ubicacion);
        var id = Interlocked.Increment(ref _secuencia).ToString();
        log.LogInformation("[SIMULADO] ubicacion_db +{Id} idReg={IdReg} {Lat},{Lon}", id, ubicacion.IdReg, ubicacion.Lat, ubicacion.Lon);
        return Task.FromResult(id);
    }

    private readonly ConcurrentDictionary<string, DispositivoRegistrado> _dispositivos = new();

    public Task<DispositivoRegistrado?> BuscarDispositivoAsync(string idUsuario, CancellationToken ct) =>
        Task.FromResult(_dispositivos.GetValueOrDefault(idUsuario));

    public Task CrearDispositivoAsync(string idUsuario, string idDispositivo, string nombre, CancellationToken ct)
    {
        _dispositivos.TryAdd(idUsuario, new DispositivoRegistrado(Interlocked.Increment(ref _secuencia).ToString(), idDispositivo));
        log.LogInformation("[SIMULADO] dispositivos_db +{Usuario} AUTH", idUsuario);
        return Task.CompletedTask;
    }

    public Task ActualizarDispositivoAsync(string itemId, string idDispositivo, CancellationToken ct)
    {
        foreach (var (k, v) in _dispositivos.Where(d => d.Value.ItemId == itemId).ToList())
            _dispositivos[k] = v with { IdDispositivo = idDispositivo };
        log.LogInformation("[SIMULADO] dispositivos_db PATCH {Item}", itemId);
        return Task.CompletedTask;
    }

    public Task RegistrarSesionAsync(string idUsuario, DateTime ahoraUtc, string resultado, CancellationToken ct)
    {
        log.LogInformation("[SIMULADO] sesion_db {Usuario} {Resultado}", idUsuario, resultado);
        return Task.CompletedTask;
    }

    public Task RegistrarUbicacionAppAsync(string ubicacionTexto, string nombre, CancellationToken ct)
    {
        log.LogInformation("[SIMULADO] UBICACION_APP \"{Ubicacion}\" {Nombre}", ubicacionTexto, nombre);
        return Task.CompletedTask;
    }

    public Task<int> ContarUbicacionesAsync(string idReg, string horaLocal, CancellationToken ct) =>
        Task.FromResult(_ubicaciones.Count(u => u.IdReg == idReg && u.HoraLocal == horaLocal));
}
