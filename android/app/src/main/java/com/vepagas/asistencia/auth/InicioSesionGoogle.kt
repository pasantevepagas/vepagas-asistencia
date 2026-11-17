package com.vepagas.asistencia.auth

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.vepagas.asistencia.BuildConfig

sealed interface ResultadoGoogle {
    data class Ok(val idToken: String) : ResultadoGoogle
    data object Cancelado : ResultadoGoogle
    data class Error(val mensaje: String) : ResultadoGoogle
}

/**
 * Inicio de sesión con Google (Credential Manager). Solo obtiene el ID token;
 * la verificación (firma, audience, expiración) y la autorización las hace el BACKEND.
 */
object InicioSesionGoogle {

    suspend fun obtenerIdToken(activity: Activity): ResultadoGoogle {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.startsWith("PENDIENTE"))
            return ResultadoGoogle.Error("Falta configurar vepagas.googleWebClientId en gradle.properties.")

        val opcion = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val solicitud = GetCredentialRequest.Builder().addCredentialOption(opcion).build()

        return try {
            val respuesta = CredentialManager.create(activity).getCredential(activity, solicitud)
            val credencial = respuesta.credential
            if (credencial is CustomCredential && credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                ResultadoGoogle.Ok(GoogleIdTokenCredential.createFrom(credencial.data).idToken)
            } else {
                ResultadoGoogle.Error("Tipo de credencial no soportado.")
            }
        } catch (_: GetCredentialCancellationException) {
            ResultadoGoogle.Cancelado
        } catch (e: GetCredentialException) {
            ResultadoGoogle.Error("No se pudo iniciar sesión con Google: ${e.message ?: e.type}")
        }
    }

    suspend fun cerrar(activity: Activity) {
        runCatching { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
