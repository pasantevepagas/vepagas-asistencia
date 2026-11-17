package com.vepagas.asistencia.datos

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Formatos EXACTOS que usa Power Apps en las listas (todas las columnas son de texto):
 *  - FECHA_HORA_INICIO / SALIDA: "17 noviembre 2026 9:34" (hora de Ecuador). Teléfonos en inglés dejaron
 *    "November 17, 2026 9:34 AM" y hay filas viejas "17/11/2026 9:34": se leen todos.
 *  - HORA: "09:34"; LAT/LONG: "-0,12345678"; enlace Maps con punto; sesion_db.FECHA = día local (05:00Z).
 */
object FormatoFecha {
    val ZONA: ZoneId = ZoneId.of("America/Guayaquil")
    private val ES: Locale = Locale.forLanguageTag("es-EC")

    private val escritura = DateTimeFormatter.ofPattern("d MMMM yyyy H:mm", ES)
    private val horaFmt = DateTimeFormatter.ofPattern("HH:mm")

    private val lectura: List<DateTimeFormatter> = listOf(
        "d MMMM yyyy H:mm" to ES, "d MMMM yyyy H:mm:ss" to ES,
        "MMMM d, yyyy h:mm a" to Locale.US, "MMMM d, yyyy h:mm:ss a" to Locale.US,
        "d/M/yyyy H:mm" to ES, "d/M/yyyy H:mm:ss" to ES, "d/M/yyyy h:mm a" to Locale.US,
        "M/d/yyyy h:mm a" to Locale.US, "yyyy-MM-dd HH:mm" to ES, "yyyy-MM-dd'T'HH:mm:ss" to ES,
    ).map { (p, l) -> DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(l) }

    fun texto(instante: Instant): String = escritura.format(instante.atZone(ZONA))
    fun hora(instante: Instant): String = horaFmt.format(instante.atZone(ZONA))
    fun dia(instante: Instant): LocalDate = instante.atZone(ZONA).toLocalDate()
    fun diaSesion(instante: Instant): String = dia(instante).atStartOfDay(ZONA).toInstant().toString()

    fun parsear(texto: String?): Instant? {
        val t = texto?.replace(' ', ' ')?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(t) }
        for (f in lectura) runCatching { return LocalDateTime.parse(t, f).atZone(ZONA).toInstant() }
        return null
    }

    private val coordLista = DecimalFormat("0.00000000", DecimalFormatSymbols(ES))
    private val coordMapa = DecimalFormat("0.00000000", DecimalFormatSymbols(Locale.US))
    private val coordApp = DecimalFormat("0.########", DecimalFormatSymbols(ES))

    fun coordenada(v: Double): String = coordLista.format(v)
    fun coordenadaMapa(v: Double): String = coordMapa.format(v)
    fun coordenadaUbicacionApp(v: Double?): String = v?.let { coordApp.format(it) }.orEmpty()
}
