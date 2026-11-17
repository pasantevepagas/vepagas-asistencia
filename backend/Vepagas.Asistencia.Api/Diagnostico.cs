using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.SharePoint;

namespace Vepagas.Asistencia.Api;

/// <summary>
/// Fase A — validación de integración SIN escribir en SharePoint:
///   dotnet run -- diagnostico
/// Muestra listas, columnas (tipo, índice), si se puede filtrar, y MUESTRAS DE FORMATO de los datos
/// que escribe Power Apps. Coordenadas e ID_USUARIO se enmascaran (dígitos -> 9, letras -> x).
/// </summary>
public static partial class Diagnostico
{
    public static async Task<int> EjecutarAsync(IServiceProvider sp)
    {
        var o = sp.GetRequiredService<IOptions<SharePointOpciones>>().Value;
        var repo = sp.GetRequiredService<RepositorioGraph>();
        var fechas = sp.GetRequiredService<FormatoFechaSharePoint>();
        var ct = CancellationToken.None;

        if (string.IsNullOrWhiteSpace(o.SiteId))
        {
            Console.Error.WriteLine("Falta SharePoint:SiteId.");
            return 1;
        }

        try
        {
            var cr = o.CamposRegistro;
            var cu = o.CamposUbicacion;

            foreach (var (nombre, id, camposFiltro) in new[]
                     {
                         ("registros_tb", o.ListaRegistrosId, new[] { cr.IdUsuario }),
                         ("ubicacion_db", o.ListaUbicacionesId, new[] { cu.IdFecha }),
                     })
            {
                Console.WriteLine();
                if (string.IsNullOrWhiteSpace(id))
                {
                    Console.WriteLine($"== {nombre}: falta configurar su id ==");
                    continue;
                }

                Console.WriteLine($"== Columnas de {nombre} ==");
                foreach (var c in await repo.ListarColumnasAsync(id, ct))
                {
                    var col = c!.AsObject();
                    var interno = col["name"]?.GetValue<string>();
                    if (col["readOnly"]?.GetValue<bool>() == true || interno is "ContentType" or "Attachments") continue;
                    var tipo = col.Select(p => p.Key).FirstOrDefault(k => k is "text" or "number" or "dateTime" or "choice" or "lookup" or "personOrGroup" or "boolean" or "hyperlinkOrPicture" or "currency" or "calculated") ?? "?";
                    var indexada = col["indexed"]?.GetValue<bool>() == true ? "SI" : "no";
                    Console.WriteLine($"  {interno,-20} tipo={tipo,-8} indexada={indexada}");
                }

                foreach (var campo in camposFiltro)
                    Console.WriteLine($"  Filtro por {campo}: {await repo.ProbarFiltroAsync(id, campo, ct)}");
            }

            // ---------- Muestras de formato ----------
            if (!string.IsNullOrWhiteSpace(o.ListaRegistrosId))
            {
                var (items, ordenados) = await repo.ListarUltimosItemsAsync(o.ListaRegistrosId, 500, ct);
                Console.WriteLine();
                Console.WriteLine($"== Muestras registros_tb ({items.Count} {(ordenados ? "más recientes" : "más antiguos")}) ==");
                foreach (var it in items.Take(8))
                {
                    var f = it!["fields"]!;
                    var inicio = Txt(f, cr.FechaHoraInicio);
                    var interpretada = fechas.Parsear(inicio);
                    Console.WriteLine(
                        $"  INICIO=\"{inicio}\"  SALIDA=\"{Txt(f, cr.FechaHoraSalida)}\"  TIPO=\"{Txt(f, cr.TipoTrabajo)}\"  " +
                        $"ID_USUARIO~\"{Mascara(Txt(f, cr.IdUsuario))}\"  ID_REG~\"{Mascara(Txt(f, cr.IdReg))}\"  " +
                        $"(leída como UTC: {interpretada?.ToString("yyyy-MM-dd HH:mm") ?? "NO RECONOCIDA"})");
                }

                Console.WriteLine();
                Console.WriteLine($"== Valores de {cr.TipoTrabajo} (copiar a Asistencia:TiposTrabajo) ==");
                foreach (var g in items.GroupBy(i => Txt(i!["fields"]!, cr.TipoTrabajo)).OrderByDescending(g => g.Count()))
                    Console.WriteLine($"  \"{g.Key}\"  ({g.Count()} registros)");

                var noReconocidas = items.Count(i => Txt(i!["fields"]!, cr.FechaHoraInicio) is { Length: > 0 } t && fechas.Parsear(t) is null);
                Console.WriteLine($"  Fechas de inicio no reconocidas: {noReconocidas} de {items.Count}");
                foreach (var t in items.Select(i => Txt(i!["fields"]!, cr.FechaHoraInicio))
                             .Where(t => t.Length > 0 && fechas.Parsear(t) is null).Distinct().Take(10))
                    Console.WriteLine($"    no reconocida: \"{t}\"");
            }

            if (!string.IsNullOrWhiteSpace(o.ListaUbicacionesId))
            {
                var (items, ordenados) = await repo.ListarUltimosItemsAsync(o.ListaUbicacionesId, 8, ct);
                Console.WriteLine();
                Console.WriteLine($"== Muestras ubicacion_db ({(ordenados ? "más recientes" : "más antiguas: la lista supera el umbral y no permite ordenar")}) ==");
                foreach (var it in items)
                {
                    var f = it!["fields"]!;
                    Console.WriteLine(
                        $"  HORA=\"{Txt(f, cu.Hora)}\"  LAT~\"{Mascara(Txt(f, cu.Lat))}\"  LONG~\"{Mascara(Txt(f, cu.Long))}\"  " +
                        $"UBICACION~\"{Mascara(Txt(f, cu.Ubicacion))}\"  ID_FECHA~\"{Mascara(Txt(f, cu.IdFecha))}\"");
                }
            }

            Console.WriteLine();
            Console.WriteLine("Lectura OK. Pase esta salida completa al desarrollador.");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"ERROR: {ex.Message}");
            return 2;
        }
    }

    /// <summary>
    ///   dotnet run -- diagnostico-lista {listId}
    /// Columnas y últimas filas de cualquier lista. Se muestran tal cual solo campos de fecha/estado;
    /// el resto se enmascara (dígitos -> 9, letras -> x).
    /// </summary>
    public static async Task<int> InspeccionarListaAsync(IServiceProvider sp, string listId)
    {
        var repo = sp.GetRequiredService<RepositorioGraph>();
        var ct = CancellationToken.None;
        string[] visibles = ["FECHA", "SESSION", "HORA", "Created", "Modified"];
        try
        {
            Console.WriteLine($"== Columnas de {listId} ==");
            var columnas = new List<string>();
            foreach (var c in await repo.ListarColumnasAsync(listId, ct))
            {
                var col = c!.AsObject();
                var interno = col["name"]?.GetValue<string>() ?? "";
                if (col["readOnly"]?.GetValue<bool>() == true || interno is "ContentType" or "Attachments") continue;
                var tipo = col.Select(p => p.Key).FirstOrDefault(k => k is "text" or "number" or "dateTime" or "choice" or "lookup" or "personOrGroup" or "boolean" or "hyperlinkOrPicture" or "currency" or "calculated") ?? "?";
                Console.WriteLine($"  {interno,-20} tipo={tipo,-8} indexada={(col["indexed"]?.GetValue<bool>() == true ? "SI" : "no")}");
                columnas.Add(interno);
            }

            var (items, ordenados) = await repo.ListarUltimosItemsAsync(listId, 6, ct);
            Console.WriteLine($"== Muestras ({(ordenados ? "más recientes" : "más antiguas")}) ==");
            foreach (var it in items)
            {
                var f = it!["fields"]!;
                Console.WriteLine("  " + string.Join("  ", columnas.Append("Created").Select(c =>
                {
                    var v = Txt(f, c);
                    return $"{c}=\"{(visibles.Contains(c) ? v : Mascara(v))}\"";
                })));
            }
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"ERROR: {ex.Message}");
            return 2;
        }
    }

    private static string Txt(JsonNode f, string campo)
    {
        var v = f[campo];
        return v is null ? "" : v.GetValueKind() == System.Text.Json.JsonValueKind.String ? v.GetValue<string>() : v.ToJsonString();
    }

    /// <summary>Muestra solo la FORMA del dato: dígitos -> 9, letras -> x (conserva separadores).</summary>
    private static string Mascara(string s)
    {
        const string marca = "/maps/place/";
        var i = s.IndexOf(marca, StringComparison.OrdinalIgnoreCase);
        if (i >= 0) return s[..(i + marca.Length)] + Digitos().Replace(s[(i + marca.Length)..], "9");
        return Letras().Replace(Digitos().Replace(s, "9"), "x");
    }

    [GeneratedRegex("[0-9]")] private static partial Regex Digitos();
    [GeneratedRegex("[A-Za-z]")] private static partial Regex Letras();
}
