package com.vepagas.asistencia.datos

import android.content.Context
import java.util.UUID

/**
 * Identificador de ESTA instalación (equivale al GUID que Power Apps guardaba con SaveData "GUID").
 * Se genera una sola vez y se guarda cifrado con el Android Keystore en un archivo aparte, de modo que
 * cerrar sesión NO lo borra. Se pierde si se desinstala la app o se borran sus datos: entonces el
 * backend lo verá como un teléfono nuevo y TI deberá habilitarlo (igual que en Power Apps).
 * No usa IMEI ni Android ID.
 */
class IdentidadDispositivo(context: Context) {
    private val almacen = AlmacenSeguro(context, "vepagas_dispositivo")

    val id: String by lazy {
        almacen.leer(CLAVE) ?: UUID.randomUUID().toString().also { almacen.guardar(CLAVE, it) }
    }

    private companion object { const val CLAVE = "id_dispositivo" }
}
