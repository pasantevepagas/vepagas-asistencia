# VEPAGAS — Contexto y plan de construcción de APK Android de asistencia

**Fecha:** 17 de noviembre de 2026  
**Estado:** cambio de estrategia; documento de arranque para desarrollo  
**Prioridad:** MVP funcional lo antes posible (objetivo operativo original: antes de las 12:00; no es una garantía de entrega).

## 1. Objetivo y decisión de arquitectura

Construir una **aplicación Android nativa (APK)** para empleados de VEPAGAS que utilizan cuentas Gmail y no pueden seguir timbrando en Power Apps tras vencer sus licencias de prueba. Los empleados corporativos continuarán usando Power Apps sin cambios. La nueva APK debe registrar **ingreso, salida y ubicación GPS** y almacenar la información en las **listas SharePoint existentes** del tenant Microsoft 365 de VEPAGAS mediante **Microsoft Graph**.

**Cambio de plan:** Google Apps Script/Google Sheets ya no serán la interfaz ni el almacenamiento de producción. Se conserva el ejemplo previo de Apps Script únicamente como referencia de una integración Graph que ya fue desarrollada y probada por el equipo.

### Arquitectura propuesta

```text
Empleado Android (Gmail)
  -> APK nativa Kotlin (Jetpack Compose)
  -> Autenticación de usuario + API HTTPS propia
  -> Backend confiable (por ejemplo ASP.NET Core en IIS, o Azure Functions)
  -> Microsoft Entra ID: OAuth2 client_credentials (solo backend)
  -> Microsoft Graph
  -> SharePoint del sitio "Desarrollo de Aplicaciones Power App"
       ├── registros_tb
       ├── ubicacion_db
       ├── dispositivos_db (fase de control)
       └── sesion_db (auditoría)
```

**Regla crítica de seguridad:** NO incluir `CLIENT_SECRET`, certificados privados, tokens de aplicación ni credenciales administrativas en el APK, recursos Android, BuildConfig, Git, ni respuestas API. Una APK es extraíble e inspeccionable. El backend conserva los secretos en un almacén seguro y emite llamadas Graph. Una alternativa sería autenticación delegada con MSAL si cada empleado tiene una identidad y derechos de acceso Microsoft adecuados; esto debe verificarse antes de escogerla.

**Identidad:** Gmail no equivale automáticamente a una identidad Entra. Definir autenticación real para cada empleado (p. ej., Google Sign-In con verificación del ID token **en backend**, o cuentas invitadas Entra con inicio de sesión compatible). Mantener tabla de correspondencia `google_sub`/correo verificado ↔ identificador corporativo de empleado y, cuando exista, Entra Object ID. No confiar en correo o ID enviados libremente por el cliente.

**Licenciamiento:** confirmar con administración Microsoft/licenciamiento que la integración Graph con permisos de aplicación y acceso de usuarios externos es conforme. Un backend intermediario no elimina automáticamente obligaciones de licenciamiento ni de acceso.

## 2. Contexto actual validado

- El sitio SharePoint de asistencia es **"Desarrollo de Aplicaciones Power App"**.
- El usuario confirmó que **`registros_tb` y `ubicacion_db` están en el mismo sitio SharePoint que el proyecto HSE** ya conectado a Graph.
- El proyecto HSE en Google Apps Script obtiene token con `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token`, `grant_type=client_credentials`, `scope=https://graph.microsoft.com/.default`; consulta elementos y hace POST a `/v1.0/sites/{siteId}/lists/{listId}/items`.
- El usuario indicó que ya configuró `TENANT_ID`, `CLIENT_ID`, `CLIENT_SECRET`, `SITE_ID`, `LIST_REGISTROS`, `LIST_UBICACIONES` en su prueba de Apps Script. **Estos valores no se han compartido y deben permanecer secretos.**
- El código HSE prueba la **implementación** de autenticación, lectura y creación; falta validar por separado que la identidad de aplicación tenga permisos efectivos sobre las listas de asistencia y soporte PATCH.
- La app Power Apps actual sigue operando para empleados con licencias corporativas; no alterar ni borrar sus listas ni lógica de producción.

## 3. Listas SharePoint existentes y modelo

### `registros_tb` — jornada de asistencia

Campos observados en Power Fx (confirmar nombres internos, tipos y obligatoriedad en SharePoint):

| Campo | Uso |
|---|---|
| `ID_USUARIO` | ID del empleado (en Power Apps: `User().EntraObjectId`) |
| `FECHA_HORA_INICIO` | fecha/hora de ingreso |
| `FECHA_HORA_SALIDA` | fecha/hora de salida, inicialmente vacía |
| `ID_REG` | GUID de jornada generado en ingreso |
| `TIPO_TRABAJO` | tipo de jornada, representado por variable `type` |

