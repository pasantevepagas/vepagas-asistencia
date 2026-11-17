# VEPAGAS Asistencia: APK Android + backend

Contexto y requisitos: [CONTEXTO_PROYECTO_VEPAGAS_ANDROID.md](CONTEXTO_PROYECTO_VEPAGAS_ANDROID.md).

```text
android/                     APK Kotlin + Jetpack Compose (AGP 9.5.1, Gradle 9.9.1, minSdk 26, target 37)
backend/Vepagas.Asistencia.Api/   ASP.NET Core .NET 10 (Minimal API) -> Microsoft Graph -> SharePoint
```

## Arquitectura

```text
APK --(Google ID token)--> POST /api/auth/google --> verifica firma/audience/email en backend
                                                  --> busca Empleado autorizado (SQLite local)
                                                  <-- token de sesión propio (JWT 12 h, revocable)
APK --(Bearer + Idempotency-Key + GPS)--> /api/asistencia/ingreso|salida
     backend: hora del servidor, reglas Power Apps, bitácora local --> Graph (client_credentials)
              registros_tb (POST/PATCH) + ubicacion_db (POST)
```

- El APK **no contiene ningún secreto**: solo la URL del API y el *Web Client ID* de Google, que es público.
- `ID_USUARIO` se toma del empleado autenticado en el backend. El cliente nunca lo envía.
- La base SQLite del backend **no guarda asistencia**. Guarda el mapeo de empleados (correo/Google `sub` ↔ `ID_USUARIO`), las sesiones, la bitácora de operaciones (para idempotencia y conciliación) y la auditoría.

### Garantías de la marcación

| Situación | Comportamiento |
|---|---|
| Doble toque o reintento por red | Usa la misma `Idempotency-Key` y devuelve la misma respuesta, sin duplicar. |
| Falla POST/PATCH en `registros_tb` | Responde 503. La app conserva la clave, y al reintentar el backend **reanuda**: verifica en SharePoint si la fila se llegó a crear antes de volver a escribirla. |
| `registros_tb` OK pero falla `ubicacion_db` | Se confirma la marcación con aviso "ubicación pendiente". El `Conciliador` reintenta cada 2 min y verifica `ID_FECHA`+`HORA` para no duplicar. Tras 30 intentos queda en `RequiereRevision`. |
| Ingreso duplicado (mismo día Ecuador y tipo) | 409 "Su ingreso ya fue registrado", aunque se haya marcado desde Power Apps. |
| Salida sin ingreso o salida repetida | 409, con los mismos mensajes que Power Apps. |
| Dos solicitudes simultáneas del mismo empleado | Se procesan una a la vez (candado por empleado). |
| GPS ausente, impreciso (>100 m), viejo (>60 s) o simulado | Se rechaza en la app, y la precisión se valida otra vez en el backend. |

### Control de dispositivos (igual que el OnStart de Power Apps)

Cada vez que se abre la app (`GET /api/asistencia/perfil`, con la cabecera `X-Dispositivo`):

| Situación en `dispositivos_db` (columna interna `ID_DISP` = ID_USER) | Resultado |
|---|---|
| El empleado no tiene fila | Se crea la fila `AUTH` con el ID de la APK. `sesion_db = GOOD`. |
| La fila tiene el mismo ID de dispositivo | `sesion_db = GOOD`, "Dispositivo autorizado". |
| La fila viene de Power Apps y el empleado aún no migró | **Migración única:** se actualiza `ID_DISPOSITIVO` con el de la APK. `sesion_db = GOOD`. |
| Otro teléfono (ya migrado) | `sesion_db = BAD` y la app queda bloqueada. TI habilita el teléfono nuevo **borrando la fila** del empleado en `dispositivos_db`. |

- El ID de dispositivo lo genera la APK al instalarse y lo guarda cifrado con el Android Keystore. Cerrar sesión no lo borra; desinstalar la app o borrar sus datos sí, y entonces el teléfono cuenta como nuevo.
- **Nunca vincule a un empleado corporativo que sigue usando Power Apps:** la migración cambiaría su `ID_DISPOSITIVO` y su Power Apps quedaría bloqueado.
- El botón "Tele trabajo" escribe `UBICACION_APP` (`"lat lon"` con coma decimal, y `NOMBRE`).
- Opciones en `Asistencia`: `ControlDispositivos`, `MigrarDispositivosPowerApps`, `RegistrarUbicacionApp`.

---

## 1. Fase A: validar Graph y columnas (sin escribir)

```powershell
cd backend\Vepagas.Asistencia.Api
dotnet user-secrets set "Graph:TenantId"     "<tenant>"
dotnet user-secrets set "Graph:ClientId"     "<client id app Entra>"
dotnet user-secrets set "Graph:ClientSecret" "<secreto>"
dotnet user-secrets set "SharePoint:SiteId"  "<SITE_ID del proyecto HSE>"
dotnet run -- diagnostico
```

El comando lista las listas del sitio con su `id`. Copie los ids de `registros_tb` y `ubicacion_db`:

