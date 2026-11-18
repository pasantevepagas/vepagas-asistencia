package com.vepagas.asistencia.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta corporativa sobria: rojo VEPAGAS, negro, grises y blanco. */
val RojoVepagas = Color(0xFFAF1740)
val RojoOscuro = Color(0xFF7A0F2C)
val NegroVepagas = Color(0xFF1C1C1C)
val Grafito = Color(0xFF2C2C30)
val GrisOscuro = Color(0xFF4A4A4A)
val GrisTexto = Color(0xFF6B6B6B)
val GrisClaro = Color(0xFFE6E6E6)
val GrisLinea = Color(0xFFEDEDF0)
val FondoApp = Color(0xFFF4F4F6)
val VerdeExito = Color(0xFF2E7D32)
val AmbarAviso = Color(0xFF8A6D00)

private val Esquema = lightColorScheme(
    primary = RojoVepagas,
    onPrimary = Color.White,
    secondary = NegroVepagas,
    onSecondary = Color.White,
    background = FondoApp,
    surface = Color.White,
    onSurface = NegroVepagas,
    outline = GrisTexto,
)

@Composable
fun TemaVepagas(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, content = content)
}
