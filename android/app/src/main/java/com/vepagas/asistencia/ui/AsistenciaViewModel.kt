package com.vepagas.asistencia.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vepagas.asistencia.VepagasApp
import com.vepagas.asistencia.auth.InicioSesionGoogle
import com.vepagas.asistencia.auth.ResultadoGoogle
import com.vepagas.asistencia.datos.Accion
import com.vepagas.asistencia.datos.EstadoJornada
import com.vepagas.asistencia.datos.MarcacionRequest
import com.vepagas.asistencia.datos.Resultado
import com.vepagas.asistencia.datos.SesionLocal
import com.vepagas.asistencia.datos.TipoTrabajo
import com.vepagas.asistencia.ubicacion.ResultadoUbicacion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TipoMensaje { EXITO, ERROR, AVISO }
data class Mensaje(val texto: String, val tipo: TipoMensaje)

/**
 * Mismo flujo que la app Power Apps:
 *  TIPOS ("Tele trabajo") -> MENU ("¡Bienvenido!" Ingreso / salida) -> REGISTRO ("Timbrar")
 */
enum class Pantalla { TIPOS, MENU, REGISTRO }

data class UbicacionVista(val lat: Double, val lon: Double, val precisionMetros: Double)

data class UiEstado(
    val iniciando: Boolean = true,
    val sesion: SesionLocal? = null,
    val pantalla: Pantalla = Pantalla.TIPOS,
    val tipos: List<TipoTrabajo> = emptyList(),
    /** Valor de TIPO_TRABAJO elegido (p. ej. "Home_Office"). */
    val tipo: String? = null,
    val accion: Accion = Accion.INGRESO,
    val jornada: EstadoJornada? = null,
    val cargando: Boolean = false,
    val procesando: Boolean = false,
    val buscandoUbicacion: Boolean = false,
    val ubicacion: UbicacionVista? = null,
    val mensaje: Mensaje? = null,
    val gpsApagado: Boolean = false,
    val marcacionSinConfirmar: Boolean = false,
    /** Mensaje de bloqueo cuando el teléfono no está autorizado en dispositivos_db. */
    val bloqueado: String? = null,
) {
    /** Texto visible del tipo elegido ("Tele trabajo"). */
    val etiquetaTipo: String get() = tipos.firstOrNull { it.valor == tipo }?.etiqueta ?: tipo.orEmpty()
}

class AsistenciaViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as VepagasApp).repositorio
    private val proveedorUbicacion = (app as VepagasApp).ubicacion
    private var trabajoUbicacion: Job? = null

    private val _ui = MutableStateFlow(UiEstado())
    val ui: StateFlow<UiEstado> = _ui.asStateFlow()

    init {
        val sesion = repo.sesionGuardada()
        _ui.update { it.copy(iniciando = false, sesion = sesion, marcacionSinConfirmar = repo.marcacionPendiente() != null) }
        if (sesion != null) cargarPerfil()
    }

    // ---------------- Sesión ----------------

    fun loginGoogle(activity: Activity) = viewModelScope.launch {
        _ui.update { it.copy(cargando = true, mensaje = null) }
        when (val g = InicioSesionGoogle.obtenerIdToken(activity)) {
            is ResultadoGoogle.Ok -> procesarLogin(repo.loginGoogle(g.idToken))
            // Google también devuelve "cancelado" ante errores de configuración (p. ej. [16] Account reauth failed).
            ResultadoGoogle.Cancelado -> _ui.update {
                it.copy(cargando = false, mensaje = Mensaje("No se completó el inicio de sesión con Google. Si no lo canceló usted, avise a TI.", TipoMensaje.AVISO))
            }
            is ResultadoGoogle.Error -> _ui.update { it.copy(cargando = false, mensaje = Mensaje(g.mensaje, TipoMensaje.ERROR)) }
        }
    }

    fun loginDesarrollo(correo: String) = viewModelScope.launch {
        _ui.update { it.copy(cargando = true, mensaje = null) }
        procesarLogin(repo.loginDesarrollo(correo.trim()))
    }

    private fun procesarLogin(r: Resultado<SesionLocal>) {
        when (r) {
            is Resultado.Ok -> { _ui.update { it.copy(cargando = false, sesion = r.valor) }; cargarPerfil() }
            is Resultado.Rechazo -> _ui.update { it.copy(cargando = false, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR)) }
            is Resultado.Incierto -> _ui.update { it.copy(cargando = false, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR)) }
            Resultado.SesionExpirada -> _ui.update { it.copy(cargando = false) }
        }
    }

    fun cerrarSesion(activity: Activity) = viewModelScope.launch {
        repo.logout()
        InicioSesionGoogle.cerrar(activity)
        _ui.value = UiEstado(iniciando = false)
    }

    private fun sesionExpirada() {
        _ui.value = UiEstado(iniciando = false, mensaje = Mensaje("Su sesión expiró. Inicie sesión nuevamente.", TipoMensaje.AVISO))
    }

    /** Al abrir la app (init) equivale al OnStart: el backend verifica el dispositivo y escribe sesion_db. */
    private fun cargarPerfil() = viewModelScope.launch {
        _ui.update { it.copy(cargando = true) }
        when (val r = repo.perfil()) {
            // Como el OnStart de Power Apps: Notify("Dispositivo autorizado").
            is Resultado.Ok -> _ui.update {
                it.copy(
                    cargando = false, bloqueado = null,
                    tipos = r.valor.tiposTrabajo,
                    sesion = SesionLocal(r.valor.nombre, r.valor.correo),
                    mensaje = Mensaje(
                        if (r.valor.dispositivo == "REGISTRADO" || r.valor.dispositivo == "MIGRADO") "Dispositivo registrado y autorizado" else "Dispositivo autorizado",
                        TipoMensaje.EXITO,
                    ),
                )
            }
            // Otro teléfono (Power Apps hacía Exit()): se bloquea la app con el mensaje.
            is Resultado.Rechazo -> _ui.update {
                if (r.codigo == "DISPOSITIVO_NO_AUTORIZADO") it.copy(cargando = false, tipos = emptyList(), bloqueado = r.mensaje, mensaje = null)
                else it.copy(cargando = false, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR))
            }
            is Resultado.Incierto -> _ui.update { it.copy(cargando = false, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR)) }
            Resultado.SesionExpirada -> sesionExpirada()
        }
    }

    // ---------------- Navegación ----------------

    /** Botón "Tele trabajo" (u otro tipo): equivale a fijar la variable `type` de Power Apps. */
    fun elegirTipo(tipo: String) {
        _ui.update { it.copy(tipo = tipo, jornada = null, pantalla = Pantalla.MENU, mensaje = null) }
        refrescar()
        // Como Power Apps: Patch(UBICACION_APP; {UBICACION: lat & " " & lon; NOMBRE}). En segundo plano,
        // sin bloquear la navegación; si no hay permiso/GPS se registra sin coordenadas (igual que Power Apps).
        viewModelScope.launch {
            val u = if (proveedorUbicacion.tienePermiso()) proveedorUbicacion.obtener() as? ResultadoUbicacion.Ok else null
            repo.registrarAcceso(tipo, u?.lat, u?.lon)
        }
    }

    /** Reintenta la verificación del dispositivo (p. ej. después de que TI lo habilitó). */
    fun reintentarVerificacion() = cargarPerfil()

    /** Botones "Ingreso" / "salida" de la pantalla de bienvenida. */
    fun abrirRegistro(accion: Accion) {
        _ui.update { it.copy(accion = accion, pantalla = Pantalla.REGISTRO, mensaje = null, ubicacion = null, gpsApagado = false) }
    }

    /** Flecha atrás. Devuelve false si ya está en la primera pantalla. */
    fun volver(): Boolean {
        val actual = _ui.value
        if (actual.procesando) return true
        return when (actual.pantalla) {
            Pantalla.REGISTRO -> {
                trabajoUbicacion?.cancel()
                _ui.update { it.copy(pantalla = Pantalla.MENU, mensaje = null, buscandoUbicacion = false) }
                refrescar(); true
            }
            Pantalla.MENU -> { _ui.update { it.copy(pantalla = Pantalla.TIPOS, mensaje = null) }; true }
            Pantalla.TIPOS -> false
        }
    }

    fun refrescar() {
        val tipo = _ui.value.tipo ?: return
        viewModelScope.launch {
            _ui.update { it.copy(cargando = true) }
            when (val r = repo.estado(tipo)) {
                is Resultado.Ok -> _ui.update { it.copy(cargando = false, jornada = r.valor) }
                is Resultado.Rechazo -> _ui.update { it.copy(cargando = false, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR)) }
                is Resultado.Incierto -> _ui.update { it.copy(cargando = false, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR)) }
                Resultado.SesionExpirada -> sesionExpirada()
            }
        }
    }

    // ---------------- Ubicación y timbrado ----------------

    /** Muestra lon/lat en la pantalla de registro (como Power Apps). Llamar con permiso concedido. */
    fun actualizarUbicacion() {
        if (_ui.value.buscandoUbicacion || _ui.value.procesando) return
        trabajoUbicacion = viewModelScope.launch {
            _ui.update { it.copy(buscandoUbicacion = true, gpsApagado = false) }
            val u = proveedorUbicacion.obtener()
            _ui.update { it.copy(buscandoUbicacion = false) }
            when (u) {
                is ResultadoUbicacion.Ok -> _ui.update { it.copy(ubicacion = UbicacionVista(u.lat, u.lon, u.precisionMetros)) }
                ResultadoUbicacion.SinPermiso -> fallo("Debe permitir el acceso a la ubicación para timbrar.")
                ResultadoUbicacion.GpsApagado -> {
                    _ui.update { it.copy(gpsApagado = true) }
                    fallo("El GPS está apagado. Actívelo en Ajustes > Ubicación.")
                }
                is ResultadoUbicacion.Error -> fallo(u.mensaje)
            }
        }
    }

    /** Botón "Timbrar". Siempre toma una ubicación FRESCA en el momento de timbrar. */
    fun timbrar() {
        val tipo = _ui.value.tipo ?: return
        val accion = _ui.value.accion
        if (_ui.value.procesando) return // evita doble toque
        trabajoUbicacion?.cancel()
        viewModelScope.launch {
            _ui.update { it.copy(procesando = true, buscandoUbicacion = false, mensaje = null, gpsApagado = false) }

            val ub = when (val u = proveedorUbicacion.obtener()) {
                is ResultadoUbicacion.Ok -> u
                ResultadoUbicacion.SinPermiso -> return@launch fallo("Debe permitir el acceso a la ubicación para timbrar.")
                ResultadoUbicacion.GpsApagado -> {
                    _ui.update { it.copy(gpsApagado = true) }
                    return@launch fallo("El GPS está apagado. Actívelo en Ajustes > Ubicación e intente nuevamente.")
                }
                is ResultadoUbicacion.Error -> return@launch fallo(u.mensaje)
            }
            _ui.update { it.copy(ubicacion = UbicacionVista(ub.lat, ub.lon, ub.precisionMetros)) }

            val r = repo.marcar(accion, MarcacionRequest(tipo, ub.lat, ub.lon, ub.precisionMetros))
            val sinConfirmar = repo.marcacionPendiente() != null
            when (r) {
                // Éxito: como Power Apps (Notify + Back) -> vuelve a la bienvenida con el mensaje.
                is Resultado.Ok -> _ui.update {
                    it.copy(
                        procesando = false,
                        pantalla = Pantalla.MENU,
                        jornada = r.valor.estado,
                        marcacionSinConfirmar = sinConfirmar,
                        mensaje = Mensaje(r.valor.mensaje, if (r.valor.ubicacionPendiente) TipoMensaje.AVISO else TipoMensaje.EXITO),
                    )
                }
                // Rechazo ("Su ingreso ya fue registrado", etc.): se queda en la pantalla con el error, como Power Apps.
                is Resultado.Rechazo -> _ui.update {
                    it.copy(procesando = false, marcacionSinConfirmar = sinConfirmar, mensaje = Mensaje(r.mensaje, TipoMensaje.ERROR))
                }
                is Resultado.Incierto -> _ui.update {
                    it.copy(
                        procesando = false,
                        marcacionSinConfirmar = true,
                        mensaje = Mensaje("${r.mensaje}\nSu timbrada NO está confirmada. Presione Timbrar nuevamente (no se duplicará).", TipoMensaje.ERROR),
                    )
                }
                Resultado.SesionExpirada -> sesionExpirada()
            }
        }
    }

    fun permisoUbicacionDenegado() = fallo("Sin permiso de ubicación no es posible timbrar.")

    private fun fallo(texto: String) {
        _ui.update { it.copy(procesando = false, mensaje = Mensaje(texto, TipoMensaje.ERROR)) }
    }

    fun descartarMensaje() = _ui.update { it.copy(mensaje = null) }
}
