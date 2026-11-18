package com.vepagas.asistencia.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vepagas.asistencia.R
import com.vepagas.asistencia.datos.Accion
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ZONA_ECUADOR: ZoneId = ZoneId.of("America/Guayaquil")
private val ES_EC: Locale = Locale.forLanguageTag("es-EC")
private val FORMATO_DIA = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", ES_EC)
private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")
private val FORMATO_SEGUNDOS = DateTimeFormatter.ofPattern("ss")
private val PERMISOS_UBICACION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

// =====================================================================
// Componentes comunes
// =====================================================================

/** Íconos de la barra de estado: claros sobre cabecera oscura, oscuros sobre fondo blanco. */
@Composable
private fun IconosBarraEstado(oscuros: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val ventana = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(ventana, view).isAppearanceLightStatusBars = oscuros
    }
}

/** Hora de Ecuador que se actualiza cada segundo. */
@Composable
private fun relojEcuador(): LocalDateTime {
    var ahora by remember { mutableStateOf(LocalDateTime.now(ZONA_ECUADOR)) }
    LaunchedEffect(Unit) { while (true) { ahora = LocalDateTime.now(ZONA_ECUADOR); delay(1_000) } }
    return ahora
}

private fun LocalDateTime.diaLegible() = format(FORMATO_DIA).replaceFirstChar { it.titlecase(ES_EC) }

private fun saludo(hora: Int) = when (hora) {
    in 5..11 -> "Buenos días"
    in 12..18 -> "Buenas tardes"
    else -> "Buenas noches"
}

private fun primerNombre(nombre: String?) =
    nombre.orEmpty().trim().split(' ').firstOrNull { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.titlecase(ES_EC) }.orEmpty()

private fun iniciales(nombre: String?) =
    nombre.orEmpty().split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Pantalla base: fondo gris muy claro, cabecera oscura con esquinas redondeadas y contenido desplazable. */
@Composable
private fun Marco(
    onVolver: (() -> Unit)? = null,
    onCerrarSesion: (() -> Unit)? = null,
    cabecera: @Composable ColumnScope.() -> Unit,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    var confirmarSalida by remember { mutableStateOf(false) }
    IconosBarraEstado(oscuros = false)

    Column(Modifier.fillMaxSize().background(FondoApp)) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(NegroVepagas, Grafito)))
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onVolver != null) IconoCabecera(R.drawable.ic_atras, "Atrás", onVolver)
                Spacer(Modifier.weight(1f))
                if (onCerrarSesion != null) IconoCabecera(R.drawable.ic_salir, "Cerrar sesión") { confirmarSalida = true }
            }
            cabecera()
            Spacer(Modifier.height(14.dp))
            // Acento rojo: firma visual de la marca.
            Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(RojoVepagas))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            contenido()
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "by ICT · Vepagas",
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
            textAlign = TextAlign.Center, fontSize = 11.sp, letterSpacing = 1.sp, color = GrisTexto,
        )
    }

    if (confirmarSalida && onCerrarSesion != null) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = false },
            title = { Text("Cerrar sesión") },
            text = { Text("¿Desea cerrar su sesión en este teléfono?") },
            confirmButton = {
                Button(
                    onClick = { confirmarSalida = false; onCerrarSesion() },
                    colors = ButtonDefaults.buttonColors(containerColor = RojoVepagas, contentColor = Color.White),
                ) { Text("Cerrar sesión") }
            },
            dismissButton = { TextButton(onClick = { confirmarSalida = false }) { Text("Cancelar", color = NegroVepagas) } },
            containerColor = Color.White,
        )
    }
}

@Composable
private fun IconoCabecera(icono: Int, descripcion: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(icono), descripcion, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
private fun Logo(ancho: Dp) {
    Image(painterResource(R.drawable.logo_vepagas), "VEPAGAS", contentScale = ContentScale.FillWidth, modifier = Modifier.width(ancho))
}

/** Escala suave al presionar: da respuesta táctil sin recargar el diseño. */
@Composable
private fun Modifier.presionable(fuente: MutableInteractionSource): Modifier {
    val presionado by fuente.collectIsPressedAsState()
    val escala by animateFloatAsState(if (presionado) 0.97f else 1f, tween(120), label = "escala")
    return graphicsLayer { scaleX = escala; scaleY = escala }
}

@Composable
private fun Tarjeta(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, contenido: @Composable ColumnScope.() -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.presionable(fuente) else Modifier),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, GrisLinea),
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.then(if (onClick != null) Modifier.clickable(interactionSource = fuente, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier)
                .padding(18.dp),
            content = contenido,
        )
    }
}

