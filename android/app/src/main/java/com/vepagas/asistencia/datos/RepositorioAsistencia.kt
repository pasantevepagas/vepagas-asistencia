package com.vepagas.asistencia.datos

import android.app.Activity
import com.microsoft.identity.client.exception.MsalException
import com.vepagas.asistencia.auth.CanceladoPorUsuario
import com.vepagas.asistencia.auth.SesionMicrosoft
import com.vepagas.asistencia.auth.SinSesion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Marcación en curso guardada en el teléfono para reanudarla sin duplicar si se corta la conexión. */
@Serializable
data class OperacionPendiente(
    val accion: String,
    val tipo: String,
    val fecha: String,
    val idReg: String,
    val horaIso: String,
    val inicioIso: String,
    val itemIdRegistro: String? = null,
    val registroHecho: Boolean = false,
    val lat: Double,
    val lon: Double,
)

/**
 * Toda la lógica que antes hacía Power Apps, ahora en la app y directo contra SharePoint:
 *  - OnStart: control de dispositivos_db + sesion_db (GOOD/BAD).
 *  - Tele trabajo: UBICACION_APP.
 *  - Ingreso: único por usuario + día (Ecuador) + tipo; fila registros_tb + fila ubicacion_db (mismo GUID).
 *  - Salida: sólo con ingreso y sin salida previa; PATCH FECHA_HORA_SALIDA + segunda ubicación.
 */
