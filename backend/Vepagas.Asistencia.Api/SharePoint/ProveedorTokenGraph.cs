using System.Text.Json;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Opciones;

namespace Vepagas.Asistencia.Api.SharePoint;

/// <summary>Token de aplicación (client_credentials) para Microsoft Graph, cacheado hasta 5 min antes de expirar.</summary>
public sealed class ProveedorTokenGraph(IHttpClientFactory httpFactory, IOptions<GraphOpciones> opciones, ILogger<ProveedorTokenGraph> log)
{
    private readonly SemaphoreSlim _candado = new(1, 1);
    private string? _token;
    private DateTime _expiraUtc;

    public async Task<string> ObtenerAsync(CancellationToken ct)
    {
        if (_token is not null && DateTime.UtcNow < _expiraUtc) return _token;

        await _candado.WaitAsync(ct);
        try
        {
            if (_token is not null && DateTime.UtcNow < _expiraUtc) return _token;

            var o = opciones.Value;
            if (string.IsNullOrWhiteSpace(o.TenantId) || string.IsNullOrWhiteSpace(o.ClientId) || string.IsNullOrWhiteSpace(o.ClientSecret))
                throw new InvalidOperationException("Faltan Graph:TenantId / ClientId / ClientSecret (configurar con user-secrets o variables de entorno).");

            using var http = httpFactory.CreateClient("login");
            using var resp = await http.PostAsync(
                $"https://login.microsoftonline.com/{Uri.EscapeDataString(o.TenantId)}/oauth2/v2.0/token",
                new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    ["grant_type"] = "client_credentials",
                    ["client_id"] = o.ClientId,
                    ["client_secret"] = o.ClientSecret,
                    ["scope"] = "https://graph.microsoft.com/.default",
                }), ct);

            var cuerpo = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode)
            {
                // No se loguea el cuerpo completo para evitar filtrar datos; solo el código de error AAD.
                var codigo = TryLeer(cuerpo, "error");
                log.LogError("Fallo al obtener token Graph: HTTP {Estado} {Codigo}", (int)resp.StatusCode, codigo);
                throw new ErrorGraph($"No se pudo obtener token de Graph ({codigo})", (int)resp.StatusCode, ambiguo: false);
            }

            using var doc = JsonDocument.Parse(cuerpo);
            _token = doc.RootElement.GetProperty("access_token").GetString()!;
            var segundos = doc.RootElement.GetProperty("expires_in").GetInt32();
            _expiraUtc = DateTime.UtcNow.AddSeconds(Math.Max(60, segundos - 300));
            return _token;
        }
        finally
        {
            _candado.Release();
        }
    }

    private static string? TryLeer(string json, string prop)
    {
        try { using var d = JsonDocument.Parse(json); return d.RootElement.TryGetProperty(prop, out var v) ? v.GetString() : null; }
        catch { return null; }
    }
}
