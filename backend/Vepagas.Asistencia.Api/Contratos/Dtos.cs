namespace Vepagas.Asistencia.Api.Contratos;

public sealed record LoginDesarrolloRequest(string Correo);

public sealed record SesionResponse(string Token, DateTime ExpiraUtc, EmpleadoDto Empleado);

public sealed record EmpleadoDto(string Nombre, string Correo);

public sealed record MarcacionRequest(string? TipoTrabajo, double? Lat, double? Lon, double? AccuracyMeters);

/// <summary>Estado: SIN_INGRESO | EN_JORNADA | JORNADA_CERRADA. Horas en hora local Ecuador (HH:mm).</summary>
public sealed record EstadoJornadaDto(
    string Fecha,
    string TipoTrabajo,
    string Estado,
    string? HoraIngreso,
    string? HoraSalida,
    bool UbicacionPendiente);

public sealed record MarcacionResponse(
    string Mensaje,
    bool UbicacionPendiente,
    EstadoJornadaDto Estado);

public sealed record ErrorResponse(string Codigo, string Mensaje, string IdSolicitud);

public sealed record NuevoEmpleadoRequest(string Correo, string Nombre, string IdUsuarioSharePoint, bool Activo = true);
