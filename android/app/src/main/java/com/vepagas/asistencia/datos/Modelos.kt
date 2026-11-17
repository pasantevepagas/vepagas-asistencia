package com.vepagas.asistencia.datos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonApp = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** valor = lo que se guarda en TIPO_TRABAJO ("Home_Office"); etiqueta = texto del botón ("Tele trabajo"). */
@Serializable data class TipoTrabajo(val valor: String, val etiqueta: String)

/** Mismo `type` que usaba Power Apps. */
val TIPOS_TRABAJO = listOf(TipoTrabajo("Home_Office", "Tele trabajo"))

/** dispositivo: AUTORIZADO | REGISTRADO | MIGRADO (resultado del control de dispositivos_db). */
data class PerfilResponse(val nombre: String, val correo: String, val tiposTrabajo: List<TipoTrabajo>, val dispositivo: String)

/** estado: SIN_INGRESO | EN_JORNADA | JORNADA_CERRADA. Horas en hora de Ecuador (HH:mm). */
data class EstadoJornada(
    val fecha: String,
    val tipoTrabajo: String,
    val estado: String,
    val horaIngreso: String? = null,
    val horaSalida: String? = null,
    val ubicacionPendiente: Boolean = false,
)

data class MarcacionRequest(val tipoTrabajo: String, val lat: Double, val lon: Double, val accuracyMeters: Double)

data class MarcacionResponse(val mensaje: String, val ubicacionPendiente: Boolean, val estado: EstadoJornada)

/** Resultado de una operación, con mensajes ya listos para mostrar. */
sealed interface Resultado<out T> {
    data class Ok<T>(val valor: T) : Resultado<T>
    /** Rechazo definitivo ("Su ingreso ya fue registrado", dispositivo no autorizado, sin permiso...). */
    data class Rechazo(val codigo: String, val mensaje: String) : Resultado<Nothing>
    /** No se sabe si se aplicó (sin red, error del servidor): reintentar retoma la misma operación. */
    data class Incierto(val mensaje: String) : Resultado<Nothing>
    /** La sesión de Microsoft expiró o se revocó: volver a iniciar sesión. */
    data object SesionExpirada : Resultado<Nothing>
}

enum class Accion { INGRESO, SALIDA }

data class SesionLocal(val nombre: String, val correo: String)

/** Rechazo de negocio interno; se convierte en Resultado.Rechazo. */
class RechazoNegocio(val codigo: String, mensaje: String) : Exception(mensaje)
