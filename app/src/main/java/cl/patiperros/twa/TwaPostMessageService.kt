package cl.patiperros.twa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

class TwaPostMessageService : CustomTabsService() {

    companion object {
        private const val TAG = "TwaPostMessageSvc"
        private const val MSG_WALK_STARTED = "WALK_STARTED"
        private const val MSG_WALK_ENDED   = "WALK_ENDED"
        private const val MSG_SEPARATOR    = ":"

        fun handleWalkMessage(context: Context, message: String) {
            Log.i(TAG, "handleWalkMessage: '$message'")
            val parts  = message.split(MSG_SEPARATOR, limit = 3)
            val action = parts.getOrNull(0)?.trim() ?: return
            when (action) {
                MSG_WALK_STARTED -> {
                    val token  = parts.getOrNull(1)?.trim().takeIf { !it.isNullOrBlank() }
                    val walkId = parts.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() }
                    startLocationService(context, token, walkId)
                }
                MSG_WALK_ENDED -> stopLocationService(context)
                else -> Log.w(TAG, "Mensaje desconocido: $message")
            }
        }

        private fun startLocationService(context: Context, token: String?, walkId: String?) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = LocationForegroundService.ACTION_WALK_STARTED
                token?.let  { putExtra(LocationForegroundService.EXTRA_AUTH_TOKEN, it) }
                walkId?.let { putExtra(LocationForegroundService.EXTRA_WALK_ID, it) }
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

        private fun stopLocationService(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = LocationForegroundService.ACTION_WALK_ENDED
            }
            try { context.startService(intent) } catch (t: Throwable) {
                Log.e(TAG, "No se pudo detener LocationForegroundService", t)
            }
        }
    }

    override fun warmup(flags: Long): Boolean = true

    override fun newSession(session: CustomTabsSessionToken): Boolean = true

    override fun mayLaunchUrl(
        session: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: MutableList<Bundle>?
    ): Boolean = true

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(session: CustomTabsSessionToken, bundle: Bundle?): Boolean = false

    override fun requestPostMessageChannel(
        session: CustomTabsSessionToken,
        postMessageOrigin: Uri
    ): Boolean {
        Log.i(TAG, "requestPostMessageChannel: origin=$postMessageOrigin")
        return true
    }

    override fun postMessage(
        session: CustomTabsSessionToken,
        message: String,
        extras: Bundle?
    ): Int {
        Log.d(TAG, "postMessage: '$message'")
        handleWalkMessage(applicationContext, message)
        return CustomTabsService.RESULT_SUCCESS
    }

    override fun validateRelationship(
        session: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?
    ): Boolean = true

    override fun receiveFile(
        session: CustomTabsSessionToken,
        uri: Uri,
        purpose: Int,
        extras: Bundle?
    ): Boolean = false
}
