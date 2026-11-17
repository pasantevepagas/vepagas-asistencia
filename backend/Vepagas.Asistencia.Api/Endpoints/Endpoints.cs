using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Contratos;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.Servicios;

namespace Vepagas.Asistencia.Api.Endpoints;

public sealed record GoogleLoginRequest(string? IdToken);
public sealed record AccesoRequest(string? TipoTrabajo, double? Lat, double? Lon);

public static class Endpoints
{
    public const string ClaveItemEmpleado = "empleado";

    public static void Mapear(WebApplication app)
    {
        app.MapGet("/api/salud", () => Results.Ok(new { estado = "ok", horaServidorUtc = DateTime.UtcNow }));

        MapearAuth(app.MapGroup("/api/auth").RequireRateLimiting("auth"), app.Environment);
        MapearAsistencia(app.MapGroup("/api/asistencia").RequireAuthorization().RequireRateLimiting("marcacion"));
        MapearAdmin(app.MapGroup("/api/admin").AddEndpointFilter(FiltroAdmin));
    }

    private static Empleado EmpleadoActual(HttpContext http) =>
        http.Items[ClaveItemEmpleado] as Empleado ?? throw new ErrorNegocio(401, "SESION_INVALIDA", "Sesión inválida.");

    // ---------------- Auth ----------------

    private static void MapearAuth(RouteGroupBuilder g, IHostEnvironment env)
    {
        g.MapPost("/google", async (GoogleLoginRequest req, ServicioSesion sesiones, ServicioAuditoria auditoria, HttpContext http, CancellationToken ct) =>
        {
            if (string.IsNullOrWhiteSpace(req.IdToken))
                throw new ErrorNegocio(400, "TOKEN_REQUERIDO", "Falta el token de Google.");

            var (sesion, empleado, motivo) = await sesiones.LoginGoogleAsync(req.IdToken, ct);
            if (sesion is null)
            {
                await auditoria.RegistrarAsync("LOGIN", "BAD", empleado?.Id, empleado?.Correo ?? CorreoDeMotivo(motivo), motivo, http, ct);
                throw new ErrorNegocio(403, "NO_AUTORIZADO",
                    "Su cuenta no está autorizada para registrar asistencia. Contacte a Talento Humano / TI.");
            }
            await auditoria.RegistrarAsync("LOGIN", "GOOD", empleado!.Id, empleado.Correo, null, http, ct);
            return Results.Ok(sesion);
        });

        // Login sin Google SOLO en Development + bandera explícita: para probar el flujo antes de tener el OAuth client.
        g.MapPost("/dev", async (LoginDesarrolloRequest req, AppDbContext db, ServicioSesion sesiones, IOptions<AuthOpciones> o, ServicioAuditoria auditoria, HttpContext http, CancellationToken ct) =>
        {
            if (!env.IsDevelopment() || !o.Value.PermitirLoginDesarrollo) return Results.NotFound();
            var correo = req.Correo?.Trim().ToLowerInvariant() ?? "";
            var empleado = await db.Empleados.FirstOrDefaultAsync(e => e.Correo == correo && e.Activo, ct)
                ?? throw new ErrorNegocio(403, "NO_AUTORIZADO", "Correo no registrado como empleado.");
            await auditoria.RegistrarAsync("LOGIN_DEV", "GOOD", empleado.Id, empleado.Correo, null, http, ct);
            return Results.Ok(await sesiones.CrearSesionAsync(empleado, ct));
        });

        g.MapPost("/logout", async (ServicioSesion sesiones, HttpContext http, CancellationToken ct) =>
        {
            await sesiones.RevocarAsync(http.User, ct);
            return Results.NoContent();
        }).RequireAuthorization();
    }

    private static string? CorreoDeMotivo(string motivo) =>
        motivo.StartsWith("NO_AUTORIZADO:") ? motivo["NO_AUTORIZADO:".Length..] : null;

    // ---------------- Asistencia ----------------

