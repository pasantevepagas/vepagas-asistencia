using System.Text;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.SharePoint;

namespace Vepagas.Asistencia.Api;

/// <summary>
/// Carga masiva de empleados autorizados desde CSV:
///   dotnet run -- importar-empleados App_Data/empleados_gmail.csv            (verifica y guarda)
///   dotnet run -- importar-empleados App_Data/empleados_gmail.csv --simular  (solo verifica)
/// Columnas (con encabezado, en cualquier orden): ID_USER (o ID_USUARIO), NOMBRE, CORREO_GMAIL (o CORREO).
/// Filas sin correo se omiten. Si SharePoint está configurado, informa cuántas jornadas tiene cada ID
/// en registros_tb (solo lectura) para confirmar que es el mismo ID_USUARIO que usa Power Apps.
/// </summary>
public static class ImportadorEmpleados
{
    public static async Task<int> EjecutarAsync(IServiceProvider sp, string ruta, bool simular)
    {
        if (!File.Exists(ruta))
        {
            Console.Error.WriteLine($"No existe el archivo {Path.GetFullPath(ruta)}");
            return 1;
        }

        var filas = LeerCsv(ruta);
        if (filas.Count == 0) { Console.Error.WriteLine("CSV vacío."); return 1; }

        string? Col(Dictionary<string, string> f, params string[] nombres) =>
            nombres.Select(n => f.GetValueOrDefault(n)).FirstOrDefault(v => !string.IsNullOrWhiteSpace(v))?.Trim();

        using var scope = sp.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
        var spOpc = scope.ServiceProvider.GetRequiredService<IOptions<SharePointOpciones>>().Value;
        var graph = string.IsNullOrWhiteSpace(spOpc.SiteId) || string.IsNullOrWhiteSpace(spOpc.ListaRegistrosId)
            ? null : scope.ServiceProvider.GetRequiredService<RepositorioGraph>();

        int guardados = 0, omitidos = 0, errores = 0;
        var correosVistos = new HashSet<string>();

        foreach (var f in filas)
        {
            var id = Col(f, "ID_USER", "ID_USUARIO");
            var nombre = Col(f, "NOMBRE");
            var correo = Col(f, "CORREO_GMAIL", "CORREO")?.ToLowerInvariant();
            var etiqueta = $"{nombre,-38}";

            if (string.IsNullOrEmpty(id) || string.IsNullOrEmpty(nombre)) { Console.WriteLine($"  ERROR  fila incompleta (falta ID_USER o NOMBRE)"); errores++; continue; }
            if (string.IsNullOrEmpty(correo)) { Console.WriteLine($"  --     {etiqueta} sin correo: omitido"); omitidos++; continue; }
            if (!correo.Contains('@') || correo.Contains(' ')) { Console.WriteLine($"  ERROR  {etiqueta} correo inválido \"{correo}\""); errores++; continue; }
            if (!correosVistos.Add(correo)) { Console.WriteLine($"  ERROR  {etiqueta} correo repetido en el CSV \"{correo}\""); errores++; continue; }

            var verificacion = "";
            if (graph is not null)
            {
                try
                {
                    var jornadas = await graph.BuscarJornadasAsync(id, DateTime.MinValue, DateTime.MaxValue, CancellationToken.None);
                    verificacion = jornadas.Count == 0
                        ? "  (sin jornadas previas en registros_tb)"
                        : $"  ({jornadas.Count} jornadas en registros_tb, última {jornadas.Max(j => j.InicioUtc):yyyy-MM-dd})";
                }
                catch (Exception ex) { verificacion = $"  (no se pudo verificar: {ex.Message})"; }
            }

            var existente = await db.Empleados.FirstOrDefaultAsync(e => e.Correo == correo);
            var esNuevo = existente is null;
            var otroConMismoId = await db.Empleados.FirstOrDefaultAsync(e => e.IdUsuarioSharePoint == id && e.Correo != correo);
            if (otroConMismoId is not null)
                Console.WriteLine($"  AVISO  {etiqueta} el ID ya estaba asignado a {otroConMismoId.Correo}");

            if (!simular)
            {
                if (existente is null) db.Empleados.Add(existente = new Empleado { Correo = correo });
                existente.Nombre = nombre;
                existente.IdUsuarioSharePoint = id;
                existente.Activo = true;
            }
            Console.WriteLine($"  {(esNuevo ? "NUEVO" : "ACTUAL"),-6} {etiqueta} {correo}{verificacion}");
            guardados++;
        }

        if (!simular) await db.SaveChangesAsync();
        Console.WriteLine();
        Console.WriteLine($"{(simular ? "SIMULACIÓN (nada guardado)" : "Guardado")}: {guardados} empleados · {omitidos} sin correo · {errores} con error");
        return errores > 0 ? 3 : 0;
    }

    /// <summary>CSV simple con comillas dobles y separador coma o punto y coma (Excel en español).</summary>
    private static List<Dictionary<string, string>> LeerCsv(string ruta)
    {
        var lineas = File.ReadAllLines(ruta, Encoding.UTF8).Where(l => !string.IsNullOrWhiteSpace(l)).ToList();
        if (lineas.Count == 0) return [];
        var sep = lineas[0].Count(c => c == ';') > lineas[0].Count(c => c == ',') ? ';' : ',';
        var encabezado = Partir(lineas[0].TrimStart('﻿'), sep).Select(h => h.Trim().ToUpperInvariant()).ToArray();

        return lineas.Skip(1).Select(l =>
        {
            var valores = Partir(l, sep);
            var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (var i = 0; i < encabezado.Length && i < valores.Count; i++) d[encabezado[i]] = valores[i];
            return d;
        }).ToList();
    }

    private static List<string> Partir(string linea, char sep)
    {
        var res = new List<string>();
        var sb = new StringBuilder();
        var comillas = false;
        for (var i = 0; i < linea.Length; i++)
        {
            var c = linea[i];
            if (c == '"')
            {
                if (comillas && i + 1 < linea.Length && linea[i + 1] == '"') { sb.Append('"'); i++; }
                else comillas = !comillas;
            }
            else if (c == sep && !comillas) { res.Add(sb.ToString()); sb.Clear(); }
            else sb.Append(c);
        }
        res.Add(sb.ToString());
        return res;
    }
}
