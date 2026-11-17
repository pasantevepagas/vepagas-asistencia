package com.vepagas.asistencia.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colores tomados de la app Power Apps original. */
val AmarilloVepagas = Color(0xFFF2C53D)
val RojoVepagas = Color(0xFFAF1740)
val GrisTexto = Color(0xFF6B6B6B)
val VerdeExito = Color(0xFF2E7D32)
val AmbarAviso = Color(0xFFB28704)

private val Esquema = lightColorScheme(
    primary = AmarilloVepagas,
    onPrimary = Color.White,
    secondary = RojoVepagas,
    onSecondary = Color.White,
    background = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
)

/** Tema claro fijo, igual que la app original (no se adapta a modo oscuro). */
@Composable
fun TemaVepagas(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, content = content)
}
