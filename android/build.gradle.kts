plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 trae Kotlin integrado; declararlo aquí (sin aplicar) fija la versión de Kotlin del build.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
