# VEPAGAS Asistencia (APK Android, sin backend)

Contexto: [CONTEXTO_PROYECTO_VEPAGAS_ANDROID.md](CONTEXTO_PROYECTO_VEPAGAS_ANDROID.md).

```text
Empleado (Gmail, invitado en Entra)
  -> APK Kotlin/Compose ── inicio de sesión Microsoft (MSAL, sin secreto)
  -> Microsoft Graph con el token DEL EMPLEADO
  -> SharePoint "Desarrollo de Aplicaciones Power App":
       registros_tb, ubicacion_db, dispositivos_db, sesion_db, UBICACION_APP
GitHub (repo privado) ── push a main ──> Actions compila APK firmada
  -> Release en repo PÚBLICO vepagas-asistencia-releases -> la app ofrece "Actualizar"
```

- **Sin servidor ni API propia.** La app hace lo mismo que Power Apps, directo contra las listas.
- **Sin secretos en la APK.** Cada empleado entra con su propia cuenta (invitado del tenant) y la app escribe con **sus** permisos de SharePoint. `ID_USUARIO` es su Object ID, igual que `User().EntraObjectId`.
- **Hora oficial:** la cabecera `Date` de Microsoft, no el reloj del teléfono.

## Lógica (igual que Power Apps)

| Momento | Qué hace |
|---|---|
| Abrir la app (OnStart) | Revisa `dispositivos_db` (columna interna `ID_DISP` = ID_USER). **Sin fila:** la crea con `AUTH`. **Mismo teléfono:** OK. **Fila de Power Apps:** la pasa a este teléfono una sola vez y la marca con `Title="APK"`. **Otro teléfono:** bloquea. En todos los casos escribe `sesion_db` con `GOOD` o `BAD`. |
| Tele trabajo | Escribe `UBICACION_APP` (`"lat lon"` con coma decimal, y `NOMBRE`). |
| Ingreso | Un solo ingreso por usuario, día (Ecuador) y tipo `Home_Office`. Crea la fila en `registros_tb` (`d MMMM yyyy H:mm`) y luego la de `ubicacion_db` con el mismo GUID. |
| Salida | Exige ingreso previo y que no haya salida. Actualiza `FECHA_HORA_SALIDA` y agrega la segunda ubicación. |
| Sin conexión a mitad de una timbrada | El teléfono guarda la operación cifrada. Al reintentar, la retoma **sin duplicar**: antes de crear de nuevo busca la jornada por `ID_REG`. Si falta la ubicación, la envía al abrir la app. |

Para habilitar un teléfono nuevo, TI **borra la fila del empleado en `dispositivos_db`**.

## Configuración única

### 1. Entra ID (administrador del tenant)
1. **App registrations → New registration:** "VEPAGAS Asistencia APK", *Accounts in this organizational directory only*. Copie el **Application (client) ID** en `android/gradle.properties`, en `vepagas.msalClientId`.
2. **Authentication → Add a platform → Android**, dos veces:
   - Paquete `com.vepagas.asistencia`, hash de firma `+pqGoaywt0GyhvkUiYCuDVwnRUU=` (release).
   - Paquete `com.vepagas.asistencia.debug`, hash de firma `kBtdKwOzJJkPRXJJG0iJog8DWCM=` (debug de este PC).
3. **API permissions → Microsoft Graph → Delegated:** `User.Read` y `Sites.ReadWrite.All`, y luego **Grant admin consent**.
4. Los empleados Gmail deben ser **invitados** en el tenant (ya lo son) y tener permiso de edición en las 5 listas (lo tenían por Power Apps).

### 2. GitHub
- Cree el repo **público** `vepagas-asistencia-releases` (puede estar vacío).
- En el repo privado, en **Settings → Secrets and variables → Actions → Secrets**:
  - `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`. Están en `Documents\VepagasClaves\LEEME-CLAVES.txt`; **haga respaldo de esa carpeta**, porque sin la keystore no se pueden publicar actualizaciones.
  - `RELEASES_TOKEN`: un token *fine-grained* con **Contents: Read and write** solo sobre `vepagas-asistencia-releases`.

## Publicar una versión
Haga `git push` a `main` con cambios en `android/`. El workflow `.github/workflows/android.yml` compila la APK firmada (`1.0.<n>`) y crea el Release en el repo público. Para escribir notas, ejecútelo a mano desde **Actions → Run workflow**. Para obligar a actualizar, defina la variable `ACTUALIZACION_OBLIGATORIA=true`.

**Primera instalación** en cada teléfono: descargue `vepagas-asistencia.apk` del último Release y ábrala (Android pedirá permitir "instalar apps desconocidas"). Desde ahí, la app avisa sola cuando hay versiones nuevas.

## Desarrollo local
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat assembleDebug   # app\build\outputs\apk\debug\app-debug.apk
```
O abra `android/` en Android Studio y pulse ▶. El build debug usa el paquete `.debug` y la firma de depuración de este PC.

## Pendiente / fuera del MVP
- Índices en `registros_tb.ID_USUARIO` y `dispositivos_db.ID_DISP` antes de que esas listas superen 5 000 filas.
- Turnos que cruzan la medianoche, Ruta Comercial y política de privacidad (LOPDP).