    private static void MapearAsistencia(RouteGroupBuilder g)
    {
        // Equivale al OnStart de Power Apps: se llama cada vez que se abre la app.
        g.MapGet("/perfil", async ([FromHeader(Name = CabeceraDispositivo)] string? disp, HttpContext http,
            ServicioDispositivo dispositivos, IOptions<AsistenciaOpciones> o, CancellationToken ct) =>
        {
            var e = EmpleadoActual(http);
            var resultado = await dispositivos.VerificarAlIniciarAsync(e, SesionActual(http), disp, http, ct);
            var tipos = o.Value.TiposTrabajo.Select(v => new
            {
                valor = v,
                etiqueta = o.Value.EtiquetasTiposTrabajo.GetValueOrDefault(v, v),
            });
            return Results.Ok(new { nombre = e.Nombre, correo = e.Correo, tiposTrabajo = tipos, dispositivo = resultado });
        });

        // Botón "Tele trabajo": fila en UBICACION_APP, como Power Apps.
        g.MapPost("/acceso", async (AccesoRequest req, [FromHeader(Name = CabeceraDispositivo)] string? disp, HttpContext http,
            ServicioDispositivo dispositivos, CancellationToken ct) =>
        {
            await dispositivos.ExigirAsync(SesionActual(http), disp, ct);
            await dispositivos.RegistrarUbicacionAppAsync(EmpleadoActual(http), req.Lat, req.Lon, ct);
            return Results.NoContent();
        });

        g.MapGet("/estado", async (string? tipoTrabajo, [FromHeader(Name = CabeceraDispositivo)] string? disp, HttpContext http,
            ServicioDispositivo dispositivos, ServicioAsistencia servicio, CancellationToken ct) =>
        {
            await dispositivos.ExigirAsync(SesionActual(http), disp, ct);
            return Results.Ok(await servicio.EstadoAsync(EmpleadoActual(http), tipoTrabajo, ct));
        });

        g.MapPost("/ingreso", async (MarcacionRequest req, [FromHeader(Name = "Idempotency-Key")] string? clave, [FromHeader(Name = CabeceraDispositivo)] string? disp,
            HttpContext http, ServicioDispositivo dispositivos, ServicioAsistencia servicio, ServicioAuditoria auditoria, CancellationToken ct) =>
        {
            await dispositivos.ExigirAsync(SesionActual(http), disp, ct);
            return await Marcar(AccionAsistencia.Ingreso, req, clave, http, servicio, auditoria, ct);
        });

        g.MapPost("/salida", async (MarcacionRequest req, [FromHeader(Name = "Idempotency-Key")] string? clave, [FromHeader(Name = CabeceraDispositivo)] string? disp,
            HttpContext http, ServicioDispositivo dispositivos, ServicioAsistencia servicio, ServicioAuditoria auditoria, CancellationToken ct) =>
        {
            await dispositivos.ExigirAsync(SesionActual(http), disp, ct);
            return await Marcar(AccionAsistencia.Salida, req, clave, http, servicio, auditoria, ct);
        });
    }

    public const string CabeceraDispositivo = "X-Dispositivo";

    private static Guid SesionActual(HttpContext http) =>
        Guid.TryParse(http.User.FindFirst(ServicioSesion.ClaimSesion)?.Value, out var g)
            ? g : throw new ErrorNegocio(401, "SESION_INVALIDA", "Sesión inválida.");

    private static async Task<IResult> Marcar(AccionAsistencia accion, MarcacionRequest req, string? clave, HttpContext http,
        ServicioAsistencia servicio, ServicioAuditoria auditoria, CancellationToken ct)
    {
        var e = EmpleadoActual(http);
        var nombreAccion = accion == AccionAsistencia.Ingreso ? "INGRESO" : "SALIDA";
        try
        {
            var r = await servicio.MarcarAsync(e, accion, req, clave, ct);
            await auditoria.RegistrarAsync(nombreAccion, r.UbicacionPendiente ? "GOOD_UBICACION_PENDIENTE" : "GOOD", e.Id, e.Correo,
                $"tipo={r.Estado.TipoTrabajo} key={clave}", http, CancellationToken.None);
            return Results.Ok(r);
        }
        catch (ErrorNegocio ex)
        {
            await auditoria.RegistrarAsync(nombreAccion, "BAD", e.Id, e.Correo, $"{ex.Codigo} key={clave}", http, CancellationToken.None);
            throw;
        }
    }