/** Botón principal con degradado rojo y animación al presionar. */
@Composable
private fun BotonPrincipal(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    cargando: Boolean = false,
    oscuro: Boolean = false,
) {
    val fuente = remember { MutableInteractionSource() }
    val colores = if (oscuro) listOf(Grafito, NegroVepagas) else listOf(RojoVepagas, RojoOscuro)
    Box(
        modifier.fillMaxWidth().height(56.dp).presionable(fuente)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(colores), alpha = if (habilitado) 1f else 0.5f)
            .clickable(enabled = habilitado && !cargando, interactionSource = fuente, indication = androidx.compose.material3.ripple(color = Color.White), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (cargando) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Text(texto, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun TarjetaMensaje(m: Mensaje, onDescartar: (() -> Unit)?) {
    val (fondo, texto) = when (m.tipo) {
        TipoMensaje.EXITO -> Color(0xFFEAF5EB) to Color(0xFF1B5E20)
        TipoMensaje.ERROR -> Color(0xFFFCECEF) to Color(0xFF9A0F30)
        TipoMensaje.AVISO -> Color(0xFFFFF6E0) to Color(0xFF6D4C00)
    }
    AnimatedVisibility(true, enter = fadeIn() + slideInVertically { -it / 3 }) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(fondo)
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(texto))
            Spacer(Modifier.width(10.dp))
            Text(m.texto, color = texto, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (onDescartar != null) TextButton(onClick = onDescartar) { Text("OK", color = texto, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Pin de ubicación dibujado (sin librería de íconos). */
@Composable
private fun IconoPin(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val r = w * 0.38f
        val c = Offset(w / 2, r + h * 0.04f)
        val punta = Path().apply {
            moveTo(c.x - r * 0.82f, c.y + r * 0.55f); lineTo(w / 2, h * 0.98f); lineTo(c.x + r * 0.82f, c.y + r * 0.55f); close()
        }
        drawPath(punta, color)
        drawCircle(color, r, c)
        drawCircle(Color.White, r * 0.42f, c)
    }
}

@Composable
private fun Chip(texto: String, color: Color) {
    Text(
        texto, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Etiqueta(texto: String) =
    Text(texto.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, color = GrisTexto, fontWeight = FontWeight.Medium)

private fun tienePermisoUbicacion(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Abre Google Maps (app o web) en la posición indicada. */
private fun abrirEnMapa(context: Context, lat: Double, lon: Double) {
    val punto = "%.7f,%.7f".format(Locale.US, lat, lon)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$punto"))
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
}

// =====================================================================
// Login
// =====================================================================

@Composable
fun PantallaLogin(ui: UiEstado, onGoogle: () -> Unit, onDescartarMensaje: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    IconosBarraEstado(oscuros = true)

    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(visible, enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 6 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Logo(250.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("Nos empeñamos en la integridad de nuestra gente", textAlign = TextAlign.Center, color = GrisTexto, fontSize = 13.sp)
                }
            }
        }

        AnimatedVisibility(visible, enter = fadeIn(tween(700, delayMillis = 200)) + slideInVertically(tween(700, delayMillis = 200)) { it / 2 }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(Brush.verticalGradient(listOf(Grafito, NegroVepagas)))
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
            ) {
                Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(RojoVepagas))
                Spacer(Modifier.height(14.dp))
                Text("Control de asistencia", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Ingrese con su cuenta @vepagas.com o con su Gmail autorizado.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))

                ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje); Spacer(Modifier.height(8.dp)) }
                BotonPrincipal("Iniciar sesión", onGoogle, habilitado = !ui.cargando, cargando = ui.cargando)

                Spacer(Modifier.height(18.dp))
                Text(
                    "Solo cuentas autorizadas por Talento Humano. La ubicación se registra únicamente al timbrar.",
                    fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// =====================================================================
// Pantalla 1: inicio ("Registro de asistencia")
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
    // Se pide la ubicación antes de continuar, porque este botón registra UBICACION_APP (como Power Apps).
    val pedirPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tipoPendiente?.let(onElegir)
        tipoPendiente = null
    }
    val ahora = relojEcuador()

    Marco(
        onCerrarSesion = onCerrarSesion,
        cabecera = {
            Spacer(Modifier.height(8.dp))
            Text(saludo(ahora.hour) + ",", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            Text(primerNombre(ui.sesion?.nombre).ifEmpty { "Bienvenido" }, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ahora.format(FORMATO_HORA), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.width(12.dp))
                Text(ahora.diaLegible(), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        },
    ) {
        ui.bloqueado?.let { texto ->
            TarjetaMensaje(Mensaje(texto, TipoMensaje.ERROR), null)
            TextButton(onClick = onReintentar, enabled = !ui.cargando) { Text("Reintentar", color = RojoVepagas, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
        }

        if (ui.cargando && ui.tipos.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = RojoVepagas)
            Text("Verificando su dispositivo…", color = GrisTexto, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }

        ui.tipos.forEachIndexed { i, tipo ->
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(80L * i); visible = true }
            AnimatedVisibility(visible, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }) {
                Tarjeta(
                    modifier = Modifier.padding(bottom = 12.dp),
                    onClick = {
                        if (tienePermisoUbicacion(context)) onElegir(tipo.valor)
                        else { tipoPendiente = tipo.valor; pedirPermiso.launch(PERMISOS_UBICACION) }
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(RojoVepagas, RojoOscuro))),
                            contentAlignment = Alignment.Center,
                        ) { IconoPin(Color.White, Modifier.size(24.dp)) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tipo.etiqueta, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NegroVepagas)
                            Text("Marque su ingreso y salida de hoy", fontSize = 13.sp, color = GrisTexto)
                        }
                        Text("›", fontSize = 30.sp, color = GrisTexto, fontWeight = FontWeight.Light)
                    }
                }
            }
        }

        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 10.dp)) { Logo(100.dp) }
        Text(ui.sesion?.correo.orEmpty(), fontSize = 12.sp, color = GrisTexto, modifier = Modifier.padding(top = 6.dp))
    }
}

