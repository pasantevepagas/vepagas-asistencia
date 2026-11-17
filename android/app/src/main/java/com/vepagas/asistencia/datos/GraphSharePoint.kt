package com.vepagas.asistencia.datos

import com.vepagas.asistencia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Error HTTP de Graph. ambiguo = no se sabe si la escritura se aplicó (timeout / 5xx). */
class ErrorGraph(mensaje: String, val estado: Int?, val ambiguo: Boolean) : IOException(mensaje)

data class Jornada(val itemId: String, val idReg: String, val tipo: String, val inicio: Instant, val salida: Instant?)
data class Dispositivo(val itemId: String, val idDispositivo: String, val titulo: String)

/**
 * Acceso directo a las listas SharePoint del sitio "Desarrollo de Aplicaciones Power App" vía Microsoft Graph,
 * con el token DELEGADO del empleado. Mismas columnas y formatos que Power Apps.
 */
class GraphSharePoint(private val token: suspend () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private val base = "https://graph.microsoft.com/v1.0/sites/${BuildConfig.SITE_ID}/lists"
    private val tipoJson = "application/json".toMediaType()

    /** Hora oficial: cabecera Date de la última respuesta de Microsoft (no el reloj del teléfono). */
    @Volatile private var horaServidor: Instant? = null
    fun ahora(): Instant = horaServidor ?: Instant.now()

    // ---------------- registros_tb / ubicacion_db ----------------

    suspend fun jornadasDe(idUsuario: String): List<Jornada> =
        listar(BuildConfig.LISTA_REGISTROS, "ID_USUARIO", idUsuario, "ID_USUARIO,FECHA_HORA_INICIO,FECHA_HORA_SALIDA,ID_REG,TIPO_TRABAJO")
            .mapNotNull { item ->
                val f = item.campos()
                if (f.texto("ID_USUARIO") != idUsuario) return@mapNotNull null
                val inicio = FormatoFecha.parsear(f.texto("FECHA_HORA_INICIO")) ?: return@mapNotNull null
                Jornada(item.texto("id"), f.texto("ID_REG"), f.texto("TIPO_TRABAJO").trim(), inicio, FormatoFecha.parsear(f.texto("FECHA_HORA_SALIDA")))
            }

    suspend fun crearJornada(idUsuario: String, idReg: String, tipo: String, inicio: Instant): String =
        crear(BuildConfig.LISTA_REGISTROS, buildJsonObject {
            put("ID_USUARIO", idUsuario)
            put("FECHA_HORA_INICIO", FormatoFecha.texto(inicio))
            put("ID_REG", idReg)
            put("TIPO_TRABAJO", tipo)
        })

    suspend fun registrarSalida(itemId: String, salida: Instant) =
        actualizar(BuildConfig.LISTA_REGISTROS, itemId, buildJsonObject { put("FECHA_HORA_SALIDA", FormatoFecha.texto(salida)) })

    suspend fun crearUbicacion(idReg: String, hora: Instant, lat: Double, lon: Double): String =
        crear(BuildConfig.LISTA_UBICACIONES, buildJsonObject {
            put("ID_FECHA", idReg)
            put("HORA", FormatoFecha.hora(hora))
            put("LAT", FormatoFecha.coordenada(lat))
            put("LONG", FormatoFecha.coordenada(lon))
            put("UBICACION", "https://www.google.com/maps/place/${FormatoFecha.coordenadaMapa(lat)},${FormatoFecha.coordenadaMapa(lon)}")
        })

    // ---------------- dispositivos_db / sesion_db / UBICACION_APP ----------------

    /** Primera fila del usuario (como LookUp de Power Apps). Columna interna de "ID_USER" = ID_DISP. */
    suspend fun dispositivoDe(idUsuario: String): Dispositivo? =
        listar(BuildConfig.LISTA_DISPOSITIVOS, "ID_DISP", idUsuario, "ID_DISP,ID_DISPOSITIVO,Title")
            .filter { it.campos().texto("ID_DISP") == idUsuario }
            .minByOrNull { it.texto("id").toIntOrNull() ?: Int.MAX_VALUE }
            ?.let { Dispositivo(it.texto("id"), it.campos().texto("ID_DISPOSITIVO").trim(), it.campos().texto("Title")) }

    suspend fun crearDispositivo(idUsuario: String, idDispositivo: String, nombre: String) {
        crear(BuildConfig.LISTA_DISPOSITIVOS, buildJsonObject {
            put("ID_DISP", idUsuario); put("ID_DISPOSITIVO", idDispositivo); put("NOMBRE", nombre); put("SESSION", "AUTH"); put("Title", MARCA_APK)
        })
    }

    /** Migración única desde Power Apps: la fila pasa a este teléfono y queda marcada como de la APK. */
    suspend fun migrarDispositivo(itemId: String, idDispositivo: String) =
        actualizar(BuildConfig.LISTA_DISPOSITIVOS, itemId, buildJsonObject { put("ID_DISPOSITIVO", idDispositivo); put("Title", MARCA_APK) })

    suspend fun registrarSesion(idUsuario: String, resultado: String) {
        crear(BuildConfig.LISTA_SESIONES, buildJsonObject {
            put("ID_USUARIO", idUsuario); put("FECHA", FormatoFecha.diaSesion(ahora())); put("SESSION", resultado)
        })
    }

    suspend fun registrarUbicacionApp(lat: Double?, lon: Double?, nombre: String) {
        crear(BuildConfig.LISTA_UBICACION_APP, buildJsonObject {
            put("UBICACION", "${FormatoFecha.coordenadaUbicacionApp(lat)} ${FormatoFecha.coordenadaUbicacionApp(lon)}")
            put("NOMBRE", nombre)
        })
    }

    // ---------------- HTTP ----------------

    private suspend fun listar(lista: String, campo: String, valor: String, select: String): List<JsonObject> {
        val filtro = URLEncoder.encode("fields/$campo eq '${valor.replace("'", "''")}'", "UTF-8").replace("+", "%20")
        var url: String? = "$base/$lista/items?\$expand=fields(\$select=$select)&\$filter=$filtro&\$top=200"
        val todos = mutableListOf<JsonObject>()
        var paginas = 0
        while (url != null && paginas++ < 25) {
            val r = enviar("GET", url, null).jsonObject
            todos += r["value"]!!.jsonArray.map { it.jsonObject }
            url = (r["@odata.nextLink"] as? JsonPrimitive)?.content
        }
        return todos
    }

    private suspend fun crear(lista: String, campos: JsonObject): String =
        enviar("POST", "$base/$lista/items", JsonObject(mapOf("fields" to campos))).jsonObject.texto("id")

    private suspend fun actualizar(lista: String, itemId: String, campos: JsonObject) {
        enviar("PATCH", "$base/$lista/items/$itemId/fields", campos)
    }

    /** POST no se reintenta (podría duplicar); GET/PATCH sí ante 429/503/504 y fallos de red. */
    private suspend fun enviar(metodo: String, url: String, cuerpo: JsonElement?): JsonElement = withContext(Dispatchers.IO) {
        val reintentable = metodo != "POST"
        var intento = 0
        while (true) {
            intento++
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token()}")
                .header("Prefer", "HonorNonIndexedQueriesWarningMayFailRandomly")
                .method(metodo, cuerpo?.toString()?.toRequestBody(tipoJson))
                .build()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: IOException) {
                if (reintentable && intento < 3) { delay(800L * intento); continue }
                throw ErrorGraph("Sin conexión con Microsoft", null, ambiguo = !reintentable)
            }
            resp.use { r ->
                r.header("Date")?.let { d -> runCatching { horaServidor = ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } }
                val texto = r.body.string()
                if (r.isSuccessful) return@withContext if (texto.isBlank()) JsonObject(emptyMap()) else JsonApp.parseToJsonElement(texto)
                if (reintentable && r.code in setOf(429, 503, 504) && intento < 3) {
                    delay((r.header("Retry-After")?.toLongOrNull() ?: intento.toLong()) * 1000)
                    return@use
                }
                throw ErrorGraph("SharePoint respondió ${r.code}", r.code, ambiguo = !reintentable && r.code >= 500)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("inalcanzable")
    }

    private fun JsonObject.campos(): JsonObject = this["fields"]!!.jsonObject
    private fun JsonObject.texto(campo: String): String = when (val v = this[campo]) {
        null -> ""
        is JsonPrimitive -> v.content
        is JsonArray, is JsonObject -> v.toString()
    }

    companion object {
        /** Marca en dispositivos_db.Title de las filas que ya pertenecen a la APK (fin de la migración desde Power Apps). */
        const val MARCA_APK = "APK"
    }
}
