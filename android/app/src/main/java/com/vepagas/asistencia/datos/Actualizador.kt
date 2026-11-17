package com.vepagas.asistencia.datos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vepagas.asistencia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** version.json que publica la CI en el servidor. */
@Serializable
data class InfoVersion(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val obligatoria: Boolean = false,
    val notas: String = "",
)

/**
 * Busca nuevas versiones en el backend (GET /api/app/version), descarga la APK (GET /api/app/apk),
 * verifica su SHA-256 y abre el instalador de Android. Android además exige que la APK nueva esté
 * firmada con la MISMA clave, así que no se puede instalar una APK ajena por este medio.
 */
class Actualizador(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    /** Devuelve la versión del servidor si es más nueva que la instalada; null si no hay o falla. */
    suspend fun buscar(): InfoVersion? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/app/version").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                ClienteApi.json.decodeFromString<InfoVersion>(r.body.string())
            }
        }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    /** Descarga y verifica la APK. Lanza excepción si falla o el hash no coincide. */
    suspend fun descargar(info: InfoVersion): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "actualizaciones").apply { mkdirs() }
        val destino = File(dir, "vepagas-asistencia.apk")
        http.newCall(Request.Builder().url("$base/app/apk").build()).execute().use { r ->
            check(r.isSuccessful) { "El servidor respondió ${r.code}" }
            destino.outputStream().use { r.body.byteStream().copyTo(it) }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(destino.readBytes()).joinToString("") { "%02x".format(it) }
        if (!hash.equals(info.sha256, ignoreCase = true)) {
            destino.delete()
            error("La descarga está dañada. Intente nuevamente.")
        }
        destino
    }

    /** true si Android ya permite a esta app instalar APKs (permiso "instalar apps desconocidas"). */
    fun puedeInstalar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Abre el ajuste para permitir que esta app instale actualizaciones (solo la primera vez). */
    fun pedirPermisoInstalacion(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        )
    }

    fun instalar(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${context.packageName}.archivos", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
