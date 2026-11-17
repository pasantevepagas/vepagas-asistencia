package com.vepagas.asistencia.datos

import com.vepagas.asistencia.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ---------------- DTOs (espejo de Contratos/Dtos.cs del backend) ----------------

@Serializable data class GoogleLoginRequest(val idToken: String)
@Serializable data class LoginDesarrolloRequest(val correo: String)
@Serializable data class EmpleadoDto(val nombre: String, val correo: String)
@Serializable data class SesionResponse(val token: String, val expiraUtc: String, val empleado: EmpleadoDto)
/** valor = lo que se guarda en TIPO_TRABAJO ("Home_Office"); etiqueta = texto del botón ("Tele trabajo"). */
@Serializable data class TipoTrabajo(val valor: String, val etiqueta: String)
/** dispositivo: AUTORIZADO | REGISTRADO | MIGRADO (resultado de la verificación contra dispositivos_db). */
@Serializable data class PerfilResponse(val nombre: String, val correo: String, val tiposTrabajo: List<TipoTrabajo>, val dispositivo: String? = null)
@Serializable data class AccesoRequest(val tipoTrabajo: String, val lat: Double? = null, val lon: Double? = null)

@Serializable
data class EstadoJornada(
    val fecha: String,
    val tipoTrabajo: String,
    /** SIN_INGRESO | EN_JORNADA | JORNADA_CERRADA */
    val estado: String,
    val horaIngreso: String? = null,
    val horaSalida: String? = null,
    val ubicacionPendiente: Boolean = false,
)

@Serializable
data class MarcacionRequest(val tipoTrabajo: String, val lat: Double, val lon: Double, val accuracyMeters: Double)

@Serializable
data class MarcacionResponse(val mensaje: String, val ubicacionPendiente: Boolean, val estado: EstadoJornada)

@Serializable
data class ErrorResponse(val codigo: String = "", val mensaje: String = "", val idSolicitud: String = "")

// ---------------- Retrofit ----------------

interface ApiAsistencia {
    @POST("auth/google") suspend fun loginGoogle(@Body req: GoogleLoginRequest): SesionResponse
    @POST("auth/dev") suspend fun loginDesarrollo(@Body req: LoginDesarrolloRequest): SesionResponse
    @POST("auth/logout") suspend fun logout(): retrofit2.Response<Unit>

    @GET("asistencia/perfil") suspend fun perfil(): PerfilResponse
    @POST("asistencia/acceso") suspend fun acceso(@Body req: AccesoRequest): retrofit2.Response<Unit>
    @GET("asistencia/estado") suspend fun estado(@Query("tipoTrabajo") tipoTrabajo: String): EstadoJornada

    @POST("asistencia/ingreso")
    suspend fun ingreso(@Header("Idempotency-Key") clave: String, @Body req: MarcacionRequest): MarcacionResponse

    @POST("asistencia/salida")
    suspend fun salida(@Header("Idempotency-Key") clave: String, @Body req: MarcacionRequest): MarcacionResponse
}

object ClienteApi {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun crear(almacen: AlmacenSeguro, dispositivo: IdentidadDispositivo): ApiAsistencia {
        val auth = Interceptor { chain ->
            val token = almacen.leer(RepositorioAsistencia.CLAVE_TOKEN)
            val req = chain.request().newBuilder().apply {
                if (token != null) header("Authorization", "Bearer $token")
                header("X-Dispositivo", dispositivo.id)
            }.build()
            chain.proceed(req)
        }
        val http = OkHttpClient.Builder()
            .addInterceptor(auth)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiAsistencia::class.java)
    }
}
