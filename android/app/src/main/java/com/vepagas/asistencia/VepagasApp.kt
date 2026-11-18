package com.vepagas.asistencia

import android.app.Application
import com.vepagas.asistencia.auth.SesionMicrosoft
import com.vepagas.asistencia.datos.AlmacenSeguro
import com.vepagas.asistencia.datos.GraphSharePoint
import com.vepagas.asistencia.datos.IdentidadDispositivo
import com.vepagas.asistencia.datos.RepositorioAsistencia
import com.vepagas.asistencia.ubicacion.ProveedorUbicacion

/** Contenedor de dependencias simple. Sin backend: la app habla directo con SharePoint del tenant. */
class VepagasApp : Application() {
    lateinit var repositorio: RepositorioAsistencia
        private set
    lateinit var ubicacion: ProveedorUbicacion
        private set

    override fun onCreate() {
        super.onCreate()
        val sesion = SesionMicrosoft(this)
        repositorio = RepositorioAsistencia(
            sesion = sesion,
            graph = GraphSharePoint(sesion::token),
            almacen = AlmacenSeguro(this),
            dispositivo = IdentidadDispositivo(this),
        )
        ubicacion = ProveedorUbicacion(this)
    }
}
