package cl.patiperros.twa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — reinicia el LocationForegroundService tras un reinicio del dispositivo.
 *
 * CORRECCIÓN #4:
 * Cuando el dispositivo se reinicia mientras hay un paseo activo, el sistema
 * Android mata todos los servicios. Este BroadcastReceiver escucha el broadcast
 * BOOT_COMPLETED y, si SharedPreferences indica que había un paseo activo,
 * vuelve a arrancar el LocationForegroundService con las credenciales persistidas.
 *
 * Requiere permiso RECEIVE_BOOT_COMPLETED en el AndroidManifest.xml (ya declarado).
 *
 * También maneja MY_PACKAGE_REPLACED para rearrancar tras una actualización de la app.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "onReceive: action=$action — verificando estado de paseo")
                checkAndRestoreWalk(context)
            }
            else -> {
                Log.d(TAG, "onReceive: action=$action ignorada")
            }
        }
    }

    /**
     * Verifica si había un paseo activo guardado en SharedPreferences y,
     * de ser así, reinicia el LocationForegroundService.
     */
    private fun checkAndRestoreWalk(context: Context) {
        val prefs = context.getSharedPreferences(
            LocationForegroundService.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val walkActive = prefs.getBoolean(LocationForegroundService.PREF_WALK_ACTIVE, false)

        if (!walkActive) {
            Log.d(TAG, "No había paseo activo — nada que restaurar")
            return
        }

        val token  = prefs.getString(LocationForegroundService.PREF_AUTH_TOKEN, null)
        val walkId = prefs.getString(LocationForegroundService.PREF_WALK_ID, null)

        Log.i(
            TAG,
            "Paseo activo detectado tras reboot — " +
            "token=${token?.take(8)}... walkId=$walkId — reiniciando servicio"
        )

        val serviceIntent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_WALK_STARTED
            token?.let   { putExtra(LocationForegroundService.EXTRA_AUTH_TOKEN, it) }
            walkId?.let  { putExtra(LocationForegroundService.EXTRA_WALK_ID, it) }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "LocationForegroundService reiniciado exitosamente tras reboot")
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo reiniciar LocationForegroundService tras reboot", t)
            // Limpiar estado corrupto para no intentar repetidamente si hay error
            prefs.edit().putBoolean(LocationForegroundService.PREF_WALK_ACTIVE, false).apply()
        }
    }
}
