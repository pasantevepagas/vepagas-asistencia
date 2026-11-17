import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun prop(nombre: String): String =
    providers.gradleProperty(nombre).orNull ?: error("Falta la propiedad Gradle '$nombre' (ver gradle.properties)")

// Firma release: datos en android/keystore.properties (NO versionado). Si no existe, el release sale sin firmar.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.vepagas.asistencia"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vepagas.asistencia"
        minSdk = 26
        targetSdk = 37
        // La CI (GitHub Actions) los pasa con -Pvepagas.versionCode / -Pvepagas.versionName en cada release.
        versionCode = providers.gradleProperty("vepagas.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("vepagas.versionName").orNull ?: "0.1.0-local"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${prop("vepagas.googleWebClientId")}\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_BASE_URL", "\"${prop("vepagas.apiUrlDebug")}\"")
            // Habilita el botón "Ingreso de prueba" (requiere backend en Development con PermitirLoginDesarrollo).
            buildConfigField("boolean", "PERMITIR_LOGIN_DEV", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${prop("vepagas.apiUrlRelease")}\"")
            buildConfigField("boolean", "PERMITIR_LOGIN_DEV", "false")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging) // solo se activa si BuildConfig.DEBUG
}