```powershell
dotnet user-secrets set "SharePoint:ListaRegistrosId"   "<id>"
dotnet user-secrets set "SharePoint:ListaUbicacionesId" "<id>"
dotnet run -- diagnostico
```

Con los ids configurados, imprime también las **columnas internas y sus tipos**. Revise:

1. Los nombres internos coinciden con `SharePoint:CamposRegistro` / `CamposUbicacion` en `appsettings.json`. Si no coinciden, ajústelos ahí; no hace falta tocar código.
2. Tipo de `LAT`/`LONG`: si es `number`, use `CoordenadasComoNumero=true`; si es `text`, use `false`.
3. Si `TIPO_TRABAJO` es `choice`, la salida muestra sus opciones. Copie esas opciones a `Asistencia:TiposTrabajo`.
4. Recomendado: indexar `ID_USUARIO` y `FECHA_HORA_INICIO` (registros_tb) e `ID_FECHA` (ubicacion_db) en la configuración de la lista. Sin índices, las consultas filtradas pueden fallar cuando la lista supere 5 000 ítems.

> La escritura (POST/PATCH) se valida aparte con un **empleado de prueba** (`ID_USUARIO` ficticio, p. ej. `PRUEBA-APK-001`), y luego se borran esas filas a mano.

## 2. Backend en local

### Modo simulado (sin SharePoint ni Google, para demo inmediata)

```powershell
cd backend\Vepagas.Asistencia.Api
dotnet user-secrets set "Auth:ClaveFirmaJwt" "<cadena aleatoria de 48+ caracteres>"
dotnet user-secrets set "Auth:ClaveAdmin"    "<cadena aleatoria de 32+ caracteres>"
dotnet run --launch-profile http          # http://0.0.0.0:5080, Development => SharePoint simulado
```

Dar de alta un empleado:

```powershell
$h = @{ "X-Admin-Key" = "<ClaveAdmin>" }
Invoke-RestMethod -Method Post http://localhost:5080/api/admin/empleados -Headers $h -ContentType application/json `
  -Body '{"correo":"empleado@gmail.com","nombre":"Nombre Apellido","idUsuarioSharePoint":"PRUEBA-APK-001"}'
```

### Modo real en local (contra SharePoint)

`dotnet user-secrets set "SharePoint:Modo" "Real"`. Requiere haber completado la Fase A.

### Endpoints

| Método | Ruta | Uso |
|---|---|---|
| POST | `/api/auth/google` `{idToken}` | Login con Google y emisión de sesión |
| POST | `/api/auth/dev` `{correo}` | Login de prueba. Solo funciona en Development y con `PermitirLoginDesarrollo=true` |
| POST | `/api/auth/logout` | Revoca la sesión |
| GET | `/api/asistencia/perfil` | Nombre y tipos de trabajo |
| GET | `/api/asistencia/estado?tipoTrabajo=` | SIN_INGRESO / EN_JORNADA / JORNADA_CERRADA |
| POST | `/api/asistencia/ingreso` · `/salida` | Cabecera `Idempotency-Key` y cuerpo `{tipoTrabajo, lat, lon, accuracyMeters}` |
| GET/POST | `/api/admin/empleados` | Alta y modificación de empleados (cabecera `X-Admin-Key`) |
| POST | `/api/admin/empleados/{id}/desvincular-google` | Desvincula la cuenta Google y revoca sus sesiones |
| GET | `/api/admin/operaciones?estado=UbicacionPendiente` | Bitácora y conciliación |
| GET | `/api/admin/auditoria?top=100` | Auditoría (LOGIN / INGRESO / SALIDA con GOOD/BAD) |

## 3. Google Sign-In (Google Cloud Console)

1. Cree un proyecto, configure la **pantalla de consentimiento OAuth** (externo, con los scopes `email`/`profile`) y publíquela.
2. Cree un cliente OAuth de tipo **Web application**. Su Client ID va en:
   - `android/gradle.properties`, propiedad `vepagas.googleWebClientId`;
   - el backend, en `Auth:GoogleClientIds` (arreglo).
3. Cree un cliente OAuth de tipo **Android** con el paquete `com.vepagas.asistencia` y el **SHA-1** del keystore de release. Para el build debug cree otro con `com.vepagas.asistencia.debug` y el SHA-1 de debug:
   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android
   ```
   El cliente Android no se referencia en el código, pero Google lo exige para emitir el token.

## 4. App Android

- Abra la carpeta `android/` en Android Studio. Use el JDK incluido (JBR).
- Configure `android/gradle.properties`:
  - `vepagas.apiUrlDebug`: queda en `http://127.0.0.1:5080/api/`. Antes de abrir la app, ejecute `adb reverse tcp:5080 tcp:5080`; sirve para el emulador y para un teléfono conectado por USB. Sin cable, use `http://<IP-LAN-del-PC>:5080/api/` y abra el puerto 5080 en el firewall.
  - `vepagas.apiUrlRelease`: la URL **HTTPS** pública del backend.
