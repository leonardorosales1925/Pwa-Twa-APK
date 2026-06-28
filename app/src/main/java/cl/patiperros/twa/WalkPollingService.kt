package cl.patiperros.twa

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * WalkPollingService — Opción 2 de activación del GPS nativo.
 *
 * Hace polling cada 10 segundos a /api/walks?status=in_progress
 * para detectar si hay un paseo activo. Si lo detecta y el
 * LocationForegroundService no está corriendo, lo arranca.
 * Si no hay paseo activo y el servicio está corriendo, lo detiene.
 *
 * Esto funciona independientemente de Chrome PostMessage o URL scheme.
 */
object WalkPollingService {

    private const val TAG = "WalkPollingService"
    private const val POLL_INTERVAL_MS = 10_000L
    private const val API_BASE = "https://app.patiperros-talca.cl"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isPolling = false

    fun start(context: Context) {
        if (isPolling) return
        isPolling = true
        Log.i(TAG, "Polling iniciado")

        scope.launch {
            while (isActive) {
                try {
                    val prefs = context.getSharedPreferences(
                        LocationForegroundService.PREFS_NAME, Context.MODE_PRIVATE
                    )
                    val token = prefs.getString(LocationForegroundService.PREF_AUTH_TOKEN, null)

                    if (!token.isNullOrBlank()) {
                        val result = fetchActiveWalk(token)
                        val serviceRunning = prefs.getBoolean(LocationForegroundService.PREF_WALK_ACTIVE, false)

                        if (result != null && !serviceRunning) {
                            Log.i(TAG, "Polling detectó paseo activo — arrancando GPS")
                            val intent = Intent(context, LocationForegroundService::class.java).apply {
                                action = LocationForegroundService.ACTION_WALK_STARTED
                                putExtra(LocationForegroundService.EXTRA_AUTH_TOKEN, token)
                                putExtra(LocationForegroundService.EXTRA_WALK_ID, result)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        } else if (result == null && serviceRunning) {
                            Log.i(TAG, "Polling detectó fin de paseo — deteniendo GPS")
                            val intent = Intent(context, LocationForegroundService::class.java).apply {
                                action = LocationForegroundService.ACTION_WALK_ENDED
                            }
                            context.startService(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Consulta /api/walks?status=in_progress y retorna el walk_id
     * del paseo activo, o null si no hay ninguno.
     */
    private fun fetchActiveWalk(token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API_BASE/api/walks?status=in_progress").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Native-Client", "PatiperrosTWA")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().readText()
                // Buscar walk_id en el JSON de forma simple
                val match = Regex("\"id\"\\s*:\\s*(\\d+)").find(body)
                match?.groupValues?.get(1)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchActiveWalk error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
