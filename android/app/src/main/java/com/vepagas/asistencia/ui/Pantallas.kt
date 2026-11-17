package com.vepagas.asistencia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vepagas.asistencia.BuildConfig
import com.vepagas.asistencia.R
import com.vepagas.asistencia.datos.Accion
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ZONA_ECUADOR: ZoneId = ZoneId.of("America/Guayaquil")
private val FORMATO_FECHA = DateTimeFormatter.ofPattern("d MMMM yyyy H:mm", Locale.forLanguageTag("es-EC"))
private val PERMISOS_UBICACION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

// =====================================================================
// Componentes comunes (estilo de la app Power Apps)
// =====================================================================

/** Marco: franja amarilla arriba/abajo, contenido centrado y pie "by ICT - Vepagas". */
@Composable
private fun Marco(
    titulo: String? = null,
    onVolver: (() -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        if (titulo != null) {
            Box(Modifier.fillMaxWidth().background(AmarilloVepagas).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(titulo, color = Color.White, fontSize = 20.sp)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(8.dp).background(AmarilloVepagas))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onVolver != null) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Image(
                        painterResource(R.drawable.ic_atras),
                        contentDescription = "Atrás",
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onVolver),
                    )
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
            contenido()
        }

        Text(
            "by ICT - Vepagas",
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            color = GrisTexto,
        )
        Box(Modifier.fillMaxWidth().height(8.dp).background(AmarilloVepagas))
    }
}

@Composable
private fun Logo(ancho: Dp) {
    Image(
        painterResource(R.drawable.logo_vepagas),
        contentDescription = "VEPAGAS",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.width(ancho),
    )
}

@Composable
private fun BotonAmarillo(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, habilitado: Boolean = true, contenido: (@Composable () -> Unit)? = null) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AmarilloVepagas, contentColor = Color.White,
            disabledContainerColor = AmarilloVepagas.copy(alpha = 0.5f), disabledContentColor = Color.White,
        ),
        modifier = modifier,
    ) {
        if (contenido != null) contenido() else Text(texto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TarjetaMensaje(m: Mensaje, onDescartar: (() -> Unit)?) {
    val (fondo, texto) = when (m.tipo) {
        TipoMensaje.EXITO -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        TipoMensaje.ERROR -> Color(0xFFFFEBEE) to Color(0xFFB71C1C)
        TipoMensaje.AVISO -> Color(0xFFFFF8E1) to Color(0xFF6D4C00)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).background(fondo, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.texto, color = texto, modifier = Modifier.weight(1f))
        if (onDescartar != null) TextButton(onClick = onDescartar) { Text("OK", color = texto) }
    }
}

private fun tienePermisoUbicacion(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

// =====================================================================
// Login (nuevo: en Power Apps la identidad venía de Microsoft 365)
// =====================================================================

@Composable
fun PantallaLogin(ui: UiEstado, onGoogle: () -> Unit, onDesarrollo: (String) -> Unit, onDescartarMensaje: () -> Unit) {
    var correoDev by rememberSaveable { mutableStateOf("") }

    Marco {
        Spacer(Modifier.height(48.dp))
        Logo(280.dp)
        Spacer(Modifier.height(8.dp))
        Text("Nos empeñamos en la\nintegridad de nuestra gente", textAlign = TextAlign.Center, fontStyle = FontStyle.Italic, color = GrisTexto)
        Spacer(Modifier.height(56.dp))

        BotonAmarillo("Iniciar sesión con Google", onGoogle, Modifier.fillMaxWidth().height(52.dp), habilitado = !ui.cargando)
        if (ui.cargando) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator(color = AmarilloVepagas) }
        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }

        Spacer(Modifier.height(16.dp))
        Text(
            "Solo cuentas autorizadas por Talento Humano. La ubicación se registra únicamente al timbrar.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = GrisTexto,
        )

        if (BuildConfig.PERMITIR_LOGIN_DEV) {
            Spacer(Modifier.height(28.dp))
            Text("— Solo pruebas (build debug) —", style = MaterialTheme.typography.labelSmall, color = GrisTexto)
            OutlinedTextField(
                value = correoDev, onValueChange = { correoDev = it },
                label = { Text("Correo de empleado de prueba") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onDesarrollo(correoDev) },
                enabled = !ui.cargando && correoDev.contains('@'),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ingreso de prueba (sin Google)") }
        }
    }
}

// =====================================================================
// Pantalla 1: tipo de trabajo ("Tele trabajo")
// =====================================================================

@Composable
fun PantallaTipos(
    ui: UiEstado,
    onElegir: (String) -> Unit,
    onReintentar: () -> Unit,
    onCerrarSesion: () -> Unit,
    onDescartarMensaje: () -> Unit,
) {
    val context = LocalContext.current
    var tipoPendiente by remember { mutableStateOf<String?>(null) }
    // Se pide la ubicación antes de continuar, porque "Tele trabajo" registra UBICACION_APP (como Power Apps).
    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tipoPendiente?.let(onElegir)
        tipoPendiente = null
    }

    Marco {
        Spacer(Modifier.height(72.dp))
        Logo(290.dp)
        Spacer(Modifier.height(80.dp))

        ui.bloqueado?.let { texto ->
            TarjetaMensaje(Mensaje(texto, TipoMensaje.ERROR), null)
            OutlinedButton(onClick = onReintentar, enabled = !ui.cargando) { Text("Reintentar") }
        }

        if (ui.cargando && ui.tipos.isEmpty()) CircularProgressIndicator(color = AmarilloVepagas)
        ui.tipos.forEach { tipo ->
            BotonAmarillo(
                texto = tipo.etiqueta,
                onClick = {
                    if (tienePermisoUbicacion(context)) onElegir(tipo.valor)
                    else { tipoPendiente = tipo.valor; pedirPermiso.launch(PERMISOS_UBICACION) }
                },
                modifier = Modifier.size(width = 130.dp, height = 110.dp).padding(bottom = 8.dp),
                contenido = { Text(tipo.etiqueta, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) },
            )
            Spacer(Modifier.height(12.dp))
        }
        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }

        Spacer(Modifier.height(40.dp))
        TextButton(onClick = onCerrarSesion) { Text("Cerrar sesión (${ui.sesion?.correo.orEmpty()})", color = GrisTexto, fontSize = 12.sp) }
    }
}