- El build debug muestra el botón **"Ingreso de prueba (sin Google)"**, que funciona con el backend en Development. El build release no lo incluye, y además solo permite HTTPS.

Compilar por línea de comandos:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease    # requiere android\keystore.properties para firmar
```

APK release firmada: cree el keystore una sola vez y **guárdelo con respaldo**, porque sin él no se pueden publicar actualizaciones:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore vepagas-release.jks -alias vepagas -keyalg RSA -keysize 2048 -validity 10000
```

Luego cree `android/keystore.properties` (ignorado por git):

```properties
storeFile=../vepagas-release.jks
storePassword=...
keyAlias=vepagas
keyPassword=...
```

Instalar en el teléfono: `adb install -r app\build\outputs\apk\release\app-release.apk`, o copie el archivo al teléfono y habilite "instalar apps desconocidas".

## 5. Publicación del backend (IIS)

```powershell
cd backend\Vepagas.Asistencia.Api
dotnet publish -c Release -o .\publish
```

1. Instale el **ASP.NET Core Hosting Bundle 10** en el servidor y cree un sitio con un App Pool "No Managed Code" que apunte a `publish\`.
2. Publique el sitio por **HTTPS** con un certificado válido. Android rechaza certificados autofirmados.
3. Asigne a la identidad del App Pool permiso de escritura sobre `publish\App_Data` (allí vive la base SQLite).
4. Configure los secretos como **variables de entorno** del App Pool o en la sección `<environmentVariables>` del `web.config`, nunca en `appsettings.json`. Use `__` como separador:
   `Graph__TenantId`, `Graph__ClientId`, `Graph__ClientSecret`, `SharePoint__SiteId`, `SharePoint__ListaRegistrosId`, `SharePoint__ListaUbicacionesId`, `Auth__ClaveFirmaJwt`, `Auth__ClaveAdmin`, `Auth__GoogleClientIds__0`.
5. `ASPNETCORE_ENVIRONMENT=Production`. En Production el backend **se niega a arrancar** si `SharePoint:Modo` es `Simulado`, y el login de prueba queda deshabilitado.
6. El App Pool debe estar configurado para no reciclarse a mitad de la jornada (o con *AlwaysRunning*), para que el conciliador siga activo.

## 6. Prueba paso a paso (modo simulado, emulador)

El flujo de pantallas replica al de Power Apps: **Tele trabajo** → **¡Bienvenido!** (Ingreso / salida) → **Registro de Ingreso** o **Registro de Salida y Actividades** (Usuario, Fecha, Ubicación lon/lat) → **Timbrar**.

1. Arranque el backend (sección 2), dé de alta `prueba@gmail.com` y ejecute `adb reverse tcp:5080 tcp:5080`.
2. Abra la app debug y fije una ubicación desde *Extended controls > Location* (o con `adb emu geo fix -78.4678 -0.1807`).
3. Use "Ingreso de prueba" con `prueba@gmail.com`, luego **Tele trabajo** y luego **Ingreso**. La pantalla muestra lon/lat. Pulse **Timbrar**: la app vuelve a la bienvenida con "Se registró el ingreso correctamente".
4. Entre otra vez a **Ingreso** y timbre: debe aparecer "Su ingreso ya fue registrado".
5. Entre a **salida** y timbre: debe aparecer "Se registró la salida correctamente". Un segundo intento muestra "Su salida ya fue registrada".
6. Apague el backend y timbre: aparece el aviso de "timbrada sin confirmar". Encienda el backend y timbre de nuevo: la operación se reanuda sin duplicar.
7. Revise `GET /api/admin/auditoria` y `GET /api/admin/operaciones`.

Los pasos 1 a 5 se probaron en el emulador (API 37) contra el backend en modo simulado. Por API también se probaron la idempotencia, el rechazo por GPS impreciso y el logout con revocación.

## 7. Pendiente antes de producción

- [ ] Fase A ejecutada contra SharePoint real: columnas, tipos y prueba de escritura con un empleado de prueba.
- [ ] Valores reales de `TIPO_TRABAJO`.
- [ ] Decidir qué valor va en `ID_USUARIO` para empleados Gmail. ¿Tienen Entra Object ID como invitados, o un identificador corporativo? Debe coincidir con lo que lee Power Apps y los reportes.
- [ ] Hosting HTTPS del backend y URL definitiva en `vepagas.apiUrlRelease`.
- [ ] Clientes OAuth de Google (Web + Android con el SHA-1 de release).
- [ ] Licenciamiento Microsoft para el acceso por Graph con permisos de aplicación; de preferencia `Sites.Selected` limitado al sitio.
- [ ] Política de privacidad de geolocalización (LOPDP) y aprobación de RR. HH./TI.
- [ ] Fuera del MVP: `dispositivos_db` (vincular el dispositivo con una clave del Keystore y aprobación de TI), escritura en `sesion_db`, turnos que cruzan la medianoche, Ruta Comercial.
