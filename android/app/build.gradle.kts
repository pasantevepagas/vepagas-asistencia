import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.firebase.appdistribution)
    alias(libs.plugins.google.gms.google.services)
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

        mapOf(
            "TENANT_ID" to "vepagas.tenantId",
            "MSAL_CLIENT_ID" to "vepagas.msalClientId",
            "SITE_ID" to "vepagas.siteId",
            "LISTA_REGISTROS" to "vepagas.listaRegistros",
            "LISTA_UBICACIONES" to "vepagas.listaUbicaciones",
            "LISTA_DISPOSITIVOS" to "vepagas.listaDispositivos",
            "LISTA_SESIONES" to "vepagas.listaSesiones",
            "LISTA_UBICACION_APP" to "vepagas.listaUbicacionApp",
            "RELEASES_REPO" to "vepagas.releasesRepo",
        ).forEach { (campo, propiedad) -> buildConfigField("String", campo, "\"${prop(propiedad)}\"") }
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
        // Redirect URI de MSAL: msauth://<applicationId>/<hash de firma> (registrado en Entra para cada variante).
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "FIRMA_HASH", "\"${prop("vepagas.firmaHashDebug")}\"")
            manifestPlaceholders["firmaHash"] = prop("vepagas.firmaHashDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "FIRMA_HASH", "\"${prop("vepagas.firmaHashRelease")}\"")
            manifestPlaceholders["firmaHash"] = prop("vepagas.firmaHashRelease")
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

    implementation(libs.msal)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
