package com.vepagas.asistencia.ubicacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ResultadoUbicacion {
    data class Ok(val lat: Double, val lon: Double, val precisionMetros: Double) : ResultadoUbicacion
    data object SinPermiso : ResultadoUbicacion
    data object GpsApagado : ResultadoUbicacion
    data class Error(val mensaje: String) : ResultadoUbicacion
}

/** Obtiene UNA ubicación actual y fresca (Fused Location Provider), solo en primer plano. */
class ProveedorUbicacion(private val context: Context) {
    private val cliente = LocationServices.getFusedLocationProviderClient(context)

    fun tienePermiso(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun gpsEncendido(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @SuppressLint("MissingPermission")
    suspend fun obtener(): ResultadoUbicacion {
        if (!tienePermiso()) return ResultadoUbicacion.SinPermiso
        if (!gpsEncendido()) return ResultadoUbicacion.GpsApagado

        val cancelacion = CancellationTokenSource()
        val solicitud = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(EDAD_MAXIMA_MS) // no aceptar caché vieja
            .setDurationMillis(TIEMPO_MAXIMO_MS)
            .build()

        val ubicacion: Location? = try {
            withTimeoutOrNull(TIEMPO_MAXIMO_MS + 2_000) {
                cliente.getCurrentLocation(solicitud, cancelacion.token).await()
            }
        } catch (e: Exception) {
            return ResultadoUbicacion.Error("No se pudo obtener la ubicación: ${e.localizedMessage}")
        } finally {
            cancelacion.cancel()
        }

        if (ubicacion == null)
            return ResultadoUbicacion.Error("No se pudo obtener la ubicación. Salga a un lugar abierto e intente nuevamente.")

        val edadMs = (SystemClock.elapsedRealtimeNanos() - ubicacion.elapsedRealtimeNanos) / 1_000_000
        if (edadMs > EDAD_MAXIMA_MS)
            return ResultadoUbicacion.Error("La ubicación obtenida es antigua. Intente nuevamente.")

        val simulada = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ubicacion.isMock
                       else @Suppress("DEPRECATION") ubicacion.isFromMockProvider
        if (simulada)
            return ResultadoUbicacion.Error("Se detectó una ubicación simulada. Desactive apps de ubicación falsa.")

        if (!ubicacion.hasAccuracy())
            return ResultadoUbicacion.Error("La ubicación no indica precisión. Intente nuevamente.")

        return ResultadoUbicacion.Ok(ubicacion.latitude, ubicacion.longitude, ubicacion.accuracy.toDouble())
    }

    private companion object {
        const val EDAD_MAXIMA_MS = 60_000L
        const val TIEMPO_MAXIMO_MS = 20_000L
    }
}
