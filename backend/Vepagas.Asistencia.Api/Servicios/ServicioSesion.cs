using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Google.Apis.Auth;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using Vepagas.Asistencia.Api.Contratos;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;

namespace Vepagas.Asistencia.Api.Servicios;

/// <summary>
/// Identidad: el empleado se autentica con Google; el backend verifica el ID token (firma, audience,
/// issuer, expiración, email verificado) y lo mapea a un Empleado autorizado. Luego emite su propio
/// token de sesión corto y revocable. El ID_USUARIO jamás viene del cliente.
/// </summary>
public sealed class ServicioSesion(AppDbContext db, IOptions<AuthOpciones> opciones, ILogger<ServicioSesion> log)
{
    public const string ClaimSesion = "sid";

    private AuthOpciones O => opciones.Value;

    public async Task<(SesionResponse? sesion, Empleado? empleado, string motivo)> LoginGoogleAsync(string idToken, CancellationToken ct)
    {
        if (O.GoogleClientIds.Length == 0)
            throw new ErrorNegocio(503, "GOOGLE_NO_CONFIGURADO", "El inicio de sesión con Google aún no está configurado en el servidor (Auth:GoogleClientIds).");

        GoogleJsonWebSignature.Payload payload;
        try
        {
            payload = await GoogleJsonWebSignature.ValidateAsync(idToken, new GoogleJsonWebSignature.ValidationSettings
            {
                Audience = O.GoogleClientIds,
                IssuedAtClockTolerance = TimeSpan.FromMinutes(2),
                ExpirationTimeClockTolerance = TimeSpan.FromMinutes(1),
            });
        }
        catch (InvalidJwtException ex)
        {
            log.LogWarning("ID token de Google inválido: {Motivo}", ex.Message);
            return (null, null, "TOKEN_GOOGLE_INVALIDO");
        }

        if (!payload.EmailVerified || string.IsNullOrWhiteSpace(payload.Email))
            return (null, null, "CORREO_NO_VERIFICADO");

        var correo = payload.Email.Trim().ToLowerInvariant();
        var empleado = await db.Empleados.FirstOrDefaultAsync(e => e.GoogleSub == payload.Subject, ct)
                       ?? await db.Empleados.FirstOrDefaultAsync(e => e.Correo == correo, ct);

        if (empleado is null) return (null, null, $"NO_AUTORIZADO:{correo}");
        if (empleado.GoogleSub is null)
        {
            // Primer login: se vincula el "sub" de Google (estable aunque cambie el correo).
            empleado.GoogleSub = payload.Subject;
            await db.SaveChangesAsync(ct);
        }
        else if (empleado.GoogleSub != payload.Subject)
        {
            return (null, empleado, "SUB_NO_COINCIDE");
        }
        if (!empleado.Activo) return (null, empleado, "EMPLEADO_INACTIVO");

        return (await CrearSesionAsync(empleado, ct), empleado, "OK");
    }

    public async Task<SesionResponse> CrearSesionAsync(Empleado empleado, CancellationToken ct)
    {
        var sesion = new Sesion { EmpleadoId = empleado.Id, ExpiraUtc = DateTime.UtcNow.AddHours(O.DuracionSesionHoras) };
        db.Sesiones.Add(sesion);
        await db.SaveChangesAsync(ct);

        var token = new JwtSecurityToken(
            issuer: O.Emisor,
            audience: O.Emisor,
            claims:
            [
                new Claim(JwtRegisteredClaimNames.Sub, empleado.Id.ToString()),
                new Claim(ClaimSesion, sesion.Id.ToString()),
            ],
            notBefore: DateTime.UtcNow,
            expires: sesion.ExpiraUtc,
            signingCredentials: new SigningCredentials(ClaveFirma(O), SecurityAlgorithms.HmacSha256));

        return new SesionResponse(new JwtSecurityTokenHandler().WriteToken(token), sesion.ExpiraUtc,
            new EmpleadoDto(empleado.Nombre, empleado.Correo));
    }

    /// <summary>Valida que la sesión del token siga vigente y el empleado activo. Devuelve el empleado o null.</summary>
    public async Task<Empleado?> ResolverAsync(ClaimsPrincipal usuario, CancellationToken ct)
    {
        if (!Guid.TryParse(usuario.FindFirstValue(ClaimSesion), out var sid)) return null;
        var sesion = await db.Sesiones.AsNoTracking().FirstOrDefaultAsync(s => s.Id == sid, ct);
        if (sesion is null || sesion.Revocada || sesion.ExpiraUtc <= DateTime.UtcNow) return null;
        var empleado = await db.Empleados.FirstOrDefaultAsync(e => e.Id == sesion.EmpleadoId, ct);
        return empleado is { Activo: true } ? empleado : null;
    }

    public async Task RevocarAsync(ClaimsPrincipal usuario, CancellationToken ct)
    {
        if (!Guid.TryParse(usuario.FindFirstValue(ClaimSesion), out var sid)) return;
        await db.Sesiones.Where(s => s.Id == sid).ExecuteUpdateAsync(s => s.SetProperty(x => x.Revocada, true), ct);
    }

    public static SymmetricSecurityKey ClaveFirma(AuthOpciones o)
    {
        var bytes = Encoding.UTF8.GetBytes(o.ClaveFirmaJwt ?? "");
        if (bytes.Length < 32)
            throw new InvalidOperationException("Auth:ClaveFirmaJwt debe tener al menos 32 bytes (configurar con user-secrets o variable de entorno).");
        return new SymmetricSecurityKey(bytes);
    }
}