// =====================================================================
// Pantalla 2: jornada de hoy — Ingreso / Salida
// =====================================================================

@Composable
fun PantallaBienvenida(ui: UiEstado, onIngreso: () -> Unit, onSalida: () -> Unit, onVolver: () -> Unit, onCerrarSesion: () -> Unit, onDescartarMensaje: () -> Unit) {
    val ahora = relojEcuador()
    Marco(
        onVolver = onVolver,
        onCerrarSesion = onCerrarSesion,
        cabecera = {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).border(2.dp, RojoVepagas, CircleShape).padding(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Text(iniciales(ui.sesion?.nombre), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("¡Bienvenido!", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    Text(ui.sesion?.nombre.orEmpty(), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("${ui.etiquetaTipo} · ${ahora.diaLegible()}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        },
    ) {
        val j = ui.jornada
        val estado = j?.estado

        Tarjeta {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Etiqueta("Jornada de hoy")
                Spacer(Modifier.weight(1f))
                when (estado) {
                    "SIN_INGRESO" -> Chip("Pendiente", GrisOscuro)
                    "EN_JORNADA" -> Chip("En jornada", RojoVepagas)
                    "JORNADA_CERRADA" -> Chip("Completa", VerdeExito)
                    null -> if (ui.cargando) CircularProgressIndicator(Modifier.size(16.dp), color = RojoVepagas, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(18.dp))
            PasoJornada("Ingreso", j?.horaIngreso, hecho = estado == "EN_JORNADA" || estado == "JORNADA_CERRADA", activo = estado == "SIN_INGRESO", conLinea = true)
            PasoJornada("Salida", j?.horaSalida, hecho = estado == "JORNADA_CERRADA", activo = estado == "EN_JORNADA", conLinea = false)
            if (j?.ubicacionPendiente == true) {
                Spacer(Modifier.height(10.dp))
                Text("Ubicación pendiente de sincronizar", fontSize = 12.sp, color = AmbarAviso)
            }
        }

        Spacer(Modifier.height(16.dp))
        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }
        if (ui.marcacionSinConfirmar)
            TarjetaMensaje(Mensaje("Hay una timbrada sin confirmar. Vuelva a timbrar la misma opción; no se duplicará.", TipoMensaje.AVISO), null)

        // Solo la acción que corresponde. Si el estado no se pudo leer (o hay una timbrada sin confirmar)
        // se muestran ambas: las reglas igual impiden duplicar.
        val dudoso = ui.marcacionSinConfirmar || (estado == null && !ui.cargando)
        val accion = when {
            dudoso -> "AMBAS"
            estado == "SIN_INGRESO" -> "INGRESO"
            estado == "EN_JORNADA" -> "SALIDA"
            estado == "JORNADA_CERRADA" -> "COMPLETA"
            else -> "CARGANDO"
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(accion, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "accion") { a ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (a) {
                    "INGRESO" -> BotonPrincipal("Registrar ingreso", onIngreso)
                    "SALIDA" -> BotonPrincipal("Registrar salida", onSalida, oscuro = true)
                    "AMBAS" -> {
                        BotonPrincipal("Registrar ingreso", onIngreso)
                        Spacer(Modifier.height(12.dp))
                        BotonPrincipal("Registrar salida", onSalida, oscuro = true)
                    }
                    "COMPLETA" -> Tarjeta {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(VerdeExito.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Text("✓", color = VerdeExito, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Jornada de hoy completa", fontWeight = FontWeight.SemiBold, color = NegroVepagas)
                                Text("Gracias por su trabajo. ¡Hasta mañana!", fontSize = 13.sp, color = GrisTexto)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Un paso de la línea de tiempo de la jornada (Ingreso → Salida). */
@Composable
private fun PasoJornada(titulo: String, hora: String?, hecho: Boolean, activo: Boolean, conLinea: Boolean) {
    val pulso = rememberInfiniteTransition(label = "pulso")
    val halo by pulso.animateFloat(0.15f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "halo")
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(
                    when { hecho -> RojoVepagas; activo -> RojoVepagas.copy(alpha = halo); else -> GrisClaro }
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (hecho) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                else Box(Modifier.size(8.dp).clip(CircleShape).background(if (activo) RojoVepagas else Color.White))
            }
            if (conLinea) Box(Modifier.width(2.dp).height(30.dp).background(if (hecho) RojoVepagas else GrisClaro))
        }
        Spacer(Modifier.width(14.dp))
        Row(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(titulo, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (hecho || activo) NegroVepagas else GrisTexto, modifier = Modifier.weight(1f))
            Text(
                if (hecho) hora.orEmpty() else if (activo) "Pendiente" else "—",
                fontSize = 15.sp, fontWeight = if (hecho) FontWeight.SemiBold else FontWeight.Normal,
                color = if (hecho) NegroVepagas else GrisTexto,
            )
        }
    }
}

// =====================================================================
// Pantalla 3: Registro de Ingreso / Salida — Timbrar
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

    // Al abrir la pantalla: pedir permiso (solo "mientras se usa la app") y obtener la ubicación.
    LaunchedEffect(Unit) {
        if (tienePermisoUbicacion(context)) onActualizarUbicacion() else pedirPermiso.launch(PERMISOS_UBICACION)
    }
    val actualizar = { if (tienePermisoUbicacion(context)) onActualizarUbicacion() else pedirPermiso.launch(PERMISOS_UBICACION) }

    val ahora = relojEcuador()
    val esIngreso = ui.accion == Accion.INGRESO

    Marco(
        onVolver = onVolver,
        cabecera = {
            Text(if (esIngreso) "Registro de ingreso" else "Registro de salida", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ahora.format(FORMATO_HORA), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light)
                Text(":" + ahora.format(FORMATO_SEGUNDOS), color = RojoVepagas, fontSize = 24.sp, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
            }
            Text(ahora.diaLegible(), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        },
    ) {
        Tarjeta {
            Etiqueta("Colaborador")
            Spacer(Modifier.height(4.dp))
            Text(ui.sesion?.nombre.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = NegroVepagas)
            Text(ui.etiquetaTipo, fontSize = 13.sp, color = GrisTexto)
        }

        Spacer(Modifier.height(12.dp))
        TarjetaUbicacion(ui, onAbrirMapa = { u -> abrirEnMapa(context, u.lat, u.lon) }, onActualizar = actualizar)

        Spacer(Modifier.height(16.dp))
        ui.mensaje?.let { TarjetaMensaje(it, onDescartarMensaje) }
        if (ui.gpsApagado) {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text("Abrir ajustes de ubicación", color = RojoVepagas, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!tienePermisoUbicacion(context) && ui.mensaje != null) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Abrir permisos de la app", color = RojoVepagas, fontWeight = FontWeight.SemiBold) }
        }

        Spacer(Modifier.height(8.dp))
        BotonPrincipal(
            texto = if (esIngreso) "Timbrar ingreso" else "Timbrar salida",
            onClick = {
                if (tienePermisoUbicacion(context)) onTimbrar()
                else { timbrarTrasPermiso = true; pedirPermiso.launch(PERMISOS_UBICACION) }
            },
            habilitado = !ui.procesando,
            cargando = ui.procesando,
            oscuro = !esIngreso,
        )
        if (ui.procesando) Text("Registrando…", fontSize = 12.sp, color = GrisTexto, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Tarjeta de ubicación: mini "mapa" con pin animado. Al tocarla abre Google Maps en esa posición. */
@Composable
private fun TarjetaUbicacion(ui: UiEstado, onAbrirMapa: (UbicacionVista) -> Unit, onActualizar: () -> Unit) {
    val u = ui.ubicacion
    val pulso = rememberInfiniteTransition(label = "radar")
    val onda by pulso.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "onda")

    Tarjeta(onClick = if (u != null && !ui.buscandoUbicacion) ({ onAbrirMapa(u) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta("Su ubicación")
            Spacer(Modifier.weight(1f))
            Text(
                if (ui.buscandoUbicacion) "Buscando…" else "Actualizar",
                color = RojoVepagas, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !ui.buscandoUbicacion && !ui.procesando, onClick = onActualizar)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // Mini mapa abstracto: cuadrícula suave + ondas de radar alrededor del pin.
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1F1F3)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val paso = 28.dp.toPx()
                var x = 0f
                while (x < size.width) { drawLine(Color(0xFFE2E2E6), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx()); x += paso }
                var y = 0f
                while (y < size.height) { drawLine(Color(0xFFE2E2E6), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()); y += paso }
                // "Calles"
                drawLine(Color.White, Offset(0f, size.height * 0.62f), Offset(size.width, size.height * 0.38f), 10.dp.toPx())
                drawLine(Color.White, Offset(size.width * 0.3f, 0f), Offset(size.width * 0.42f, size.height), 7.dp.toPx())
                if (u != null || ui.buscandoUbicacion) {
                    val centro = Offset(size.width / 2, size.height / 2)
                    for (k in 0..1) {
                        val f = (onda + k * 0.5f) % 1f
                        drawCircle(RojoVepagas.copy(alpha = 0.35f * (1f - f)), radius = 12.dp.toPx() + f * 56.dp.toPx(), center = centro, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            IconoPin(if (u != null) RojoVepagas else GrisTexto, Modifier.size(34.dp).padding(bottom = 14.dp))
            if (u != null) {
                Text(
                    "Ver en Google Maps  ↗", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(NegroVepagas.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            u != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lat ${"%.6f".format(Locale.US, u.lat)}   Lon ${"%.6f".format(Locale.US, u.lon)}", fontSize = 13.sp, color = NegroVepagas)
                    Text("Toque el mapa para ver desde dónde está timbrando", fontSize = 11.sp, color = GrisTexto)
                }
                Chip("±${u.precisionMetros.toInt()} m", if (u.precisionMetros <= 50) VerdeExito else AmbarAviso)
            }
            ui.buscandoUbicacion -> Text("Obteniendo su ubicación…", fontSize = 13.sp, color = GrisTexto)
            else -> Text("Ubicación no disponible. Toque “Actualizar”.", fontSize = 13.sp, color = GrisTexto)
        }
    }
}
