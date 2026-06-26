# Patiperros TWA (Trusted Web Activity)

Aplicación Android nativa en Kotlin que envuelve la PWA de Patiperros (`https://app.patiperros-talca.cl`) usando el patrón **Trusted Web Activity** (TWA), añadiendo capacidades nativas que la PWA no puede tener por sí sola:

- **Servicio en primer plano (Foreground Service) con GPS** para registrar paseos aun con la pantalla apagada.
- **Puente JavaScript ↔ Kotlin** (`@JavascriptInterface`) para comunicación bidireccional con la PWA.
- **Notificación persistente** durante un paseo activo.

## Información del paquete

- **Package**: `cl.patiperros.twa`
- **Nombre app**: Patiperros
- **versionCode**: 1
- **versionName**: 1.0.0-debug
- **minSdk**: 26 (Android 8.0)
- **targetSdk** / **compileSdk**: 34 (Android 14)
- **URL host**: `app.patiperros-talca.cl`
- **Color de marca**: `#0496D7`

## Estructura del proyecto

```
PatiperrosTWA/
├── app/
│   ├── build.gradle                              # Config del módulo (deps + signing)
│   ├── proguard-rules.pro                        # Mantiene @JavascriptInterface
│   └── src/main/
│       ├── AndroidManifest.xml                   # Permisos, TWA, Service, FileProvider
│       ├── java/cl/patiperros/twa/
│       │   ├── PatiperrosApp.kt                  # Application + canal de notificación
│       │   ├── LauncherActivity.kt               # Lanza TWA (fallback a WebView)
│       │   ├── BridgeWebViewActivity.kt          # WebView con AndroidBridge inyectado
│       │   ├── AndroidBridge.kt                  # @JavascriptInterface (PWA ↔ Android)
│       │   └── LocationForegroundService.kt      # FusedLocation + notificación persistente
│       └── res/
│           ├── values/  (strings, colors, themes)
│           ├── drawable/  (splash, notif icon, launcher fg)
│           ├── mipmap-{m,h,x,xx,xxx}hdpi/  (íconos adaptivos)
│           └── xml/  (shortcuts, filepaths)
├── build.gradle                                  # AGP 8.2.2 + Kotlin 1.9.22
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/                               # Gradle 8.5
├── gradlew, gradlew.bat
├── local.properties                              # sdk.dir
├── keystore.properties                           # Credenciales de firma de release
├── keystore/
│   └── patiperros-release.jks                    # Keystore de PRODUCCIÓN (válido 27 años)
└── outputs/
    └── PatiperrosTWA-debug.apk                   # APK debug compilado y firmado
```

## Permisos declarados (AndroidManifest)

| Permiso | Propósito |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Carga de la PWA |
| `ACCESS_FINE_LOCATION` | GPS de alta precisión durante paseos |
| `ACCESS_COARSE_LOCATION` | Fallback de ubicación |
| `ACCESS_BACKGROUND_LOCATION` | GPS continuo cuando app no está al frente |
| `FOREGROUND_SERVICE` | Servicio activo con notificación |
| `FOREGROUND_SERVICE_LOCATION` | Tipo específico requerido en Android 14+ |
| `POST_NOTIFICATIONS` | Notificación persistente del paseo (Android 13+) |
| `WAKE_LOCK` | Mantener CPU activa durante el paseo |
| `RECEIVE_BOOT_COMPLETED` | (Reservado para reanudación tras reinicio) |

## Componentes Kotlin

### `PatiperrosApp.kt`
Crea el canal de notificaciones `walk_channel` con `IMPORTANCE_LOW` (sin sonido pero visible).

### `LauncherActivity.kt`
Activity de lanzamiento. Intenta abrir la PWA via **Custom Tabs / TWA**. Si Chrome / proveedor TWA no está disponible, hace fallback a `BridgeWebViewActivity`.

### `BridgeWebViewActivity.kt`
WebView nativo con JavaScript habilitado, geolocalización, almacenamiento DOM y el bridge inyectado. Solicita permisos de ubicación al iniciar.

### `AndroidBridge.kt`
Puente expuesto a la PWA bajo el nombre **`AndroidBridge`**. Métodos disponibles desde JavaScript:

```javascript
// Comprobar disponibilidad
window.AndroidBridge && window.AndroidBridge.isAvailable()  // true

// Iniciar GPS en background (cuando la PWA emite WALK_STARTED)
window.AndroidBridge.onWalkStarted();

// Detener GPS (cuando la PWA emite WALK_ENDED)
window.AndroidBridge.onWalkEnded();

// Metadata
window.AndroidBridge.getVersion();      // "1.0.0-debug"
window.AndroidBridge.getPackageName();  // "cl.patiperros.twa"
```

Internamente, cada método arranca/detiene `LocationForegroundService` enviando los actions `ACTION_WALK_STARTED` / `ACTION_WALK_ENDED`.

