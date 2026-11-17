using System.Security.Claims;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using Vepagas.Asistencia.Api;
using Vepagas.Asistencia.Api.Contratos;
using Vepagas.Asistencia.Api.Datos;
using Vepagas.Asistencia.Api.Endpoints;
using Vepagas.Asistencia.Api.Opciones;
using Vepagas.Asistencia.Api.Servicios;
using Vepagas.Asistencia.Api.SharePoint;

var builder = WebApplication.CreateBuilder(args);
var cfg = builder.Configuration;

// ---------- Opciones ----------
builder.Services.Configure<GraphOpciones>(cfg.GetSection(GraphOpciones.Seccion));
builder.Services.Configure<SharePointOpciones>(cfg.GetSection(SharePointOpciones.Seccion));
builder.Services.Configure<AuthOpciones>(cfg.GetSection(AuthOpciones.Seccion));
builder.Services.Configure<AsistenciaOpciones>(cfg.GetSection(AsistenciaOpciones.Seccion));

var spOpc = cfg.GetSection(SharePointOpciones.Seccion).Get<SharePointOpciones>() ?? new();
var authOpc = cfg.GetSection(AuthOpciones.Seccion).Get<AuthOpciones>() ?? new();
var modoImportar = args.Length >= 2 && args[0].Equals("importar-empleados", StringComparison.OrdinalIgnoreCase);
var modoInspeccion = args.Length >= 2 && args[0].Equals("diagnostico-lista", StringComparison.OrdinalIgnoreCase);
var modoDiagnostico = args.Contains("diagnostico", StringComparer.OrdinalIgnoreCase) || modoImportar || modoInspeccion; // herramientas de consola

// ---------- Base local ----------
var modoRealCfg = string.Equals(spOpc.Modo, "Real", StringComparison.OrdinalIgnoreCase);
// El modo Simulado usa su PROPIA base: su bitácora de pruebas nunca debe bloquear marcaciones reales.
var cadena = modoRealCfg
    ? cfg.GetConnectionString("Asistencia") ?? "Data Source=App_Data/asistencia.db"
    : cfg.GetConnectionString("AsistenciaSimulado") ?? "Data Source=App_Data/asistencia-simulado.db";
var rutaDb = new SqliteConnectionStringBuilder(cadena).DataSource;
if (Path.GetDirectoryName(Path.GetFullPath(rutaDb)) is { } dirDb) Directory.CreateDirectory(dirDb);
builder.Services.AddDbContext<AppDbContext>(o => o.UseSqlite(cadena));

// ---------- SharePoint / Graph ----------
builder.Services.AddHttpClient("graph", c => c.Timeout = TimeSpan.FromSeconds(30));
builder.Services.AddHttpClient("login", c => c.Timeout = TimeSpan.FromSeconds(20));
builder.Services.AddSingleton<ProveedorTokenGraph>();
builder.Services.AddSingleton<FormatoFechaSharePoint>();
builder.Services.AddSingleton<RepositorioGraph>();
builder.Services.AddSingleton<RepositorioSimulado>();
var modoReal = modoRealCfg;
builder.Services.AddSingleton<IRepositorioAsistencia>(sp => modoReal
    ? sp.GetRequiredService<RepositorioGraph>()
    : sp.GetRequiredService<RepositorioSimulado>());

// ---------- Servicios ----------
builder.Services.AddSingleton<RelojEcuador>();
builder.Services.AddSingleton<CandadosEmpleado>();
builder.Services.AddScoped<ServicioAsistencia>();
builder.Services.AddScoped<ServicioSesion>();
builder.Services.AddScoped<ServicioAuditoria>();
builder.Services.AddScoped<ServicioDispositivo>();
if (!modoDiagnostico) builder.Services.AddHostedService<Conciliador>();

// ---------- Autenticación (token de sesión propio, HS256) ----------
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(o =>
    {
        o.MapInboundClaims = false;
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = authOpc.Emisor,
            ValidAudience = authOpc.Emisor,
            IssuerSigningKey = modoDiagnostico ? null : ServicioSesion.ClaveFirma(authOpc),
            ValidateIssuerSigningKey = true,
            ClockSkew = TimeSpan.FromMinutes(1),
        };
        o.Events = new JwtBearerEvents
        {
            // Sesión revocable: se verifica en BD en cada request.
            OnTokenValidated = async ctx =>
            {
                var sesiones = ctx.HttpContext.RequestServices.GetRequiredService<ServicioSesion>();
                var empleado = await sesiones.ResolverAsync(ctx.Principal!, ctx.HttpContext.RequestAborted);
                if (empleado is null) ctx.Fail("Sesión revocada, expirada o empleado inactivo.");
                else ctx.HttpContext.Items[Endpoints.ClaveItemEmpleado] = empleado;
            },
        };
    });
