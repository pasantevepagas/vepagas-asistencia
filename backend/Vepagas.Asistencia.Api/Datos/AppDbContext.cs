using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace Vepagas.Asistencia.Api.Datos;

/// <summary>
/// Base local del backend (SQLite). NO es el almacenamiento de asistencia (eso es SharePoint);
/// guarda: empleados autorizados (mapeo Google ↔ ID_USUARIO), sesiones, bitácora transaccional
/// de operaciones (idempotencia + conciliación) y auditoría.
/// </summary>
public sealed class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    public DbSet<Empleado> Empleados => Set<Empleado>();
    public DbSet<Sesion> Sesiones => Set<Sesion>();
    public DbSet<OperacionAsistencia> Operaciones => Set<OperacionAsistencia>();
    public DbSet<RegistroAuditoria> Auditoria => Set<RegistroAuditoria>();

    protected override void ConfigureConventions(ModelConfigurationBuilder builder)
    {
        // SQLite pierde DateTimeKind: todas las fechas del backend son UTC.
        builder.Properties<DateTime>().HaveConversion<ConversorUtc>();
        builder.Properties<DateTime?>().HaveConversion<ConversorUtcNulo>();
    }

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<Empleado>(e =>
        {
            e.HasIndex(x => x.Correo).IsUnique();
            e.HasIndex(x => x.GoogleSub).IsUnique();
        });

        b.Entity<OperacionAsistencia>(e =>
        {
            e.HasIndex(x => new { x.EmpleadoId, x.ClaveIdempotencia }).IsUnique();
            e.HasIndex(x => new { x.EmpleadoId, x.FechaLocal, x.TipoTrabajo, x.Accion });
            e.HasIndex(x => x.Estado);
            e.Property(x => x.Accion).HasConversion<string>();
            e.Property(x => x.Estado).HasConversion<string>();
        });

        b.Entity<RegistroAuditoria>(e => e.HasIndex(x => x.FechaUtc));
    }

    /// <summary>
    /// EnsureCreated no altera tablas existentes: agrega aquí columnas nuevas para bases ya creadas.
    /// </summary>
    public void ActualizarEsquema()
    {
        Database.EnsureCreated();
        AgregarColumnaSiFalta("Empleados", "DispositivoMigrado", "INTEGER NOT NULL DEFAULT 0");
        AgregarColumnaSiFalta("Sesiones", "DispositivoId", "TEXT NULL");
    }

    private void AgregarColumnaSiFalta(string tabla, string columna, string definicion)
    {
        var conexion = Database.GetDbConnection();
        if (conexion.State != System.Data.ConnectionState.Open) conexion.Open();
        using (var cmd = conexion.CreateCommand())
        {
            cmd.CommandText = $"SELECT COUNT(*) FROM pragma_table_info('{tabla}') WHERE name = '{columna}'";
            if (Convert.ToInt32(cmd.ExecuteScalar()) > 0) return;
        }
        using var alter = conexion.CreateCommand();
        alter.CommandText = $"ALTER TABLE \"{tabla}\" ADD COLUMN \"{columna}\" {definicion}";
        alter.ExecuteNonQuery();
    }

    private sealed class ConversorUtc() : ValueConverter<DateTime, DateTime>(
        v => v.ToUniversalTime(), v => DateTime.SpecifyKind(v, DateTimeKind.Utc));

    private sealed class ConversorUtcNulo() : ValueConverter<DateTime?, DateTime?>(
        v => v.HasValue ? v.Value.ToUniversalTime() : v,
        v => v.HasValue ? DateTime.SpecifyKind(v.Value, DateTimeKind.Utc) : v);
}

