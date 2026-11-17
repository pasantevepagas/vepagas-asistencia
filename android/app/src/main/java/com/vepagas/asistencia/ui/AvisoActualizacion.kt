package com.vepagas.asistencia.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vepagas.asistencia.BuildConfig
import com.vepagas.asistencia.datos.Actualizador
import com.vepagas.asistencia.datos.InfoVersion
import kotlinx.coroutines.launch
import java.io.File

/** Al volver a primer plano busca una versión nueva en el servidor y ofrece instalarla. */
@Composable
fun AvisoActualizacion() {
    val activity = LocalContext.current as Activity
    val actualizador = remember { Actualizador(activity.applicationContext) }
    val alcance = rememberCoroutineScope()
    val ciclo = LocalLifecycleOwner.current.lifecycle

    var info by remember { mutableStateOf<InfoVersion?>(null) }
    var descartada by remember { mutableStateOf(false) }
    var descargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!descargando) actualizador.buscar()?.let { info = it }
            // Si el usuario volvió de habilitar "instalar apps desconocidas", continuar con la instalación.
            apk?.let { if (actualizador.puedeInstalar()) actualizador.instalar(activity, it) }
        }
    }

    val nueva = info ?: return
    if (descartada && !nueva.obligatoria) return

    AlertDialog(
        onDismissRequest = { if (!nueva.obligatoria && !descargando) descartada = true },
        title = { Text("Nueva versión disponible") },
        text = {
            Column {
                Text("Versión ${nueva.versionName} (tiene ${BuildConfig.VERSION_NAME}).")
                if (nueva.notas.isNotBlank()) Text(nueva.notas)
                if (nueva.obligatoria) Text("Esta actualización es obligatoria para seguir timbrando.")
                if (descargando) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(color = RojoVepagas) }
                error?.let { Text(it, color = Color(0xFFB71C1C)) }
            }
        },
        confirmButton = {
            Button(
                enabled = !descargando,
                colors = ButtonDefaults.buttonColors(containerColor = RojoVepagas, contentColor = Color.White),
                onClick = {
                    alcance.launch {
                        descargando = true; error = null
                        runCatching { actualizador.descargar(nueva) }
                            .onSuccess { archivo ->
                                apk = archivo
                                if (actualizador.puedeInstalar()) actualizador.instalar(activity, archivo)
                                else actualizador.pedirPermisoInstalacion(activity)
                            }
                            .onFailure { error = it.message ?: "No se pudo descargar." }
                        descargando = false
                    }
                },
            ) { Text("Actualizar") }
        },
        dismissButton = {
            if (!nueva.obligatoria) TextButton(enabled = !descargando, onClick = { descartada = true }) { Text("Más tarde") }
        },
    )
}