class RepositorioAsistencia(
    private val sesion: SesionMicrosoft,
    private val graph: GraphSharePoint,
    private val almacen: AlmacenSeguro,
    private val dispositivo: IdentidadDispositivo,
) {
    private val candado = Mutex()
    private var cuenta: com.vepagas.asistencia.auth.Cuenta? = null

    // ---------------- Sesión ----------------

    fun sesionGuardada(): SesionLocal? {
        val nombre = almacen.leer(CLAVE_NOMBRE) ?: return null
        return SesionLocal(nombre, almacen.leer(CLAVE_CORREO).orEmpty())
    }

    suspend fun iniciarSesion(activity: Activity): Resultado<SesionLocal> = try {
        if (!sesion.configurada) Resultado.Rechazo("SIN_CONFIGURAR", "La app aún no tiene configurado el registro de Microsoft (vepagas.msalClientId).")
        else {
            val c = sesion.iniciar(activity)
            cuenta = c
            almacen.guardar(CLAVE_NOMBRE, c.nombre)
            almacen.guardar(CLAVE_CORREO, c.correo)
            Resultado.Ok(SesionLocal(c.nombre, c.correo))
        }
    } catch (_: CanceladoPorUsuario) {
        Resultado.Rechazo("CANCELADO", "Inicio de sesión cancelado.")
    } catch (e: MsalException) {
        Resultado.Rechazo(e.errorCode ?: "MSAL", "No se pudo iniciar sesión con Microsoft: ${e.message}")
    }

    suspend fun cerrarSesion() {
        sesion.cerrar()
        cuenta = null
        almacen.guardar(CLAVE_NOMBRE, null)
        almacen.guardar(CLAVE_CORREO, null)
        almacen.guardar(CLAVE_OPERACIONES, null)
    }

    private suspend fun cuentaActual() = cuenta ?: (sesion.cuentaActual() ?: throw SinSesion()).also { cuenta = it }

    // ---------------- OnStart: dispositivo ----------------

    /** Equivale al OnStart de Power Apps. También reintenta ubicaciones que quedaron pendientes. */
    suspend fun perfil(): Resultado<PerfilResponse> = intentar {
        val c = cuentaActual()
        val idApp = dispositivo.id
        val fila = graph.dispositivoDe(c.oid)
        val resultado = when {
            fila == null -> { graph.crearDispositivo(c.oid, idApp, c.nombre); "REGISTRADO" }
            fila.idDispositivo.equals(idApp, ignoreCase = true) -> "AUTORIZADO"
            // Fila heredada de Power Apps (sin marca): se pasa a este teléfono UNA sola vez.
            fila.titulo != GraphSharePoint.MARCA_APK -> { graph.migrarDispositivo(fila.itemId, idApp); "MIGRADO" }
            else -> "BAD"
        }
        runCatching { graph.registrarSesion(c.oid, if (resultado == "BAD") "BAD" else "GOOD") }
        if (resultado == "BAD")
            throw RechazoNegocio("DISPOSITIVO_NO_AUTORIZADO", "Este teléfono no está autorizado para su usuario. Si cambió de teléfono, contacte a TI para habilitar el nuevo.")
        runCatching { completarUbicacionesPendientes() }
        PerfilResponse(c.nombre, c.correo, TIPOS_TRABAJO, resultado)
    }

    /** Botón "Tele trabajo": fila en UBICACION_APP. Un fallo no impide continuar (igual que Power Apps). */
    suspend fun registrarAcceso(lat: Double?, lon: Double?) {
        runCatching { graph.registrarUbicacionApp(lat, lon, cuentaActual().nombre) }
    }

    // ---------------- Jornada ----------------

    suspend fun estado(tipo: String): Resultado<EstadoJornada> = intentar {
        val c = cuentaActual()
        val jornada = jornadaDeHoy(c.oid, tipo)
        val hoy = FormatoFecha.dia(graph.ahora()).toString()
        EstadoJornada(
            fecha = hoy,
            tipoTrabajo = tipo,
            estado = when { jornada == null -> "SIN_INGRESO"; jornada.salida == null -> "EN_JORNADA"; else -> "JORNADA_CERRADA" },
            horaIngreso = jornada?.inicio?.let(FormatoFecha::hora),
            horaSalida = jornada?.salida?.let(FormatoFecha::hora),
            ubicacionPendiente = operaciones().any { it.tipo == tipo && it.registroHecho },
        )
    }

    /** Hay una marcación cuyo resultado no se pudo confirmar (se retoma al volver a timbrar). */
    fun marcacionPendiente(): OperacionPendiente? = operaciones().firstOrNull { !it.registroHecho }

    suspend fun marcar(accion: Accion, req: MarcacionRequest): Resultado<MarcacionResponse> = intentar {
        candado.withLock {
            val c = cuentaActual()
            var op = operaciones().firstOrNull { it.accion == accion.name && it.tipo == req.tipoTrabajo && !it.registroHecho }
            val reanudando = op != null

            if (op == null) {
                val jornada = jornadaDeHoy(c.oid, req.tipoTrabajo) // también actualiza la hora oficial
                val ahora = graph.ahora()
                op = if (accion == Accion.INGRESO) {
                    if (jornada != null) throw RechazoNegocio("INGRESO_YA_REGISTRADO", "Su ingreso ya fue registrado")
                    OperacionPendiente(accion.name, req.tipoTrabajo, FormatoFecha.dia(ahora).toString(), UUID.randomUUID().toString(),
                        ahora.toString(), ahora.toString(), lat = req.lat, lon = req.lon)
                } else {
                    jornada ?: throw RechazoNegocio("SIN_INGRESO", "No se ha registrado su entrada")
                    if (jornada.salida != null) throw RechazoNegocio("SALIDA_YA_REGISTRADA", "Su salida ya fue registrada")
                    OperacionPendiente(accion.name, req.tipoTrabajo, FormatoFecha.dia(ahora).toString(), jornada.idReg,
                        ahora.toString(), jornada.inicio.toString(), jornada.itemId, lat = req.lat, lon = req.lon)
                }
                guardarOperacion(op)
            }

            // Paso 1: registros_tb
            if (accion == Accion.INGRESO) {
                if (reanudando) {
                    // Un intento anterior pudo haber creado la fila aunque se perdió la respuesta.
                    val existente = jornadaDeHoy(c.oid, op.tipo)
                    if (existente != null && existente.idReg != op.idReg) {
                        quitarOperacion(op); throw RechazoNegocio("INGRESO_YA_REGISTRADO", "Su ingreso ya fue registrado")
                    }
                    op = op.copy(itemIdRegistro = existente?.itemId)
                }
                if (op.itemIdRegistro == null)
                    op = op.copy(itemIdRegistro = graph.crearJornada(c.oid, op.idReg, op.tipo, Instant.parse(op.horaIso)))
            } else {
                graph.registrarSalida(op.itemIdRegistro!!, Instant.parse(op.horaIso)) // PATCH: idempotente
            }
            op = op.copy(registroHecho = true)
            guardarOperacion(op)

            // Paso 2: ubicacion_db (si falla, la marcación vale y la ubicación se reintenta al abrir la app)
            val ubicacionOk = runCatching { graph.crearUbicacion(op.idReg, Instant.parse(op.horaIso), op.lat, op.lon) }.isSuccess
            if (ubicacionOk) quitarOperacion(op)

            val ingreso = accion == Accion.INGRESO
            MarcacionResponse(
                mensaje = when {
                    ingreso && ubicacionOk -> "Se registró el ingreso correctamente"
                    !ingreso && ubicacionOk -> "Se registró la salida correctamente"
                    ingreso -> "Ingreso registrado. La ubicación se enviará al abrir la app; no repita la marcación."
                    else -> "Salida registrada. La ubicación se enviará al abrir la app; no repita la marcación."
                },
                ubicacionPendiente = !ubicacionOk,
                estado = EstadoJornada(
                    fecha = op.fecha, tipoTrabajo = op.tipo,
                    estado = if (ingreso) "EN_JORNADA" else "JORNADA_CERRADA",
                    horaIngreso = FormatoFecha.hora(Instant.parse(op.inicioIso)),
                    horaSalida = if (ingreso) null else FormatoFecha.hora(Instant.parse(op.horaIso)),
                    ubicacionPendiente = !ubicacionOk,
                ),
            )
        }
    }

    private suspend fun completarUbicacionesPendientes() = candado.withLock {
        for (op in operaciones().filter { it.registroHecho }) {
            runCatching { graph.crearUbicacion(op.idReg, Instant.parse(op.horaIso), op.lat, op.lon) }
                .onSuccess { quitarOperacion(op) }
        }
    }

    private suspend fun jornadaDeHoy(oid: String, tipo: String): Jornada? {
        val jornadas = graph.jornadasDe(oid)
        val hoy = FormatoFecha.dia(graph.ahora())
        return jornadas.filter { FormatoFecha.dia(it.inicio) == hoy && it.tipo.equals(tipo, ignoreCase = true) }.minByOrNull { it.inicio }
    }

    // ---------------- Operaciones pendientes (cifradas en el teléfono) ----------------

    private val serializador = ListSerializer(OperacionPendiente.serializer())

    private fun operaciones(): List<OperacionPendiente> =
        almacen.leer(CLAVE_OPERACIONES)?.let { runCatching { JsonApp.decodeFromString(serializador, it) }.getOrNull() }.orEmpty()

    private fun guardarOperacion(op: OperacionPendiente) =
        escribir(operaciones().filterNot { it.idReg == op.idReg && it.accion == op.accion } + op)

    private fun quitarOperacion(op: OperacionPendiente) =
        escribir(operaciones().filterNot { it.idReg == op.idReg && it.accion == op.accion })

    private fun escribir(lista: List<OperacionPendiente>) =
        almacen.guardar(CLAVE_OPERACIONES, if (lista.isEmpty()) null else JsonApp.encodeToString(serializador, lista))

    // ---------------- Errores -> Resultado ----------------

    private suspend fun <T> intentar(bloque: suspend () -> T): Resultado<T> = try {
        Resultado.Ok(bloque())
    } catch (e: RechazoNegocio) {
        Resultado.Rechazo(e.codigo, e.message.orEmpty())
    } catch (_: SinSesion) {
        Resultado.SesionExpirada
    } catch (e: ErrorGraph) {
        when (e.estado) {
            401 -> Resultado.SesionExpirada
            403 -> Resultado.Rechazo("SIN_PERMISO", "Su usuario no tiene permiso en las listas de SharePoint. Contacte a TI.")
            null -> Resultado.Incierto("Sin conexión. Verifique su internet e intente nuevamente.")
            else -> Resultado.Incierto("SharePoint no respondió correctamente (${e.estado}). Intente nuevamente.")
        }
    } catch (_: IOException) {
        Resultado.Incierto("Sin conexión. Verifique su internet e intente nuevamente.")
    } catch (e: MsalException) {
        Resultado.Incierto("Error de autenticación: ${e.message}")
    }

    private companion object {
        const val CLAVE_NOMBRE = "nombre"
        const val CLAVE_CORREO = "correo"
        const val CLAVE_OPERACIONES = "operaciones"
    }
}
