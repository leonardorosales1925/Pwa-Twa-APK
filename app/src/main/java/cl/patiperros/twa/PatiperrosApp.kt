package cl.patiperros.twa

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application class de la TWA Patiperros.
 *
 * Responsabilidades:
 *  - Crear el canal de notificaciones para el [LocationForegroundService]
 *    al inicio del proceso. En Android 8+ (API 26+) los servicios en
 *    primer plano requieren un canal previamente registrado.
 */
class PatiperrosApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createWalkNotificationChannel()
    }

    private fun createWalkNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.walk_notif_channel_id),
                getString(R.string.walk_notif_channel_name),
                // IMPORTANCE_LOW: visible y persistente, sin sonido —
                // queremos que el sistema mantenga el proceso vivo pero sin
                // molestar al paseador con beeps cada vez que actualizamos
                // la notificación.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.walk_notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = NotificationManager.IMPORTANCE_DEFAULT
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
