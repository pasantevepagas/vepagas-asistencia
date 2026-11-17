namespace Vepagas.Asistencia.Api.Opciones;

/// <summary>Credenciales de la app Entra (solo backend). Nunca en appsettings versionado: usar user-secrets o variables de entorno.</summary>
public sealed class GraphOpciones
{
    public const string Seccion = "Graph";
    public string TenantId { get; set; } = "";
    public string ClientId { get; set; } = "";
    public string ClientSecret { get; set; } = "";
}

public sealed class SharePointOpciones
{
    public const string Seccion = "SharePoint";

    /// <summary>"Real" (Microsoft Graph) o "Simulado" (memoria, solo para demo/desarrollo).</summary>
    public string Modo { get; set; } = "Simulado";
    public string SiteId { get; set; } = "";
    public string ListaRegistrosId { get; set; } = "";
    public string ListaUbicacionesId { get; set; } = "";

    /// <summary>true si LAT/LONG son columnas Number; false si son Text. Confirmar con `dotnet run -- diagnostico`.</summary>
    public bool CoordenadasComoNumero { get; set; } = true;

    /// <summary>Si LAT/LONG son texto: true si Power Apps las guarda con coma decimal ("-0,1807").</summary>
    public bool CoordenadasConComaDecimal { get; set; }

    /// <summary>
    /// Formato .NET con que Power Apps guarda Now() en las columnas de TEXTO FECHA_HORA_INICIO/SALIDA
    /// (p. ej. "d/M/yyyy H:mm"). "ISO" = yyyy-MM-ddTHH:mm:ssZ. Confirmar con `dotnet run -- diagnostico`.
    /// </summary>
    public string FormatoFechaTexto { get; set; } = "";
    public string CulturaFechaTexto { get; set; } = "es-EC";
    /// <summary>true si el texto está en hora local de Ecuador (lo normal cuando Power Apps convierte Now() a texto).</summary>
    public bool FechaTextoEnHoraLocal { get; set; } = true;

    public CamposRegistro CamposRegistro { get; set; } = new();
    public CamposUbicacion CamposUbicacion { get; set; } = new();

    // Listas de control (mismo comportamiento que el OnStart / botón Tele trabajo de Power Apps).
    public string ListaDispositivosId { get; set; } = "";
    public string ListaSesionesId { get; set; } = "";
    public string ListaUbicacionAppId { get; set; } = "";
    public CamposDispositivo CamposDispositivo { get; set; } = new();
    public CamposSesion CamposSesion { get; set; } = new();
    public CamposUbicacionApp CamposUbicacionApp { get; set; } = new();
}

/// <summary>dispositivos_db. OJO: la columna visible "ID_USER" tiene nombre interno "ID_DISP".</summary>
public sealed class CamposDispositivo
{
    public string IdUsuario { get; set; } = "ID_DISP";
    public string IdDispositivo { get; set; } = "ID_DISPOSITIVO";
    public string Nombre { get; set; } = "NOMBRE";
    public string Sesion { get; set; } = "SESSION";
}

/// <summary>sesion_db. FECHA es columna de fecha (solo día).</summary>
public sealed class CamposSesion
{
    public string IdUsuario { get; set; } = "ID_USUARIO";
    public string Fecha { get; set; } = "FECHA";
    public string Sesion { get; set; } = "SESSION";
}

/// <summary>UBICACION_APP (se escribe al pulsar "Tele trabajo").</summary>
public sealed class CamposUbicacionApp
{
    public string Ubicacion { get; set; } = "UBICACION";
    public string Nombre { get; set; } = "NOMBRE";
}

/// <summary>Nombres INTERNOS de columnas en registros_tb (confirmar con diagnóstico).</summary>
public sealed class CamposRegistro
{
    public string IdUsuario { get; set; } = "ID_USUARIO";
    public string FechaHoraInicio { get; set; } = "FECHA_HORA_INICIO";
    public string FechaHoraSalida { get; set; } = "FECHA_HORA_SALIDA";
    public string IdReg { get; set; } = "ID_REG";
    public string TipoTrabajo { get; set; } = "TIPO_TRABAJO";
}

/// <summary>Nombres INTERNOS de columnas en ubicacion_db (confirmar con diagnóstico).</summary>
public sealed class CamposUbicacion
{
    public string IdFecha { get; set; } = "ID_FECHA";
    public string Hora { get; set; } = "HORA";
    public string Lat { get; set; } = "LAT";
    public string Long { get; set; } = "LONG";
    public string Ubicacion { get; set; } = "UBICACION";
}

public sealed class AuthOpciones
{
    public const string Seccion = "Auth";

    /// <summary>Client IDs OAuth "Web" de Google aceptados como audience del ID token (el mismo que usa Android como serverClientId).</summary>
    public string[] GoogleClientIds { get; set; } = [];

    /// <summary>Clave HMAC para firmar tokens de sesión (mín. 32 bytes). Secreto: user-secrets / variable de entorno.</summary>
    public string ClaveFirmaJwt { get; set; } = "";
    public string Emisor { get; set; } = "vepagas-asistencia";
    public int DuracionSesionHoras { get; set; } = 12;

    /// <summary>Clave para endpoints /api/admin (cabecera X-Admin-Key). Secreto.</summary>
    public string ClaveAdmin { get; set; } = "";

    /// <summary>Solo funciona si además el entorno es Development. Permite login por correo sin Google para pruebas.</summary>
    public bool PermitirLoginDesarrollo { get; set; }
}

public sealed class AsistenciaOpciones
{
    public const string Seccion = "Asistencia";
    public string ZonaHoraria { get; set; } = "America/Guayaquil";
    /// <summary>Valores que se guardan en TIPO_TRABAJO (el `type` de Power Apps), p. ej. "Home_Office".</summary>
    public string[] TiposTrabajo { get; set; } = [];
    /// <summary>Texto del botón para cada valor, p. ej. "Home_Office" -> "Tele trabajo".</summary>
    public Dictionary<string, string> EtiquetasTiposTrabajo { get; set; } = [];
    public double PrecisionMaximaMetros { get; set; } = 100;
    public int MinutosReconciliacion { get; set; } = 2;
    public int IntentosMaximosUbicacion { get; set; } = 30;

    /// <summary>Exigir dispositivo autorizado (dispositivos_db) como el OnStart de Power Apps.</summary>
    public bool ControlDispositivos { get; set; } = true;

    /// <summary>
    /// Primera vez que un empleado entra con la APK y ya tiene fila de Power Apps en dispositivos_db:
    /// se actualiza ID_DISPOSITIVO con el de la APK (una sola vez). Luego la regla es estricta.
    /// </summary>
    public bool MigrarDispositivosPowerApps { get; set; } = true;

    /// <summary>Escribir UBICACION_APP al pulsar "Tele trabajo", como Power Apps.</summary>
    public bool RegistrarUbicacionApp { get; set; } = true;
}