    // ---------------- Administración (X-Admin-Key) ----------------

    private static void MapearAdmin(RouteGroupBuilder g)
    {
        g.MapGet("/empleados", async (AppDbContext db, CancellationToken ct) =>
            Results.Ok(await db.Empleados.AsNoTracking().OrderBy(e => e.Nombre)
                .Select(e => new { e.Id, e.Nombre, e.Correo, e.IdUsuarioSharePoint, e.Activo, vinculadoGoogle = e.GoogleSub != null })
                .ToListAsync(ct)));

        // Alta o actualización por correo.
        g.MapPost("/empleados", async (NuevoEmpleadoRequest req, AppDbContext db, CancellationToken ct) =>
        {
            var correo = req.Correo?.Trim().ToLowerInvariant() ?? "";
            if (!correo.Contains('@') || string.IsNullOrWhiteSpace(req.Nombre) || string.IsNullOrWhiteSpace(req.IdUsuarioSharePoint))
                throw new ErrorNegocio(400, "DATOS_INVALIDOS", "Correo, Nombre e IdUsuarioSharePoint son obligatorios.");

            var e = await db.Empleados.FirstOrDefaultAsync(x => x.Correo == correo, ct);
            if (e is null) db.Empleados.Add(e = new Empleado { Correo = correo });
            e.Nombre = req.Nombre.Trim();
            e.IdUsuarioSharePoint = req.IdUsuarioSharePoint.Trim();
            e.Activo = req.Activo;
            await db.SaveChangesAsync(ct);
            return Results.Ok(new { e.Id, e.Nombre, e.Correo, e.IdUsuarioSharePoint, e.Activo });
        });

        // Si el empleado cambia de cuenta Google: borra el vínculo "sub" y revoca sesiones.
        g.MapPost("/empleados/{id:int}/desvincular-google", async (int id, AppDbContext db, CancellationToken ct) =>
        {
            var e = await db.Empleados.FindAsync([id], ct) ?? throw new ErrorNegocio(404, "NO_EXISTE", "Empleado no existe.");
            e.GoogleSub = null;
            await db.Sesiones.Where(s => s.EmpleadoId == id).ExecuteUpdateAsync(s => s.SetProperty(x => x.Revocada, true), ct);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        });

        g.MapGet("/operaciones", async (string? estado, AppDbContext db, CancellationToken ct) =>
        {
            var q = db.Operaciones.AsNoTracking();
            if (Enum.TryParse<EstadoOperacion>(estado, true, out var est)) q = q.Where(o => o.Estado == est);
            return Results.Ok(await q.OrderByDescending(o => o.Id).Take(200)
                .Select(o => new
                {
                    o.Id, o.EmpleadoId, accion = o.Accion.ToString(), estado = o.Estado.ToString(), o.TipoTrabajo, o.FechaLocal, o.HoraUtc,
                    o.IdReg, o.ItemIdRegistro, o.ItemIdUbicacion, o.IntentosUbicacion, o.UltimoError,
                })
                .ToListAsync(ct));
        });

        g.MapGet("/auditoria", async (int? top, AppDbContext db, CancellationToken ct) =>
            Results.Ok(await db.Auditoria.AsNoTracking().OrderByDescending(a => a.Id).Take(Math.Clamp(top ?? 100, 1, 1000)).ToListAsync(ct)));
    }

    private static async ValueTask<object?> FiltroAdmin(EndpointFilterInvocationContext ctx, EndpointFilterDelegate next)
    {
        var o = ctx.HttpContext.RequestServices.GetRequiredService<IOptions<AuthOpciones>>().Value;
        var enviada = ctx.HttpContext.Request.Headers["X-Admin-Key"].ToString();
        if (string.IsNullOrEmpty(o.ClaveAdmin) || o.ClaveAdmin.Length < 16 ||
            !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(enviada), Encoding.UTF8.GetBytes(o.ClaveAdmin)))
            return Results.Unauthorized();
        return await next(ctx);
    }
}
