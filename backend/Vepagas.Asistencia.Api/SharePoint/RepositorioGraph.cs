using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Opciones;

namespace Vepagas.Asistencia.Api.SharePoint;

/// <summary>Implementación real sobre Microsoft Graph (listas SharePoint del sitio "Desarrollo de Aplicaciones Power App").</summary>
public sealed class RepositorioGraph(
    IHttpClientFactory httpFactory,
    ProveedorTokenGraph tokens,
    IOptions<SharePointOpciones> opciones,
    FormatoFechaSharePoint fechas,
    Servicios.RelojEcuador reloj,
    ILogger<RepositorioGraph> log) : IRepositorioAsistencia
{
    private const string Base = "https://graph.microsoft.com/v1.0";
    private SharePointOpciones O => opciones.Value;

    /// <summary>Acepta ids con o sin llaves ({guid} como los copia SharePoint).</summary>
    private static string Id(string id) => id.Trim().Trim('{', '}');

    private string UrlItems(string listId) =>
        $"{Base}/sites/{Uri.EscapeDataString(O.SiteId)}/lists/{Uri.EscapeDataString(Id(listId))}/items";

    public async Task<IReadOnlyList<RegistroJornada>> BuscarJornadasAsync(string idUsuario, DateTime desdeUtc, DateTime hastaUtc, CancellationToken ct)
    {
        var c = O.CamposRegistro;
        // FECHA_HORA_INICIO es columna de TEXTO: no se puede filtrar por rango en el servidor.
        // Se filtra por usuario y el día se evalúa en memoria (equivale a DateValue() de Power Apps).
        var filtro = $"fields/{c.IdUsuario} eq '{EscaparOData(idUsuario)}'";
        var select = string.Join(',', c.IdUsuario, c.FechaHoraInicio, c.FechaHoraSalida, c.IdReg, c.TipoTrabajo);
        var url = $"{UrlItems(O.ListaRegistrosId)}?$expand=fields($select={select})&$filter={Uri.EscapeDataString(filtro)}&$top=100";

        var resultado = new List<RegistroJornada>();
        foreach (var item in await ListarPaginadoAsync(url, ct))
        {
            var f = item!["fields"]!.AsObject();
            var inicio = LeerFecha(f, c.FechaHoraInicio);
            if (inicio is null) continue;
            resultado.Add(new RegistroJornada(
                ItemId: item["id"]!.GetValue<string>(),
                IdUsuario: LeerTexto(f, c.IdUsuario) ?? "",
                IdReg: LeerTexto(f, c.IdReg) ?? "",
                TipoTrabajo: LeerTexto(f, c.TipoTrabajo) ?? "",
                InicioUtc: inicio.Value,
                SalidaUtc: LeerFecha(f, c.FechaHoraSalida)));
        }
        // Re-verificación en memoria por si el filtro del servidor fuese más laxo de lo esperado.
        return resultado.Where(r => r.IdUsuario == idUsuario && r.InicioUtc >= desdeUtc && r.InicioUtc < hastaUtc).ToList();
    }

    public async Task<string> CrearJornadaAsync(string idUsuario, string idReg, string tipoTrabajo, DateTime inicioUtc, CancellationToken ct)
    {
        var c = O.CamposRegistro;
        var campos = new JsonObject
        {
            [c.IdUsuario] = idUsuario,
            [c.FechaHoraInicio] = fechas.Formatear(inicioUtc),
            [c.IdReg] = idReg,
            [c.TipoTrabajo] = tipoTrabajo,
        };
        return await CrearItemAsync(O.ListaRegistrosId, campos, ct);
    }

    public async Task RegistrarSalidaAsync(string itemId, DateTime salidaUtc, CancellationToken ct)
    {
        var cuerpo = new JsonObject { [O.CamposRegistro.FechaHoraSalida] = fechas.Formatear(salidaUtc) };
        var url = $"{UrlItems(O.ListaRegistrosId)}/{Uri.EscapeDataString(itemId)}/fields";
        // PATCH con el mismo valor es idempotente: se permiten reintentos automáticos.
        using var _ = await EnviarAsync(HttpMethod.Patch, url, cuerpo, reintentarSeguro: true, ct);
    }

    public async Task<string> CrearUbicacionAsync(NuevaUbicacion u, CancellationToken ct)
    {
        var c = O.CamposUbicacion;
        // El enlace de Maps siempre con punto decimal; las columnas LAT/LONG según el formato que usa Power Apps.
        var latMapa = u.Lat.ToString("0.00000000", CultureInfo.InvariantCulture);
        var lonMapa = u.Lon.ToString("0.00000000", CultureInfo.InvariantCulture);
        var culturaCoord = O.CoordenadasConComaDecimal ? CultureInfo.GetCultureInfo("es-EC") : CultureInfo.InvariantCulture;
        var campos = new JsonObject
        {
            [c.IdFecha] = u.IdReg,
            [c.Hora] = u.HoraLocal,
            // Mismo formato que Power Apps: "-0,12345678" (8 decimales, coma).
            [c.Lat] = O.CoordenadasComoNumero ? JsonValue.Create(Math.Round(u.Lat, 8)) : JsonValue.Create(u.Lat.ToString("0.00000000", culturaCoord)),
            [c.Long] = O.CoordenadasComoNumero ? JsonValue.Create(Math.Round(u.Lon, 8)) : JsonValue.Create(u.Lon.ToString("0.00000000", culturaCoord)),
            [c.Ubicacion] = $"https://www.google.com/maps/place/{latMapa},{lonMapa}",
        };
        return await CrearItemAsync(O.ListaUbicacionesId, campos, ct);
    }

    // ---------- dispositivos_db / sesion_db / UBICACION_APP ----------

    public async Task<DispositivoRegistrado?> BuscarDispositivoAsync(string idUsuario, CancellationToken ct)
    {
        var c = O.CamposDispositivo;
        var filtro = $"fields/{c.IdUsuario} eq '{EscaparOData(idUsuario)}'";
        var url = $"{UrlItems(O.ListaDispositivosId)}?$expand=fields($select={c.IdUsuario},{c.IdDispositivo})&$filter={Uri.EscapeDataString(filtro)}&$top=50";
        var item = (await ListarPaginadoAsync(url, ct))
            .Where(i => LeerTexto(i!["fields"]!.AsObject(), c.IdUsuario) == idUsuario)
            .OrderBy(i => int.TryParse(i!["id"]!.GetValue<string>(), out var n) ? n : int.MaxValue) // LookUp = primera fila
            .FirstOrDefault();
        return item is null ? null : new DispositivoRegistrado(
            item["id"]!.GetValue<string>(),
            LeerTexto(item["fields"]!.AsObject(), c.IdDispositivo) ?? "");
    }

    public async Task CrearDispositivoAsync(string idUsuario, string idDispositivo, string nombre, CancellationToken ct)
    {
        var c = O.CamposDispositivo;
        await CrearItemAsync(O.ListaDispositivosId, new JsonObject
        {
            [c.IdUsuario] = idUsuario,
            [c.IdDispositivo] = idDispositivo,
            [c.Nombre] = nombre,
            [c.Sesion] = "AUTH",
        }, ct);
    }

    public async Task ActualizarDispositivoAsync(string itemId, string idDispositivo, CancellationToken ct)
    {
        var url = $"{UrlItems(O.ListaDispositivosId)}/{Uri.EscapeDataString(itemId)}/fields";
        using var _ = await EnviarAsync(HttpMethod.Patch, url, new JsonObject { [O.CamposDispositivo.IdDispositivo] = idDispositivo }, true, ct);
    }

    public async Task RegistrarSesionAsync(string idUsuario, DateTime ahoraUtc, string resultado, CancellationToken ct)
    {
        var c = O.CamposSesion;
        // FECHA es columna de solo fecha: Power Apps la guarda como el día local (00:00 Ecuador = 05:00Z).
        var diaLocalUtc = reloj.LocalAUtc(reloj.ALocal(ahoraUtc).Date);
        await CrearItemAsync(O.ListaSesionesId, new JsonObject
        {
            [c.IdUsuario] = idUsuario,
            [c.Fecha] = diaLocalUtc.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture),
            [c.Sesion] = resultado,
        }, ct);
    }

    public async Task RegistrarUbicacionAppAsync(string ubicacionTexto, string nombre, CancellationToken ct)
    {
        var c = O.CamposUbicacionApp;
        await CrearItemAsync(O.ListaUbicacionAppId, new JsonObject { [c.Ubicacion] = ubicacionTexto, [c.Nombre] = nombre }, ct);
    }

    /// <summary>-1 = no se pudo verificar (p. ej. ubicacion_db supera 5000 ítems y ID_FECHA no está indexada).</summary>
    public async Task<int> ContarUbicacionesAsync(string idReg, string horaLocal, CancellationToken ct)
    {
        var c = O.CamposUbicacion;
        var filtro = $"fields/{c.IdFecha} eq '{EscaparOData(idReg)}'";
        var url = $"{UrlItems(O.ListaUbicacionesId)}?$expand=fields($select={c.IdFecha},{c.Hora})&$filter={Uri.EscapeDataString(filtro)}&$top=100";
        try
        {
            return (await ListarPaginadoAsync(url, ct))
                .Count(i => LeerTexto(i!["fields"]!.AsObject(), c.Hora) == horaLocal);
        }
        catch (ErrorGraph ex) when (ex.EstadoHttp is 400 or 422)
        {
            log.LogWarning("No se puede filtrar ubicacion_db por {Campo} (¿falta índice?): {Error}", c.IdFecha, ex.Message);
            return -1;
        }
    }

    /// <summary>Diagnóstico: prueba si se puede filtrar una lista por un campo (umbral de 5000 / índices).</summary>
    public async Task<string> ProbarFiltroAsync(string listId, string campo, CancellationToken ct)
    {
        var url = $"{UrlItems(listId)}?$expand=fields($select={campo})&$filter={Uri.EscapeDataString($"fields/{campo} eq '__prueba__'")}&$top=1";
        try { using var _ = await EnviarAsync(HttpMethod.Get, url, null, true, ct); return "OK"; }
        catch (ErrorGraph ex) { return $"FALLA ({ex.Message})"; }
    }

    // ---------- Diagnóstico (solo lectura) ----------

    public async Task<JsonArray> ListarListasDelSitioAsync(CancellationToken ct) =>
        await ListarPaginadoAsync($"{Base}/sites/{Uri.EscapeDataString(O.SiteId)}/lists?$select=id,name,displayName,webUrl", ct);

    public async Task<JsonArray> ListarColumnasAsync(string listId, CancellationToken ct) =>
        await ListarPaginadoAsync($"{Base}/sites/{Uri.EscapeDataString(O.SiteId)}/lists/{Uri.EscapeDataString(Id(listId))}/columns", ct);

    /// <summary>Últimos ítems; si la lista supera el umbral y no permite ordenar, devuelve los primeros (más antiguos).</summary>
    public async Task<(JsonArray items, bool ordenados)> ListarUltimosItemsAsync(string listId, int cantidad, CancellationToken ct)
    {
        try
        {
            using var resp = await EnviarAsync(HttpMethod.Get, $"{UrlItems(listId)}?$expand=fields&$top={cantidad}&$orderby=id desc", null, true, ct);
            return (JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct))!["value"]!.AsArray(), true);
        }
        catch (ErrorGraph ex) when (ex.EstadoHttp is 400 or 422)
        {
            using var resp = await EnviarAsync(HttpMethod.Get, $"{UrlItems(listId)}?$expand=fields&$top={cantidad}", null, true, ct);
            return (JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct))!["value"]!.AsArray(), false);
        }
    }

    // ---------- Infraestructura HTTP ----------

    private async Task<string> CrearItemAsync(string listId, JsonObject campos, CancellationToken ct)
    {
        // POST NO se reintenta automáticamente: un reintento ciego podría duplicar filas.
        // La reanudación la hace ServicioAsistencia usando la bitácora + Idempotency-Key.
        using var resp = await EnviarAsync(HttpMethod.Post, UrlItems(listId), new JsonObject { ["fields"] = campos }, reintentarSeguro: false, ct);
        var json = JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct))!;
        return json["id"]!.GetValue<string>();
    }

    private async Task<JsonArray> ListarPaginadoAsync(string url, CancellationToken ct)
    {
        var todos = new JsonArray();
        string? siguiente = url;
        var paginas = 0;
        while (siguiente is not null && paginas++ < 50)
        {
            using var resp = await EnviarAsync(HttpMethod.Get, siguiente, null, reintentarSeguro: true, ct);
            var json = JsonNode.Parse(await resp.Content.ReadAsStringAsync(ct))!;
            foreach (var item in json["value"]!.AsArray().ToList())
                todos.Add(item!.DeepClone());
            siguiente = json["@odata.nextLink"]?.GetValue<string>();
        }
        return todos;
    }

    private async Task<HttpResponseMessage> EnviarAsync(HttpMethod metodo, string url, JsonNode? cuerpo, bool reintentarSeguro, CancellationToken ct)
    {
        var maxIntentos = reintentarSeguro ? 4 : 1;
        for (var intento = 1; ; intento++)
        {
            using var req = new HttpRequestMessage(metodo, url);
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await tokens.ObtenerAsync(ct));
            // Necesario para $filter sobre columnas no indexadas. Recomendado: indexar ID_USUARIO, FECHA_HORA_INICIO e ID_FECHA.
            req.Headers.TryAddWithoutValidation("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly");
            if (cuerpo is not null)
                req.Content = new StringContent(cuerpo.ToJsonString(), Encoding.UTF8, "application/json");

            HttpResponseMessage resp;
            try
            {
                resp = await httpFactory.CreateClient("graph").SendAsync(req, ct);
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
            {
                if (intento < maxIntentos) { await Task.Delay(Espera(intento, null), ct); continue; }
                throw new ErrorGraph($"Sin respuesta de Graph ({metodo} {Recortar(url)})", null, ambiguo: metodo != HttpMethod.Get, ex);
            }

            if (resp.IsSuccessStatusCode) return resp;

            var estado = (int)resp.StatusCode;
            var transitorio = resp.StatusCode is HttpStatusCode.TooManyRequests or HttpStatusCode.ServiceUnavailable or HttpStatusCode.GatewayTimeout or HttpStatusCode.BadGateway;
            if (transitorio && intento < maxIntentos)
            {
                var espera = Espera(intento, resp.Headers.RetryAfter?.Delta);
                resp.Dispose();
                await Task.Delay(espera, ct);
                continue;
            }

            var detalle = await resp.Content.ReadAsStringAsync(ct);
            resp.Dispose();
            var codigo = ExtraerCodigoError(detalle);
            log.LogWarning("Graph {Metodo} {Url} -> HTTP {Estado} {Codigo}", metodo, Recortar(url), estado, codigo);
            // 429 = rechazado antes de procesar; 5xx en escritura = resultado desconocido.
            var ambiguo = metodo != HttpMethod.Get && estado >= 500;
            throw new ErrorGraph($"Graph respondió {estado} ({codigo})", estado, ambiguo);
        }
    }

    private static TimeSpan Espera(int intento, TimeSpan? retryAfter) =>
        retryAfter is { } ra && ra < TimeSpan.FromSeconds(30) ? ra : TimeSpan.FromMilliseconds(400 * Math.Pow(2, intento - 1));

    private static string ExtraerCodigoError(string json)
    {
        try
        {
            var n = JsonNode.Parse(json);
            return $"{n?["error"]?["code"]?.GetValue<string>()}: {n?["error"]?["message"]?.GetValue<string>()}";
        }
        catch { return "sin detalle"; }
    }

    private static string Recortar(string url) => url.Split('?')[0];
    private static string EscaparOData(string v) => v.Replace("'", "''");

    private static string? LeerTexto(JsonObject f, string campo) =>
        f.TryGetPropertyValue(campo, out var v) && v is not null
            ? (v.GetValueKind() == JsonValueKind.String ? v.GetValue<string>() : v.ToJsonString())
            : null;

    private DateTime? LeerFecha(JsonObject f, string campo) => fechas.Parsear(LeerTexto(f, campo));
}
