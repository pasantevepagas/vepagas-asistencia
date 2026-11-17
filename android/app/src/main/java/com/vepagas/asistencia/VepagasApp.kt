package com.vepagas.asistencia

import android.app.Application
import com.vepagas.asistencia.datos.AlmacenSeguro
import com.vepagas.asistencia.datos.ClienteApi
import com.vepagas.asistencia.datos.IdentidadDispositivo
import com.vepagas.asistencia.datos.RepositorioAsistencia
import com.vepagas.asistencia.ubicacion.ProveedorUbicacion

/** Contenedor de dependencias simple (sin framework de DI para el MVP). */
class VepagasApp : Application() {
    lateinit var repositorio: RepositorioAsistencia
        private set
    lateinit var ubicacion: ProveedorUbicacion
        private set

    override fun onCreate() {
        super.onCreate()
        val almacen = AlmacenSeguro(this)
        val dispositivo = IdentidadDispositivo(this)
        repositorio = RepositorioAsistencia(ClienteApi.crear(almacen, dispositivo), almacen)
        ubicacion = ProveedorUbicacion(this)
    }
}