public sealed class Empleado
{
    public int Id { get; set; }
    /// <summary>Correo Gmail verificado (minúsculas).</summary>
    public string Correo { get; set; } = "";
    /// <summary>"sub" de Google; se vincula en el primer login y desde ahí es la identidad.</summary>
    public string? GoogleSub { get; set; }
    /// <summary>Valor que se escribe en registros_tb.ID_USUARIO (Entra Object ID si existe, o identificador corporativo).</summary>
    public string IdUsuarioSharePoint { get; set; } = "";
    public string Nombre { get; set; } = "";
    public bool Activo { get; set; } = true;
    public DateTime CreadoUtc { get; set; } = DateTime.UtcNow;
    /// <summary>true cuando dispositivos_db ya apunta a un dispositivo de la APK (la migración desde Power Apps es única).</summary>
    public bool DispositivoMigrado { get; set; }
}

public sealed class Sesion
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public int EmpleadoId { get; set; }
    public DateTime CreadaUtc { get; set; } = DateTime.UtcNow;
    public DateTime ExpiraUtc { get; set; }
    public bool Revocada { get; set; }
    /// <summary>Dispositivo con el que se verificó la sesión contra dispositivos_db (cabecera X-Dispositivo).</summary>
    public string? DispositivoId { get; set; }
}

public enum AccionAsistencia { Ingreso, Salida }

public enum EstadoOperacion
{
    /// <summary>Creada; el paso sobre registros_tb aún no está confirmado (puede reanudarse con la misma Idempotency-Key).</summary>
    Iniciada,
    /// <summary>registros_tb confirmado (POST ingreso o PATCH salida); falta ubicación.</summary>
    RegistroGuardado,
    /// <summary>registros_tb OK pero ubicacion_db falló: la reintenta el conciliador.</summary>
    UbicacionPendiente,
    Completada,
    /// <summary>El conciliador agotó reintentos: requiere revisión manual.</summary>
    RequiereRevision
}

/// <summary>Bitácora transaccional: una fila por operación de ingreso/salida (Graph no da atomicidad entre listas).</summary>
public sealed class OperacionAsistencia
{
    public int Id { get; set; }
    public int EmpleadoId { get; set; }
    public string ClaveIdempotencia { get; set; } = "";
    public AccionAsistencia Accion { get; set; }
    public EstadoOperacion Estado { get; set; }
    public string TipoTrabajo { get; set; } = "";
    /// <summary>Fecha local Ecuador (yyyy-MM-dd).</summary>
    public string FechaLocal { get; set; } = "";
    /// <summary>Hora oficial (servidor) de la marcación.</summary>
    public DateTime HoraUtc { get; set; }
    /// <summary>Inicio de la jornada (= HoraUtc en ingreso; FECHA_HORA_INICIO existente en salida).</summary>
    public DateTime InicioJornadaUtc { get; set; }
    /// <summary>GUID de jornada (registros_tb.ID_REG = ubicacion_db.ID_FECHA).</summary>
    public string IdReg { get; set; } = "";
    public string? ItemIdRegistro { get; set; }
    public string? ItemIdUbicacion { get; set; }
    public double Lat { get; set; }
    public double Lon { get; set; }
    public double PrecisionMetros { get; set; }
    public int IntentosUbicacion { get; set; }
    public string? UltimoError { get; set; }
    /// <summary>Respuesta final ya entregada (para reintentos con la misma Idempotency-Key).</summary>
    public string? RespuestaJson { get; set; }
    public DateTime CreadaUtc { get; set; } = DateTime.UtcNow;
    public DateTime ActualizadaUtc { get; set; } = DateTime.UtcNow;
}

public sealed class RegistroAuditoria
{
    public long Id { get; set; }
    public DateTime FechaUtc { get; set; } = DateTime.UtcNow;
    public int? EmpleadoId { get; set; }
    public string? Correo { get; set; }
    public string Accion { get; set; } = "";
    /// <summary>GOOD / BAD / ERROR (mismo criterio que sesion_db).</summary>
    public string Resultado { get; set; } = "";
    public string? Detalle { get; set; }
    public string? IdSolicitud { get; set; }
    public string? Ip { get; set; }
}