### `ubicacion_db` — eventos de ubicación

| Campo | Uso |
|---|---|
| `ID_FECHA` | corresponde a `registros_tb.ID_REG` |
| `HORA` | hora del evento (formato `HH:mm`) |
| `LAT` | latitud |
| `LONG` | longitud |
| `UBICACION` | enlace Google Maps de coordenadas |

En la estructura actual **no se confirmó** un campo `TIPO_MARCACION`; no crearlo ni escribirlo sin autorización. Si se agrega en el futuro, diferenciar INGRESO/SALIDA; de momento el orden de eventos y el vínculo con la jornada deben interpretarse con cuidado.

### `sesion_db` — auditoría

Captura visible: columnas `ID_USUARIO`, `FECHA`, `SESSION` (valores `GOOD`/`BAD`). Power Apps registra acceso autorizado o rechazado. No es requisito para un primer prototipo de timbrada, pero sí deseable para la auditoría y control de seguridad.

### `dispositivos_db` — dispositivos autorizados

Campos vistos en fórmula Power Fx: `ID_USER`, `ID_DISPOSITIVO`, `NOMBRE`, `SESSION`. La app anterior genera y guarda un GUID local con `SaveData/LoadData`. **Ese GUID no es un identificador físico confiable**: se puede perder o duplicar. En Android, registrar una instalación con clave criptográfica de Android Keystore y vincularla al usuario en backend; aplicar aprobación por TI y, si se requiere, integridad del dispositivo (Play Integrity). No utilizar IMEI ni Android ID como solución automática.

## 4. Lógica actual de ingreso (Power Apps)

```powerfx
UpdateContext({fecha_i : LookUp(registros_tb;
    ID_USUARIO = Text(User().EntraObjectId) &&
    Today() = DateValue(FECHA_HORA_INICIO) &&
    TIPO_TRABAJO = type
)});;

If(IsBlank(fecha_i);
    UpdateContext({i:GUID()});;
    Patch(registros_tb; Defaults(registros_tb);
      {ID_USUARIO:User().EntraObjectId;
       FECHA_HORA_INICIO:Now();
       ID_REG:i;
       TIPO_TRABAJO:type});;
    Patch(ubicacion_db; Defaults(ubicacion_db);
      {ID_FECHA:i;
       HORA:Text(Now();"HH:mm");
       LAT:Location.Latitude;
       LONG:Location.Longitude;
       UBICACION:"https://www.google.com/maps/place/" & lat & "," & longi});;
    Notify("Se registro el ingreso del correctamente"; NotificationType.Success);;
    Back();;
;
    Notify("Su ingreso ya fue registrado";NotificationType.Error)
);;
```

**Semántica:** una entrada por `ID_USUARIO + fecha local de inicio + TIPO_TRABAJO`; genera `ID_REG` y un registro GPS con `ID_FECHA` igual al GUID. El enlace usa variables `lat`/`longi` cuya procedencia no fue confirmada; en Android construirlo desde las coordenadas realmente capturadas.

## 5. Lógica actual de salida (Power Apps)

```powerfx
UpdateContext({fecha_i : LookUp(registros_tb;
    ID_USUARIO = Text(User().EntraObjectId) &&
    Today() = DateValue(FECHA_HORA_INICIO) &&
    TIPO_TRABAJO = type
)});;

If(IsBlank(fecha_i.FECHA_HORA_INICIO);
    Notify("No se ha registrado su entrada";NotificationType.Error);;
;
    If(!IsBlank(fecha_i.FECHA_HORA_SALIDA);
        Notify("Su salida ya fue registrada";NotificationType.Error);;
    ;
        Patch(registros_tb;
            LookUp(registros_tb;
                ID_USUARIO = Text(User().EntraObjectId) &&
                Today() = DateValue(FECHA_HORA_INICIO) &&
                TIPO_TRABAJO = type);
            {FECHA_HORA_SALIDA:Now()});;
        Patch(ubicacion_db; Defaults(ubicacion_db);
            {HORA:Text(Now();"HH:mm");
             ID_FECHA:fecha_i.ID_REG;
             LAT:Location.Latitude;
             LONG:Location.Longitude;
             UBICACION:"https://www.google.com/maps/place/" & lat & "," & longi});;
        Notify("Se registro la salida correctamente"; NotificationType.Success);;
        Back(ScreenTransition.UnCoverRight)
    )
);;
```

