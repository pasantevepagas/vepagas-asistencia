using System.Text.Json.Nodes;

namespace Vepagas.Asistencia.Api.Endpoints;

/// <summary>
/// Actualizaciones de la APK servidas por el propio backend (el repo de GitHub es privado).
/// El workflow android-release copia en Actualizaciones:Carpeta:
///   vepagas-asistencia.apk  +  version.json {versionCode, versionName, sha256, obligatoria, notas}
/// </summary>
public static class EndpointsApp
{
    public static void Mapear(WebApplication app)
    {
        var carpeta = app.Configuration["Actualizaciones:Carpeta"] is { Length: > 0 } c
            ? c : Path.Combine(app.Environment.ContentRootPath, "actualizaciones");
        var g = app.MapGroup("/api/app");

        g.MapGet("/version", async () =>
        {
            var ruta = Path.Combine(carpeta, "version.json");
            if (!File.Exists(ruta)) return Results.NotFound();
            return Results.Text(await File.ReadAllTextAsync(ruta), "application/json");
        });

        g.MapGet("/apk", () =>
        {
            var ruta = Path.Combine(carpeta, "vepagas-asistencia.apk");
            return File.Exists(ruta)
                ? Results.File(ruta, "application/vnd.android.package-archive", "vepagas-asistencia.apk", enableRangeProcessing: true)
                : Results.NotFound();
        });
    }
}