builder.Services.AddAuthorization();

// ---------- Rate limiting ----------
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.AddPolicy("auth", http => RateLimitPartition.GetFixedWindowLimiter(
        http.Connection.RemoteIpAddress?.ToString() ?? "?",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(1) }));
    o.AddPolicy("marcacion", http => RateLimitPartition.GetFixedWindowLimiter(
        http.User.FindFirstValue("sub") ?? http.Connection.RemoteIpAddress?.ToString() ?? "?",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 30, Window = TimeSpan.FromMinutes(1) }));
});

var app = builder.Build();

// ---------- Inicialización ----------
using (var scope = app.Services.CreateScope())
{
    scope.ServiceProvider.GetRequiredService<AppDbContext>().ActualizarEsquema();
}

if (modoInspeccion)
    return await Diagnostico.InspeccionarListaAsync(app.Services, args[1]);
if (modoImportar)
    return await ImportadorEmpleados.EjecutarAsync(app.Services, args[1], args.Contains("--simular", StringComparer.OrdinalIgnoreCase));
if (modoDiagnostico)
    return await Diagnostico.EjecutarAsync(app.Services);

if (!modoReal && !app.Environment.IsDevelopment())
    throw new InvalidOperationException("SharePoint:Modo=Simulado solo se permite en Development.");
if (modoReal && (string.IsNullOrWhiteSpace(spOpc.SiteId) || string.IsNullOrWhiteSpace(spOpc.ListaRegistrosId) || string.IsNullOrWhiteSpace(spOpc.ListaUbicacionesId)))
    throw new InvalidOperationException("Modo Real requiere SharePoint:SiteId, ListaRegistrosId y ListaUbicacionesId.");
var asisOpc = cfg.GetSection(AsistenciaOpciones.Seccion).Get<AsistenciaOpciones>() ?? new();
if (modoReal && asisOpc.ControlDispositivos && (string.IsNullOrWhiteSpace(spOpc.ListaDispositivosId) || string.IsNullOrWhiteSpace(spOpc.ListaSesionesId)))
    throw new InvalidOperationException("ControlDispositivos requiere SharePoint:ListaDispositivosId y ListaSesionesId.");
if (modoReal && asisOpc.RegistrarUbicacionApp && string.IsNullOrWhiteSpace(spOpc.ListaUbicacionAppId))
    throw new InvalidOperationException("RegistrarUbicacionApp requiere SharePoint:ListaUbicacionAppId.");
if (modoReal && string.IsNullOrWhiteSpace(spOpc.FormatoFechaTexto))
    throw new InvalidOperationException("Modo Real requiere SharePoint:FormatoFechaTexto (el formato en que Power Apps guarda FECHA_HORA_INICIO). Ver `dotnet run -- diagnostico`.");
app.Logger.LogInformation("SharePoint modo {Modo}", modoReal ? "REAL (Graph)" : "SIMULADO (memoria)");

// ---------- Pipeline ----------
app.UseExceptionHandler(e => e.Run(async http =>
{
    var ex = http.Features.Get<IExceptionHandlerFeature>()?.Error;
    var (estado, codigo, mensaje) = ex is ErrorNegocio n
        ? (n.EstadoHttp, n.Codigo, n.Message)
        : (500, "ERROR_INTERNO", "Ocurrió un error inesperado. Intente nuevamente.");
    if (ex is not ErrorNegocio)
        app.Logger.LogError(ex, "Error no controlado {Id}", http.TraceIdentifier);
    http.Response.StatusCode = estado;
    await http.Response.WriteAsJsonAsync(new ErrorResponse(codigo, mensaje, http.TraceIdentifier));
}));

if (!app.Environment.IsDevelopment())
{
    app.UseHsts();
    app.UseHttpsRedirection();
}

app.UseAuthentication();
app.UseAuthorization();
app.UseRateLimiter();

Endpoints.Mapear(app);
EndpointsApp.Mapear(app);

app.Run();
return 0;
