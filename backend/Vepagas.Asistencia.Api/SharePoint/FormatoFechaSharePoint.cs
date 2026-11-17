using System.Globalization;
using Microsoft.Extensions.Options;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.Servicios;

namespace Vepagas.Asistencia.Api.SharePoint;

/// <summary>
/// FECHA_HORA_INICIO / FECHA_HORA_SALIDA son columnas de TEXTO: Power Apps guarda ahí Now() convertido a texto.
/// Para que Power Apps (DateValue) y los reportes lean igual las filas de la APK, se escribe EXACTAMENTE
/// el mismo formato (SharePoint:FormatoFechaTexto) y se leen tanto ese formato como variantes comunes.
/// </summary>
public sealed class FormatoFechaSharePoint(IOptions<SharePointOpciones> opciones, RelojEcuador reloj)
{
    private static readonly CultureInfo[] Culturas =
        [CultureInfo.InvariantCulture, CultureInfo.GetCultureInfo("es-EC"), CultureInfo.GetCultureInfo("es-ES"), CultureInfo.GetCultureInfo("en-US")];

    private static readonly string[] FormatosComunes =
    [
        "d MMMM yyyy H:mm", "d MMMM yyyy H:mm:ss",
        // Teléfonos en inglés: Power Apps escribe la fecha según el idioma del dispositivo.
        "MMMM d, yyyy h:mm tt", "MMMM d, yyyy h:mm:ss tt",
        "d/M/yyyy H:mm", "d/M/yyyy H:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy HH:mm:ss",
        "d/M/yyyy h:mm tt", "d/M/yyyy h:mm:ss tt",
        "M/d/yyyy h:mm tt", "M/d/yyyy h:mm:ss tt", "M/d/yyyy H:mm",
        "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-ddTHH:mm:ss", "yyyy-MM-ddTHH:mm:ss.fff",
    ];

    private SharePointOpciones O => opciones.Value;

    public string Formatear(DateTime utc)
    {
        var formato = O.FormatoFechaTexto;
        if (string.IsNullOrWhiteSpace(formato) || formato.Equals("ISO", StringComparison.OrdinalIgnoreCase))
            return utc.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture);

        var valor = O.FechaTextoEnHoraLocal ? reloj.ALocal(utc) : utc.ToUniversalTime();
        return valor.ToString(formato, CultureInfo.GetCultureInfo(O.CulturaFechaTexto));
    }

    public DateTime? Parsear(string? texto)
    {
        if (string.IsNullOrWhiteSpace(texto)) return null;
        var t = texto.Trim().Replace(' ', ' ').Replace(' ', ' ');

        // ISO con zona (Z u offset): es un instante absoluto.
        if ((t.EndsWith('Z') || t.Contains('+') || (t.Length > 19 && t[19] is '-' or '+')) &&
            DateTime.TryParse(t, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal, out var abs))
            return DateTime.SpecifyKind(abs, DateTimeKind.Utc);

        // Sin zona: se asume hora local de Ecuador (o UTC si así se configuró).
        var formatos = string.IsNullOrWhiteSpace(O.FormatoFechaTexto) || O.FormatoFechaTexto.Equals("ISO", StringComparison.OrdinalIgnoreCase)
            ? FormatosComunes
            : [O.FormatoFechaTexto, .. FormatosComunes];

        foreach (var cultura in Culturas)
        {
            if (DateTime.TryParseExact(t, formatos, cultura, DateTimeStyles.AllowWhiteSpaces, out var d))
                return AUtc(d);
        }
        return null;
    }

    private DateTime AUtc(DateTime sinZona) =>
        O.FechaTextoEnHoraLocal ? reloj.LocalAUtc(sinZona) : DateTime.SpecifyKind(sinZona, DateTimeKind.Utc);
}
