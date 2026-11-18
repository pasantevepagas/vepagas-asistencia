package com.vepagas.asistencia.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.vepagas.asistencia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Empleado autenticado. oid = Object ID en el tenant VEPAGAS (lo mismo que User().EntraObjectId). */
data class Cuenta(val oid: String, val nombre: String, val correo: String)

/** No hay sesión válida: hay que iniciar sesión de nuevo. */
class SinSesion : Exception("Sesión de Microsoft no disponible")

/**
 * Inicio de sesión con la cuenta Microsoft del tenant VEPAGAS (MSAL, cliente público, SIN secreto).
 * Los empleados Gmail entran como invitados del tenant con su propio correo. La app escribe en SharePoint
 * con los permisos de ESE empleado (delegado), igual que Power Apps.
 */
class SesionMicrosoft(private val context: Context) {
    private val alcances = listOf(
        "https://graph.microsoft.com/Sites.ReadWrite.All",
        "https://graph.microsoft.com/User.Read",
    )
    private val candado = Mutex()
    private var cliente: ISingleAccountPublicClientApplication? = null

    val configurada: Boolean get() = !BuildConfig.MSAL_CLIENT_ID.startsWith("PENDIENTE")

    private suspend fun cliente(): ISingleAccountPublicClientApplication = candado.withLock {
        cliente?.let { return it }
        check(configurada) { "Falta vepagas.msalClientId en gradle.properties" }
        val config = File(context.filesDir, "msal_config.json").apply { writeText(configuracionMsal()) }
        suspendCancellableCoroutine { cont ->
            PublicClientApplication.createSingleAccountPublicClientApplication(context, config,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(app: ISingleAccountPublicClientApplication) { cliente = app; cont.resume(app) }
                    override fun onError(e: MsalException) = cont.resumeWithException(e)
                })
        }
    }

    /** Muestra la pantalla de Microsoft. Lanza MsalException si falla o el usuario cancela. */
    suspend fun iniciar(activity: Activity): Cuenta {
        val app = cliente()
        withContext(Dispatchers.IO) { if (app.currentAccount?.currentAccount != null) app.signOut() }
        val resultado = suspendCancellableCoroutine<IAuthenticationResult> { cont ->
            app.signIn(
                SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(alcances)
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(r: IAuthenticationResult) = cont.resume(r)
                        override fun onError(e: MsalException) = cont.resumeWithException(e)
                        override fun onCancel() = cont.resumeWithException(CanceladoPorUsuario())
                    })
                    .build()
            )
        }
        return cuentaDe(resultado.account)
    }

    /** Cuenta con sesión guardada en el teléfono, o null. */
    suspend fun cuentaActual(): Cuenta? = runCatching {
        withContext(Dispatchers.IO) { cliente().currentAccount?.currentAccount?.let(::cuentaDe) }
    }.getOrNull()

    /** Token de acceso para Graph (renovación silenciosa). Lanza SinSesion si hay que volver a iniciar sesión. */
    suspend fun token(): String = withContext(Dispatchers.IO) {
        val app = cliente()
        val cuenta = app.currentAccount?.currentAccount ?: throw SinSesion()
        try {
            app.acquireTokenSilent(
                AcquireTokenSilentParameters.Builder()
                    .forAccount(cuenta)
                    .fromAuthority(cuenta.authority)
                    .withScopes(alcances)
                    .build()
            ).accessToken
        } catch (_: MsalUiRequiredException) {
            throw SinSesion()
        }
    }

    suspend fun cerrar() {
        runCatching { withContext(Dispatchers.IO) { cliente().signOut() } }
    }

    private fun cuentaDe(cuenta: IAccount): Cuenta {
        val c = cuenta.claims.orEmpty()
        val correo = (c["email"] ?: c["preferred_username"] ?: cuenta.username)?.toString().orEmpty()
        return Cuenta(
            oid = (c["oid"] ?: cuenta.id).toString(),
            nombre = (c["name"] ?: correo).toString(),
            correo = correo,
        )
    }

    private fun configuracionMsal(): String {
        val redirect = "msauth://${context.packageName}/${URLEncoder.encode(BuildConfig.FIRMA_HASH, "UTF-8")}"
        return """
        {
          "client_id": "${BuildConfig.MSAL_CLIENT_ID}",
          "authorization_user_agent": "WEBVIEW",
          "redirect_uri": "$redirect",
          "account_mode": "SINGLE",
          "broker_redirect_uri_registered": false,
          "authorities": [
            { "type": "AAD", "default": true,
              "audience": { "type": "AzureADMyOrg", "tenant_id": "${BuildConfig.TENANT_ID}" } }
          ]
        }
        """.trimIndent()
    }
}

class CanceladoPorUsuario : Exception("Inicio de sesión cancelado")