// =====================================================================
// Pantalla 2: ¡Bienvenido! — Ingreso / salida
// =====================================================================

@Composable
fun PantallaBienvenida(ui: UiEstado, onIngreso: () -> Unit, onSalida: () -> Unit, onVolver: () -> Unit, onDescartarMensaje: () -> Unit) {
    Marco(onVolver = onVolver) {
        Logo(210.dp)
        Text("Nos empeñamos en la\nintegridad de nuestra gente", textAlign = TextAlign.Center, fontStyle = FontStyle.Italic, color = GrisTexto, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        // Avatar (iniciales) en lugar de la foto de perfil de Microsoft 365.
        val iniciales = ui.sesion?.nombre.orEmpty().split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        Box(Modifier.size(108.dp).background(Color(0xFFA9A9A9)), contentAlignment = Alignment.Center) {
            Text(iniciales.ifEmpty { "?" }, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))
        Text("¡ Bienvenido !", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(ui.sesion?.nombre.orEmpty().uppercase(), fontSize = 16.sp)
        Text(ui.etiquetaTipo, fontSize = 12.sp, color = GrisTexto)

        // Estado de la jornada (dato nuevo, no estaba en Power Apps).
        ui.jornada?.let { j ->
            val texto = when (j.estado) {
                "SIN_INGRESO" -> "Hoy: sin ingreso registrado"
                "EN_JORNADA" -> "Hoy: ingreso ${j.horaIngreso}"
                "JORNADA_CERRADA" -> "Hoy: ingreso ${j.horaIngreso} · salida ${j.horaSalida}"
                else -> j.estado
            }
            Spacer(Modifier.height(6.dp))
            Text(texto, fontSize = 13.sp, color = if (j.estado == "SIN_INGRESO") GrisTexto else VerdeExito)
            if (j.ubicacionPendiente) Text("Ubicación pendiente de sincronizar", fontSize = 12.sp, color = AmbarAviso)
        } ?: if (ui.cargando) CircularProgressIndicator(Modifier.padding(top = 6.dp).size(18.dp), color = AmarilloVepagas, strokeWidth = 2.dp) else Unit

        Spacer(Modifier.height(20.dp))
        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }
        if (ui.marcacionSinConfirmar)
            TarjetaMensaje(Mensaje("Hay una timbrada sin confirmar. Vuelva a timbrar la misma opción; no se duplicará.", TipoMensaje.AVISO), null)

        BotonAmarillo("Ingreso", onIngreso, Modifier.width(170.dp).height(44.dp))
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onSalida,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, AmarilloVepagas),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AmarilloVepagas),
            modifier = Modifier.width(170.dp).height(44.dp),
        ) { Text("salida", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(16.dp))
    }
}

