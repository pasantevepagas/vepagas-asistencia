namespace Vepagas.Asistencia.Api.SharePoint;

/// <summary>Fila de registros_tb (jornada).</summary>
public sealed record RegistroJornada(
    string ItemId,
    string IdUsuario,
    string IdReg,
    string TipoTrabajo,
    DateTime InicioUtc,
    DateTime? SalidaUtc);

/// <summary>Fila de dispositivos_db.</summary>
public sealed record DispositivoRegistrado(string ItemId, string IdDispositivo);

/// <summary>Fila nueva para ubicacion_db.</summary>
public sealed record NuevaUbicacion(string IdReg, string HoraLocal, double Lat, double Lon);

/// <summary>Acceso a las listas SharePoint de asistencia (registros_tb, ubicacion_db).</summary>
public interface IRepositorioAsistencia
{
    /// <summary>Jornadas del usuario cuyo inicio cae en [desdeUtc, hastaUtc).</summary>
    Task<IReadOnlyList<RegistroJornada>> BuscarJornadasAsync(string idUsuario, DateTime desdeUtc, DateTime hastaUtc, CancellationToken ct);

    /// <summary>POST registros_tb. Devuelve el itemId creado.</summary>
    Task<string> CrearJornadaAsync(string idUsuario, string idReg, string tipoTrabajo, DateTime inicioUtc, CancellationToken ct);

    /// <summary>PATCH FECHA_HORA_SALIDA (idempotente).</summary>
    Task RegistrarSalidaAsync(string itemId, DateTime salidaUtc, CancellationToken ct);

    /// <summary>POST ubicacion_db. Devuelve el itemId creado.</summary>
    Task<string> CrearUbicacionAsync(NuevaUbicacion ubicacion, CancellationToken ct);

    // ---- Control de dispositivos / sesiones (OnStart de Power Apps) ----

    /// <summary>Primera fila de dispositivos_db del usuario (como LookUp de Power Apps), o null.</summary>
    Task<DispositivoRegistrado?> BuscarDispositivoAsync(string idUsuario, CancellationToken ct);

    /// <summary>POST dispositivos_db con SESSION = "AUTH".</summary>
    Task CrearDispositivoAsync(string idUsuario, string idDispositivo, string nombre, CancellationToken ct);

    /// <summary>PATCH ID_DISPOSITIVO (migración única desde Power Apps).</summary>
    Task ActualizarDispositivoAsync(string itemId, string idDispositivo, CancellationToken ct);

    /// <summary>POST sesion_db (GOOD / BAD).</summary>
    Task RegistrarSesionAsync(string idUsuario, DateTime ahoraUtc, string resultado, CancellationToken ct);

    /// <summary>POST UBICACION_APP ("lat lon" + nombre).</summary>
    Task RegistrarUbicacionAppAsync(string ubicacionTexto, string nombre, CancellationToken ct);

    /// <summary>Cantidad de filas en ubicacion_db con ID_FECHA = idReg y HORA = horaLocal (para no duplicar al reintentar).</summary>
    Task<int> ContarUbicacionesAsync(string idReg, string horaLocal, CancellationToken ct);
}

/// <summary>Error HTTP de Graph. <see cref="Ambiguo"/> = no se sabe si la escritura se aplicó (timeout, 5xx).</summary>
public sealed class ErrorGraph(string mensaje, int? estadoHttp, bool ambiguo, Exception? interno = null)
    : Exception(mensaje, interno)
{
    public int? EstadoHttp { get; } = estadoHttp;
    public bool Ambiguo { get; } = ambiguo;
}
