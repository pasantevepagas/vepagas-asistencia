package com.vepagas.asistencia.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vepagas.asistencia.datos.Accion

class MainActivity : ComponentActivity() {
    private val vm: AsistenciaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaVepagas {
                val ui by vm.ui.collectAsStateWithLifecycle()

                BackHandler(enabled = ui.sesion != null && ui.pantalla != Pantalla.TIPOS) { vm.volver() }
                AvisoActualizacion()

                when {
                    ui.iniciando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    ui.sesion == null -> PantallaLogin(
                        ui = ui,
                        onGoogle = { vm.loginGoogle(this) },
                        onDescartarMensaje = vm::descartarMensaje,
                    )
                    ui.pantalla == Pantalla.TIPOS -> PantallaTipos(
                        ui = ui,
                        onElegir = vm::elegirTipo,
                        onReintentar = vm::reintentarVerificacion,
                        onCerrarSesion = { vm.cerrarSesion(this) },
                        onDescartarMensaje = vm::descartarMensaje,
                    )
                    ui.pantalla == Pantalla.MENU -> PantallaBienvenida(
                        ui = ui,
                        onIngreso = { vm.abrirRegistro(Accion.INGRESO) },
                        onSalida = { vm.abrirRegistro(Accion.SALIDA) },
                        onVolver = { vm.volver() },
                        onCerrarSesion = { vm.cerrarSesion(this) },
                        onDescartarMensaje = vm::descartarMensaje,
                    )
                    else -> PantallaRegistro(
                        ui = ui,
                        onVolver = { vm.volver() },
                        onActualizarUbicacion = vm::actualizarUbicacion,
                        onTimbrar = vm::timbrar,
                        onPermisoDenegado = vm::permisoUbicacionDenegado,
                        onDescartarMensaje = vm::descartarMensaje,
                    )
                }
            }
        }
    }
}
