package cl.patiperros.twa

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground Service que mantiene el GPS activo durante un paseo de
 * Patiperros, incluso con la pantalla bloqueada.
 *
 * Ciclo de vida:
 *  - ACTION_WALK_STARTED → startForeground() + requestLocationUpdates()
 *  - ACTION_WALK_ENDED   → removeLocationUpdates() + stopSelf()
 *
 * CORRECCIONES v75:
 *  1. HandlerThread dedicado para el LocationCallback (evita saturar el Main Looper).
 *  2. onNewLocation() envía las coordenadas directamente al backend via HTTP,
 *     funcionando con pantalla bloqueada sin depender del JS de Chrome.
 *  3. El token y walk_id se pasan como Intent extras al iniciar el servicio.
 *  4. Estado de paseo activo persiste en SharedPreferences para reinicio tras reboot.
 */
class LocationForegroundService : Service() {

    companion object {
        private const val TAG = "LocationFGService"

        const val ACTION_WALK_STARTED = "cl.patiperros.twa.WALK_STARTED"
        const val ACTION_WALK_ENDED   = "cl.patiperros.twa.WALK_ENDED"

        // Extras que debe incluir el Intent al iniciar el paseo
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"
        const val EXTRA_WALK_ID    = "extra_walk_id"

        private const val NOTIFICATION_ID = 4807

        // SharedPreferences key para persistir estado activo
        const val PREFS_NAME        = "patiperros_walk_state"
        const val PREF_WALK_ACTIVE  = "walk_active"
        const val PREF_AUTH_TOKEN   = "auth_token"
        const val PREF_WALK_ID      = "walk_id"

        // Endpoint del backend
        private const val LOCATION_ENDPOINT = "https://app.patiperros-talca.cl/api/location"

        // Frecuencia de actualizaciones de ubicación durante el paseo
        private const val UPDATE_INTERVAL_MS   = 5_000L  // 5 s
        private const val FASTEST_INTERVAL_MS  = 2_000L  // 2 s
        private const val MIN_DISPLACEMENT_M   = 3f      // 3 metros

        // Mínimo desplazamiento entre envíos HTTP (evitar duplicados idénticos)
        private const val MIN_HTTP_INTERVAL_MS = 4_000L  // 4 s entre posts
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    // Credenciales del paseo activo (llegadas por Intent extras)
    private var authToken: String? = null
    private var walkId: String? = null

    // Timestamp del último envío HTTP exitoso
    private var lastHttpSentTs = 0L

    // HandlerThread dedicado para el LocationCallback (CORRECCIÓN #3)
    private lateinit var locationHandlerThread: HandlerThread
    private lateinit var locationHandler: Handler

    // Coroutine scope para envíos HTTP no bloqueantes
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                onNewLocation(location)
            }
        }
    }

    // ------------------------------------------------------------------
    // Ciclo de vida del servicio
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // Crear HandlerThread dedicado para el GPS callback
        locationHandlerThread = HandlerThread("PatiperrosGPSThread").also { it.start() }
        locationHandler = Handler(locationHandlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WALK_STARTED -> {
                // Leer token y walk_id de los extras del Intent
                val token  = intent.getStringExtra(EXTRA_AUTH_TOKEN)
                val walkId = intent.getStringExtra(EXTRA_WALK_ID)
                startWalk(token, walkId)
            }
            ACTION_WALK_ENDED -> stopWalk()
            else -> {
                // El sistema reinició el servicio (START_STICKY o BootReceiver).
                // Intentamos restaurar desde SharedPreferences.
                val prefs = getWalkPrefs()
                if (prefs.getBoolean(PREF_WALK_ACTIVE, false)) {
                    val token  = prefs.getString(PREF_AUTH_TOKEN, null)
                    val walkId = prefs.getString(PREF_WALK_ID, null)
                    Log.i(TAG, "onStartCommand: restaurando paseo desde SharedPreferences")
                    startWalk(token, walkId)
                } else {
                    Log.d(TAG, "onStartCommand: no hay paseo activo en prefs, ignorando restart")
                    stopSelf()
                }
            }
        }
        // START_STICKY: si el sistema mata el servicio, lo reinicia con intent nulo.
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // startWalk / stopWalk
    // ------------------------------------------------------------------

    private fun startWalk(token: String?, wId: String?) {
        if (isRunning) {
            Log.d(TAG, "startWalk() ignorado — ya en ejecución")
            return
        }
        Log.i(TAG, "startWalk() token=${token?.take(8)}... walkId=$wId")

        // Actualizar credenciales (puede llegar null en restart; se usa lo que haya en prefs)
        if (!token.isNullOrBlank()) {
            authToken = token
            walkId = wId
            // Persistir para posibles reinicios
            getWalkPrefs().edit().apply {
                putBoolean(PREF_WALK_ACTIVE, true)
                putString(PREF_AUTH_TOKEN, token)
                putString(PREF_WALK_ID, wId)
                apply()
            }
        } else {
            // Restaurar de prefs (caso BootReceiver / START_STICKY)
            val prefs = getWalkPrefs()
            authToken = prefs.getString(PREF_AUTH_TOKEN, null)
            walkId    = prefs.getString(PREF_WALK_ID, null)
        }

        // 1) Foreground service con tipo "location" (requerido en Android 14+)
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2) Wake lock parcial
        acquireWakeLock()

        // 3) Verificar permiso de ubicación
        if (!hasLocationPermission()) {
            Log.w(TAG, "startWalk() — sin permiso de ubicación; deteniendo servicio")
            stopSelf()
            return
        }

        // 4) Suscripción al FusedLocationProviderClient en HandlerThread dedicado
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedClient.requestLocationUpdates(
                request,
                locationCallback,
                locationHandlerThread.looper   // ← HandlerThread dedicado (CORRECCIÓN #3)
            )
            isRunning = true
            Log.i(TAG, "GPS suscrito en HandlerThread '${locationHandlerThread.name}'")
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException al pedir updates de ubicación", se)
            stopSelf()
        }
    }

    private fun stopWalk() {
        Log.i(TAG, "stopWalk() — deteniendo GPS y foreground")

        // Limpiar estado persistido
        getWalkPrefs().edit().apply {
            putBoolean(PREF_WALK_ACTIVE, false)
            remove(PREF_AUTH_TOKEN)
            remove(PREF_WALK_ID)
            apply()
        }
        authToken = null
        walkId    = null

        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "removeLocationUpdates falló", t)
        }
        releaseWakeLock()
        isRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ------------------------------------------------------------------
    // onNewLocation — envío HTTP directo al backend (CORRECCIÓN #1)
    // ------------------------------------------------------------------

    /**
     * Recibe cada fix GPS (se ejecuta en el HandlerThread dedicado).
     *
     * Si hay un paseo activo y el token está disponible, envía las
     * coordenadas directamente al backend via POST HTTP. Esto funciona
     * incluso con la pantalla bloqueada porque este código corre en
     * Kotlin nativo, no en el JS de Chrome que queda congelado.
     */
    private fun onNewLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy
        Log.d(
            TAG,
            "fix lat=${"%.6f".format(lat)} lng=${"%.6f".format(lng)} acc=${acc}m " +
                "token=${authToken?.take(8)}... walkId=$walkId"
        )

        val token = authToken
        val wId   = walkId

        // Solo enviamos si tenemos credenciales y respetamos el intervalo mínimo
        if (token.isNullOrBlank()) {
            Log.v(TAG, "onNewLocation: sin token — skip HTTP")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHttpSentTs < MIN_HTTP_INTERVAL_MS) {
            Log.v(TAG, "onNewLocation: intervalo mínimo no alcanzado — skip HTTP")
            return
        }
        lastHttpSentTs = now

        // Envío asíncrono en coroutine IO (no bloqueamos el HandlerThread del GPS)
        serviceScope.launch {
            uploadLocation(token, wId, lat, lng, acc, location.time)
        }
    }

    /**
     * Hace POST a /api/location con las coordenadas del fix GPS.
     * Usa HttpURLConnection (sin dependencia adicional).
     *
     * Body JSON:
     * {
     *   "walk_id": "...",
     *   "lat": 0.0,
     *   "lng": 0.0,
     *   "accuracy": 0.0,
     *   "timestamp": 0,
     *   "source": "native_service"
     * }
     */
    private fun uploadLocation(
        token: String,
        walkId: String?,
        lat: Double,
        lng: Double,
        acc: Float,
        ts: Long
    ) {
        var conn: HttpURLConnection? = null
        try {
            val body = buildString {
                append("{")
                if (!walkId.isNullOrBlank()) append("\"walk_id\":\"$walkId\",")
                append("\"latitude\":$lat,")
                append("\"longitude\":$lng,")
                append("\"accuracy\":$acc,")
                append("\"timestamp\":$ts,")
                append("\"source\":\"native_service\"")
                append("}")
            }

            conn = (URL(LOCATION_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Native-Client", "PatiperrosTWA")
                connectTimeout = 10_000
                readTimeout    = 10_000
                doOutput       = true
                doInput        = false
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }

            val code = conn.responseCode
            if (code in 200..299) {
                Log.d(TAG, "uploadLocation OK ($code) lat=${"%.5f".format(lat)} lng=${"%.5f".format(lng)}")
            } else {
                Log.w(TAG, "uploadLocation respuesta $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadLocation error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Notificación persistente
    // ------------------------------------------------------------------

    private fun buildOngoingNotification(): Notification {
        val launcherIntent = Intent(this, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val contentIntent = PendingIntent.getActivity(
            this, 0, launcherIntent, pendingFlags
        )

        return NotificationCompat.Builder(
            this, getString(R.string.walk_notif_channel_id)
        )
            .setSmallIcon(R.drawable.ic_walk_notification)
            .setContentTitle(getString(R.string.walk_notif_title))
            .setContentText(getString(R.string.walk_notif_text))
            .setTicker(getString(R.string.walk_notif_ticker))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    // ------------------------------------------------------------------
    // Wake lock
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PatiperrosTWA::WalkWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6 h timeout de seguridad
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "releaseWakeLock falló", t)
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun getWalkPrefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy()")
        serviceScope.cancel()
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Throwable) { /* ignore */ }
        releaseWakeLock()
        // Parar el HandlerThread del GPS limpiamente
        locationHandlerThread.quitSafely()
        super.onDestroy()
    }
}
