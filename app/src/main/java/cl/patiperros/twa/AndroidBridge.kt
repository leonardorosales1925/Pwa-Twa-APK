package cl.patiperros.twa

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Puente JavaScript ↔ Kotlin para la ruta WebView (BridgeWebViewActivity).
 *
 * Se inyecta como `window.AndroidBridge` dentro del WebView que carga la PWA
 * Patiperros. La PWA puede invocar:
 *
 *   - `AndroidBridge.onWalkStarted(token, walkId)` → arranca LocationForegroundService
 *   - `AndroidBridge.onWalkEnded()`               → detiene LocationForegroundService
 *   - `AndroidBridge.isAvailable()`               → para que la web detecte el bridge
 *   - `AndroidBridge.getVersion()`                → versión de la TWA nativa
 *
 * NOTA v75: onWalkStarted() ahora recibe token y walkId opcionales para que el
 * LocationForegroundService pueda enviar coordenadas directamente al backend.
 */
class AndroidBridge(private val context: Context) {

    companion object {
        const val JS_OBJECT_NAME = "AndroidBridge"
        private const val TAG = "AndroidBridge"
    }

    /**
     * La PWA llama este método cuando el paseador presiona "Iniciar Paseo".
     *
     * @param token  Token de autenticación del paseador (Bearer token).
     * @param walkId ID del paseo activo en el backend.
     *
     * Ambos parámetros son opcionales para mantener retrocompatibilidad.
     * Si no se pasan, el servicio intenta recuperarlos de SharedPreferences.
     */
    @JavascriptInterface
    fun onWalkStarted(token: String? = null, walkId: String? = null) {
        Log.i(TAG, "JS → onWalkStarted() token=${token?.take(8)}... walkId=$walkId")
        startLocationService(token, walkId)
    }

    /**
     * La PWA llama este método cuando el paseador presiona "Finalizar Paseo".
     */
    @JavascriptInterface
    fun onWalkEnded() {
        Log.i(TAG, "JS → onWalkEnded()")
        stopLocationService()
    }

    /**
     * Permite a la PWA detectar si está corriendo dentro de la TWA nativa.
     */
    @JavascriptInterface
    fun isAvailable(): Boolean = true

    /**
     * Devuelve la versión de la TWA nativa para diagnóstico desde la web.
     */
    @JavascriptInterface
    fun getVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Devuelve el package name — útil para integraciones de notifs push.
     */
    @JavascriptInterface
    fun getPackageName(): String = context.packageName

    // ------------------------------------------------------------------
    // Helpers privados
    // ------------------------------------------------------------------

    private fun startLocationService(token: String?, walkId: String?) {
        val intent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_WALK_STARTED
            token?.let  { if (it.isNotBlank()) putExtra(LocationForegroundService.EXTRA_AUTH_TOKEN, it) }
            walkId?.let { if (it.isNotBlank()) putExtra(LocationForegroundService.EXTRA_WALK_ID, it) }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo iniciar LocationForegroundService", t)
        }
    }

    private fun stopLocationService() {
        val intent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_WALK_ENDED
        }
        try {
            context.startService(intent)
            context.stopService(Intent(context, LocationForegroundService::class.java))
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo detener LocationForegroundService", t)
        }
    }
}