**Semántica:** no permite salida sin entrada ni segunda salida. Actualiza `FECHA_HORA_SALIDA` en la misma fila `registros_tb` y agrega segunda fila GPS en `ubicacion_db`, con el **mismo GUID**.

## 6. Requisitos funcionales del MVP

1. Inicio de sesión real y verificación de empleado autorizado (no permitir que el usuario elija libremente el ID de otra persona).
2. Pantalla principal sencilla con nombre, fecha, tipo de trabajo, estado de jornada, botones **REGISTRAR INGRESO** y **REGISTRAR SALIDA**.
3. Solicitud de permiso de ubicación **al usar la aplicación**; obtener coordenadas actuales y precisión; rechazar ubicación ausente u obsoleta, informar cómo habilitar GPS. No solicitar ubicación permanente si no es necesaria.
4. Validar en backend: ingreso único por empleado + fecha local Ecuador (`America/Guayaquil`) + tipo de trabajo; salida solo con ingreso existente y sin salida previa.
5. Usar **hora del servidor** como hora oficial; almacenar UTC y convertir correctamente a zona de Ecuador al leer/escribir campos SharePoint, evitando diferencias por configuración regional.
6. Ingreso: crear fila `registros_tb`, después fila `ubicacion_db`, ambas vinculadas por UUID. Salida: PATCH de `FECHA_HORA_SALIDA` sobre el ítem existente y POST de segunda ubicación.
7. Gestionar errores parciales y reintentos sin duplicar marcaciones; usar idempotency key por operación, bloqueo/serialización por empleado y bitácora transaccional en backend. Microsoft Graph no garantiza una transacción atómica entre dos listas.
8. Mostrar confirmación solo después de verificar éxito; si ubicación no pudo guardarse, indicar estado pendiente de conciliación y evitar que el usuario repita sin control.
9. Registro de auditoría (quién, qué acción, cuándo, resultado, identificador de solicitud); no exponer secretos en logs.
10. APK instalable en dispositivos Android de prueba, acceso por HTTPS y política de privacidad adecuada para geolocalización y datos laborales conforme a LOPDP Ecuador.

**Casos especiales por definir antes de producción:** turno que cruza medianoche; múltiples tipos de trabajo; cambio de zona horaria del dispositivo; pérdida de conexión; empleados sin Entra Object ID; bloqueo por dispositivo; registro de actividad de Ruta Comercial (fuera de MVP).

## 7. API propuesta (backend)

```http
POST /api/auth/google
Authorization: Bearer <Google ID token>
# Verificar firma, audience, issuer, expiración, google_sub y autorización

GET /api/asistencia/estado?tipoTrabajo=...
Authorization: Bearer <token de sesión del backend>

POST /api/asistencia/ingreso
Authorization: Bearer <token de sesión del backend>
Idempotency-Key: <uuid>
Content-Type: application/json
{"tipoTrabajo":"...", "lat":-0.0, "lon":-0.0, "accuracyMeters":12}

POST /api/asistencia/salida
Authorization: Bearer <token de sesión del backend>
Idempotency-Key: <uuid>
Content-Type: application/json
{"tipoTrabajo":"...", "lat":-0.0, "lon":-0.0, "accuracyMeters":12}
```

El backend **deriva `ID_USUARIO` de la sesión autenticada**, no del JSON enviado por Android. Los endpoints y nombres anteriores son propuesta, no infraestructura existente.

### Llamadas Graph (referencia)

```http
POST https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded
# grant_type=client_credentials&client_id=...&client_secret=...&scope=https://graph.microsoft.com/.default

GET https://graph.microsoft.com/v1.0/sites/{siteId}/lists/{listId}/items?$expand=fields

POST https://graph.microsoft.com/v1.0/sites/{siteId}/lists/{listId}/items
Content-Type: application/json
{"fields":{"ID_USUARIO":"...","ID_REG":"...","TIPO_TRABAJO":"...","FECHA_HORA_INICIO":"..."}}

PATCH https://graph.microsoft.com/v1.0/sites/{siteId}/lists/{listId}/items/{itemId}/fields
Content-Type: application/json
{"FECHA_HORA_SALIDA":"..."}
```

**Importante:** validar nombres internos y tipos reales de columnas mediante `GET /sites/{siteId}/lists/{listId}/columns` y un elemento de prueba; las propiedades visibles de SharePoint pueden diferir de las internas. Las consultas filtradas por campos requieren revisar índices y capacidades Graph. Preferir permisos de aplicación restringidos a sitio (`Sites.Selected`) si encajan con la configuración y el consentimiento del administrador.