### `LocationForegroundService.kt`
- **Tipo**: `foregroundServiceType="location"` (cumple Android 14+).
- **Notificación**: Persistente, canal `walk_channel`, ícono `ic_walk_notification`.
- **GPS**: `FusedLocationProviderClient` con `Priority.PRIORITY_HIGH_ACCURACY`, intervalo **5 s**, intervalo mínimo 2 s.
- **Wake Lock**: `PARTIAL_WAKE_LOCK` adquirido al iniciar y liberado al detener.
- **Acciones**: `ACTION_WALK_STARTED` (start) y `ACTION_WALK_ENDED` (stop + cleanup).

## Recursos visuales

- **Color primario**: `#0496D7` (matches PWA `theme_color`).
- **Splash**: `drawable/splash.xml` con fondo de marca.
- **Íconos adaptivos**: 5 densidades (`mdpi`–`xxxhdpi`) con foreground vectorial.
- **Tema**: `Theme.Patiperros` con `colorPrimary` y `colorPrimaryDark`.

## Configuración TWA

En `AndroidManifest.xml` se declara:
- `android.support.customtabs.trusted.DEFAULT_URL` = `https://app.patiperros-talca.cl`
- `android.support.customtabs.trusted.STATUS_BAR_COLOR` = `@color/colorPrimary`
- `android.support.customtabs.trusted.SPLASH_IMAGE_DRAWABLE` = `@drawable/splash`
- Intent filter con `android.intent.action.VIEW` para deep links a `https://app.patiperros-talca.cl`.

> **Para producción**: hay que publicar el archivo `assetlinks.json` en `https://app.patiperros-talca.cl/.well-known/assetlinks.json` con la huella SHA-256 del **certificado de firma de release** (no la del keystore en sí). La huella del certificado release es:
>
> `05:93:E2:B9:F1:A7:86:F0:3D:54:50:88:17:E8:E8:B5:38:2B:CA:31:68:45:CC:68:C7:DA:E7:35:C1:71:D1:0E`

## Firma

### Debug (este APK)
Firmado con la `debug.keystore` estándar de Android (`~/.android/debug.keystore`, alias `androiddebugkey`).

### Producción (release)
Keystore generado en `keystore/patiperros-release.jks`:
- **Alias**: `patiperros`
- **Algoritmo**: RSA 2048 bits
- **Validez**: 10000 días (~27 años)
- **Credenciales** (en `keystore.properties`):
  - `storePassword=Patiperros2026!`
  - `keyPassword=Patiperros2026!`
- **SHA-256 archivo .jks**: `afea9432ce5bdba80cff0688a53e812c48d2c78142cda59d02dcf95f5ab30e44`
- **SHA-256 certificado** (para Digital Asset Links): `05:93:E2:B9:F1:A7:86:F0:3D:54:50:88:17:E8:E8:B5:38:2B:CA:31:68:45:CC:68:C7:DA:E7:35:C1:71:D1:0E`

## Compilación

### Build estándar con Gradle (recomendado en máquinas con ≥ 4 GB RAM)
```bash
./gradlew assembleDebug          # APK debug
./gradlew assembleRelease        # APK release firmado con keystore producción
```

### Build manual sin Gradle (utilizado en este sandbox por restricciones de memoria)
Pipeline ejecutado: `aapt2 compile` → `aapt2 link` → `javac` (R.java + BuildConfig.java) → `kotlinc` (clases Kotlin) → `d8` (dex) → `zip` (añadir classes.dex) → `zipalign` → `apksigner`.

## Integración del lado de la PWA

La PWA Patiperros ya emite `postMessage({ type: 'WALK_STARTED' })` y `WALK_ENDED`. Para que el bridge lo capte, basta agregar en el código de la PWA un wrapper que detecte el bridge y delegue:

```javascript
// En la PWA — al detectar inicio/fin de paseo
function dispatchWalkEvent(type) {
  // Web (notifica al SW si lo hay)
  navigator.serviceWorker?.controller?.postMessage({ type });

  // Android nativo (si está dentro del TWA)
  if (window.AndroidBridge && window.AndroidBridge.isAvailable && window.AndroidBridge.isAvailable()) {
    if (type === 'WALK_STARTED') window.AndroidBridge.onWalkStarted();
    if (type === 'WALK_ENDED')   window.AndroidBridge.onWalkEnded();
  }
}
```

## Resultados de la verificación

- `apksigner verify`: **Verifies** ✓
  - V2 scheme: ✓
  - V3 scheme: ✓
- `aapt dump badging`: paquete `cl.patiperros.twa`, todos los permisos requeridos presentes.
- Total archivos en APK: 441
- Tamaño APK debug: ~2.9 MB
- SHA-256 APK debug: `b1f833e69863eae09283a8a66df03770b62399e57d0b581e73fea9f32b7d524d`

## Próximos pasos sugeridos

1. Publicar `assetlinks.json` en el dominio para eliminar la barra de URL del TWA.
2. Integrar el snippet de `dispatchWalkEvent` en la PWA.
3. Generar APK release firmado: `./gradlew assembleRelease`.
4. Subir bundle a Play Console (`./gradlew bundleRelease`).