// =====================================================================
// Pantalla 3: Registro de Ingreso / Registro de Salida y Actividades — Timbrar
// =====================================================================

@Composable
fun PantallaRegistro(
    ui: UiEstado,
    onVolver: () -> Unit,
    onActualizarUbicacion: () -> Unit,
    onTimbrar: () -> Unit,
    onPermisoDenegado: () -> Unit,
    onDescartarMensaje: () -> Unit,
) {
    val context = LocalContext.current
    var timbrarTrasPermiso by remember { mutableStateOf(false) }

    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (timbrarTrasPermiso) onTimbrar() else onActualizarUbicacion()
        } else onPermisoDenegado()
        timbrarTrasPermiso = false
    }

    // Al abrir la pantalla: pedir permiso (solo "mientras se usa la app") y mostrar lon/lat.
    LaunchedEffect(Unit) {
        if (tienePermisoUbicacion(context)) onActualizarUbicacion() else pedirPermiso.launch(PERMISOS_UBICACION)
    }

    var ahora by remember { mutableStateOf(LocalDateTime.now(ZONA_ECUADOR)) }
    LaunchedEffect(Unit) { while (true) { ahora = LocalDateTime.now(ZONA_ECUADOR); delay(1_000) } }

    val titulo = if (ui.accion == Accion.INGRESO) "Registro de Ingreso" else "Registro de Salida y Actividades"

    Marco(titulo = titulo, onVolver = onVolver) {
        Logo(120.dp)
        Spacer(Modifier.height(36.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Dato("Usuario:") { Text(ui.sesion?.nombre.orEmpty().uppercase(), fontStyle = FontStyle.Italic, color = GrisTexto) }
            Dato("Fecha:") { Text(ahora.format(FORMATO_FECHA), fontStyle = FontStyle.Italic, color = GrisTexto) }
            Dato("Ubicación:") {
                val u = ui.ubicacion
                Column {
                    Row {
                        Text("lon: ${u?.lon?.let { "%.6f".format(Locale.US, it) } ?: ""}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("lat: ${u?.lat?.let { "%.6f".format(Locale.US, it) } ?: ""}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    }
                    when {
                        ui.buscandoUbicacion -> Text("Obteniendo ubicación…", fontSize = 11.sp, color = GrisTexto)
                        u != null -> Text("Precisión ±${u.precisionMetros.toInt()} m", fontSize = 11.sp, color = GrisTexto)
                    }
                }
            }
        }

        Spacer(Modifier.height(36.dp))
        Image(
            painterResource(R.drawable.ic_mapa),
            contentDescription = "Actualizar ubicación",
            modifier = Modifier.size(80.dp).clickable(enabled = !ui.procesando) {
                if (tienePermisoUbicacion(context)) onActualizarUbicacion() else pedirPermiso.launch(PERMISOS_UBICACION)
            },
        )
        Spacer(Modifier.height(24.dp))

        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }
        if (ui.gpsApagado) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text("Abrir ajustes de ubicación")
            }
        }
        if (!tienePermisoUbicacion(context) && ui.mensaje != null) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Abrir permisos de la app") }
        }

        BotonAmarillo(
            texto = "Timbrar",
            onClick = {
                if (tienePermisoUbicacion(context)) onTimbrar()
                else { timbrarTrasPermiso = true; pedirPermiso.launch(PERMISOS_UBICACION) }
            },
            habilitado = !ui.procesando,
            modifier = Modifier.width(160.dp).height(46.dp),
            contenido = if (ui.procesando) {
                { CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 3.dp) }
            } else null,
        )
        if (ui.procesando) Text("Registrando…", fontSize = 12.sp, color = GrisTexto, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Dato(etiqueta: String, valor: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(etiqueta, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
        Box(Modifier.weight(1f)) { valor() }
    }
}