## 8. Stack recomendado

- **Android:** Kotlin, Jetpack Compose, Android Studio, Retrofit/OkHttp, Fused Location Provider, Android Keystore; minSdk a confirmar según dispositivos reales.
- **Backend:** ASP.NET Core Web API sobre IIS/Windows Server corporativo existente, **si** se puede publicar HTTPS y asegurar el servicio; alternativa Azure Functions si está disponible. La elección es una propuesta, no infraestructura desplegada.
- **Autenticación:** Google Identity Services/Google Sign-In para Gmail + verificación del token en backend; emisión de sesión corta y revocable. Si se utiliza Entra B2B, adaptar el flujo.
- **Datos de producción:** listas SharePoint existentes mediante Microsoft Graph; **sin Google Sheets**.
- **Seguridad:** secretos backend en almacenamiento seguro, HTTPS, permisos mínimos, registros de auditoría, protección de datos, rate limiting y validación de entradas.

## 9. Orden de trabajo para un MVP urgente

### Fase A — validar integración (sin escritura en producción)

1. Confirmar que la app Entra existente puede leer `registros_tb` y `ubicacion_db` en el mismo `SITE_ID`.
2. Obtener los **IDs de ambas listas** y sus columnas internas y tipos.
3. Confirmar permisos de escritura de Graph; usar lista de prueba o elementos de prueba controlados.
4. Confirmar método de autenticación de Gmail y mapeo con `ID_USUARIO` que usa Power Apps.

### Fase B — construir backend

5. Crear Web API con endpoint de estado y autenticación.
6. Implementar Graph token server-side, lectura, POST y PATCH.
7. Implementar validaciones, idempotencia, errores parciales y auditoría.

### Fase C — construir APK

8. Crear proyecto Kotlin + Compose, pantalla login, inicio y salida.
9. Capturar GPS con permisos Android y precisión.
10. Conectar a API HTTPS y mostrar estados reales.
11. Generar APK de prueba firmada, instalar en teléfono Android y probar flujo completo.

### Fase D — producción controlada

12. Validar licenciamiento, protección de datos y autorización de TI/RR. HH.
13. Piloto con 1–3 empleados Gmail; comprobar registros en SharePoint y lectura desde Power Apps.
14. Desplegar gradualmente; agregar `dispositivos_db`, `sesion_db` y Ruta Comercial después de estabilizar timbradas, salvo que sean condiciones obligatorias de salida a producción.

**No comprometer un lanzamiento real antes de las 12:00 sin verificar identidad, permisos, escritura y pruebas.** Si el plazo es inamovible, entregar primero una APK de demostración con backend de prueba y programar habilitación productiva tras validaciones.

## 10. Información pendiente que debe pedir el asistente de desarrollo

- URLs o IDs de `registros_tb`, `ubicacion_db` y sus columnas internas; `SITE_ID` ya es común al proyecto HSE.
- Definición y valores válidos de `TIPO_TRABAJO` (`type` en Power Apps).
- ¿Los empleados Gmail son invitados Entra con Object ID o necesitan identificador corporativo propio?
- ¿Dónde alojar el backend: IIS corporativo accesible por Internet con HTTPS, Azure, otro?
- ¿Se exige un dispositivo autorizado por empleado desde el primer día?
- Versión mínima Android y disponibilidad de Google Play Services.
- Configuración de permisos de la aplicación Entra (solo los nombres, **nunca secretos**).
- Criterios de RR. HH. para turnos nocturnos, marcaciones fuera de sitio, ausencia de GPS y corrección de registros.

## 11. Instrucciones para el asistente que construirá la aplicación

**Actúa como desarrollador senior Android Kotlin + ASP.NET Core + Microsoft Graph.** Construye una solución completa y ejecutable para VEPAGAS basada en este documento. Prioriza MVP ingreso/salida con GPS, manteniendo compatibilidad con las listas SharePoint existentes. No cambies las listas ni el Power Apps productivo sin confirmación. Divide el trabajo en entregables verificables: (1) validación Graph/columnas, (2) backend seguro, (3) proyecto Android Studio, (4) pruebas y APK. Entrega estructura de carpetas, archivos fuente completos, configuración por entorno, comandos para ejecutar, instrucciones de publicación y pruebas paso a paso. No incluyas secretos reales ni asumas que Gmail concede automáticamente acceso a Microsoft Graph. Pregunta solo por los datos imprescindibles que falten; empieza por la validación del esquema y la identidad. Si se requieren cambios a SharePoint, propónlos y espera aprobación.
