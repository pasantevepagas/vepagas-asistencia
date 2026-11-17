package com.vepagas.asistencia.datos

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda valores en SharedPreferences cifrados con AES-GCM usando una clave no exportable
 * del Android Keystore. Usado para el token de sesión del backend (nunca secretos de Graph).
 */
class AlmacenSeguro(context: Context, archivo: String = "vepagas_seguro") {
    private val prefs = context.getSharedPreferences(archivo, Context.MODE_PRIVATE)

    fun guardar(clave: String, valor: String?) {
        if (valor == null) {
            prefs.edit { remove(clave) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, claveMaestra()) }
        val cifrado = cipher.doFinal(valor.toByteArray(Charsets.UTF_8))
        val datos = cipher.iv + cifrado
        prefs.edit { putString(clave, Base64.encodeToString(datos, Base64.NO_WRAP)) }
    }

    fun leer(clave: String): String? {
        val b64 = prefs.getString(clave, null) ?: return null
        return try {
            val datos = Base64.decode(b64, Base64.NO_WRAP)
            val iv = datos.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMACION).apply {
                init(Cipher.DECRYPT_MODE, claveMaestra(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(datos.copyOfRange(IV_BYTES, datos.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Clave del Keystore perdida/rotada: se descarta el valor (el usuario vuelve a iniciar sesión).
            prefs.edit { remove(clave) }
            null
        }
    }

    fun limpiar() = prefs.edit { clear() }

    private fun claveMaestra(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "vepagas_sesion"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
