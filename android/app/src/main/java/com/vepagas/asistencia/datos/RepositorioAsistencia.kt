package com.vepagas.asistencia.datos

import kotlinx.serialization.Serializable
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

/** Resultado de una llamada al backend, con mensajes ya listos para mostrar. */
sealed interface Resultado<out T> {
    data class Ok<T>(val valor: T) : Resultado<T>
    /** El servidor rechazó la operación de forma definitiva (409 ya registrado, 400 validación...). */
    data class Rechazo(val codigo: String, val mensaje: String) : Resultado<Nothing>
    /** No se sabe si se aplicó (sin red, timeout, 5xx): reintentar con la MISMA Idempotency-Key. */
    data class Incierto(val mensaje: String) : Resultado<Nothing>
    /** Sesión vencida/revocada: volver al login. */
    data object SesionExpirada : Resultado<Nothing>
}

enum class Accion { INGRESO, SALIDA }

data class SesionLocal(val nombre: String, val correo: String)

class RepositorioAsistencia(private val api: ApiAsistencia, private val almacen: AlmacenSeguro) {

    fun sesionGuardada(): SesionLocal? {
        almacen.leer(CLAVE_TOKEN) ?: return null
        return SesionLocal(almacen.leer(CLAVE_NOMBRE) ?: "", almacen.leer(CLAVE_CORREO) ?: "")
    }

    suspend fun loginGoogle(idToken: String): Resultado<SesionLocal> =
        llamar { api.loginGoogle(GoogleLoginRequest(idToken)) }.guardarSesion()

    suspend fun loginDesarrollo(correo: String): Resultado<SesionLocal> =
        llamar { api.loginDesarrollo(LoginDesarrolloRequest(correo)) }.guardarSesion()

    suspend fun logout() {
        runCatching { api.logout() }
        almacen.limpiar()
    }

    suspend fun perfil(): Resultado<PerfilResponse> = llamar { api.perfil() }

    /** Botón "Tele trabajo" (UBICACION_APP). Best-effort: un fallo no impide continuar, igual que Power Apps. */
    suspend fun registrarAcceso(tipoTrabajo: String, lat: Double?, lon: Double?) {
        runCatching { api.acceso(AccesoRequest(tipoTrabajo, lat, lon)) }
    }

    suspend fun estado(tipoTrabajo: String): Resultado<EstadoJornada> = llamar { api.estado(tipoTrabajo) }

    /**
     * Marca ingreso/salida. Si un intento anterior de la MISMA acción y tipo quedó incierto,
     * reutiliza su Idempotency-Key para que el backend reanude en lugar de duplicar.
     */
    suspend fun marcar(accion: Accion, req: MarcacionRequest): Resultado<MarcacionResponse> {
        val pendiente = marcacionPendiente()
        val clave = if (pendiente != null && pendiente.accion == accion.name && pendiente.tipoTrabajo == req.tipoTrabajo)
            pendiente.clave
        else UUID.randomUUID().toString()

        guardarPendiente(MarcacionPendiente(accion.name, req.tipoTrabajo, clave))
        val r = llamar {
            if (accion == Accion.INGRESO) api.ingreso(clave, req) else api.salida(clave, req)
        }
        if (r !is Resultado.Incierto) guardarPendiente(null) // definitivo (éxito o rechazo): la clave ya no se reutiliza
        return r
    }

    /** Hay una marcación cuyo resultado no se pudo confirmar. */
    fun marcacionPendiente(): MarcacionPendiente? =
        almacen.leer(CLAVE_PENDIENTE)?.let { runCatching { ClienteApi.json.decodeFromString<MarcacionPendiente>(it) }.getOrNull() }

    private fun guardarPendiente(p: MarcacionPendiente?) =
        almacen.guardar(CLAVE_PENDIENTE, p?.let { ClienteApi.json.encodeToString(MarcacionPendiente.serializer(), it) })

    private fun Resultado<SesionResponse>.guardarSesion(): Resultado<SesionLocal> = when (this) {
        is Resultado.Ok -> {
            almacen.guardar(CLAVE_TOKEN, valor.token)
            almacen.guardar(CLAVE_NOMBRE, valor.empleado.nombre)
            almacen.guardar(CLAVE_CORREO, valor.empleado.correo)
            Resultado.Ok(SesionLocal(valor.empleado.nombre, valor.empleado.correo))
        }
        is Resultado.Rechazo -> this
        is Resultado.Incierto -> this
        Resultado.SesionExpirada -> Resultado.Rechazo("NO_AUTORIZADO", "No autorizado")
    }

    private suspend fun <T> llamar(bloque: suspend () -> T): Resultado<T> = try {
        Resultado.Ok(bloque())
    } catch (e: HttpException) {
        val error = runCatching {
            ClienteApi.json.decodeFromString<ErrorResponse>(e.response()?.errorBody()?.string().orEmpty())
        }.getOrNull()
        when {
            e.code() == 401 -> { almacen.guardar(CLAVE_TOKEN, null); Resultado.SesionExpirada }
            e.code() == 429 -> Resultado.Incierto("Demasiados intentos. Espere un minuto e intente nuevamente.")
            e.code() >= 500 -> Resultado.Incierto(error?.mensaje?.takeIf { it.isNotBlank() }
                ?: "El servidor no respondió correctamente. Intente nuevamente.")
            else -> Resultado.Rechazo(error?.codigo.orEmpty(), error?.mensaje?.takeIf { it.isNotBlank() } ?: "Solicitud rechazada (${e.code()}).")
        }
    } catch (_: IOException) {
        Resultado.Incierto("Sin conexión con el servidor. Verifique su internet e intente nuevamente.")
    }

    companion object {
        const val CLAVE_TOKEN = "token"
        private const val CLAVE_NOMBRE = "nombre"
        private const val CLAVE_CORREO = "correo"
        private const val CLAVE_PENDIENTE = "marcacion_pendiente"
    }
}

@Serializable
data class MarcacionPendiente(val accion: String, val tipoTrabajo: String, val clave: String)
