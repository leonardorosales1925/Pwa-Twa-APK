package cl.patiperros.twa

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.trusted.TrustedWebActivityIntentBuilder

class LauncherActivity : AppCompatActivity() {

    companion object {
        const val PWA_URL =
            "https://app.patiperros-talca.cl/app?source=pwa-app&mode=standalone"
    }

    private var customTabsClient: CustomTabsClient? = null
    private var pendingUrl: String? = null
    private var channelReady = false

    private val serviceConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            customTabsClient = client
            client.warmup(0)
            pendingUrl?.let { launchWithClient(it, client) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            customTabsClient = null
        }
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Iniciar polling como mecanismo de respaldo para detectar paseos activos
        WalkPollingService.start(applicationContext)

        val url = intent?.dataString?.takeIf {
            it.startsWith("https://app.patiperros-talca.cl")
        } ?: PWA_URL

        val browserPackage = resolveBrowserPackage()

        if (browserPackage != null) {
            pendingUrl = url
            val bound = CustomTabsClient.bindCustomTabsService(this, browserPackage, serviceConnection)
            if (!bound) {
                fallbackToWebView(url)
            }
        } else {
            fallbackToWebView(url)
        }
    }

    private fun launchWithClient(url: String, client: CustomTabsClient) {
        try {
            // Callback que escucha el aviso de Chrome "el canal PostMessage ya está
            // listo" (onMessageChannelReady) y lo reenvía a la PWA con el mensaje
            // TWA_CHANNEL_READY. Sin este aviso, la PWA nunca sabe que puede empezar
            // a enviar WALK_STARTED / WALK_ENDED y el envío de ubicación nunca arranca.
            var activeSession: CustomTabsSession? = null
            val callback = object : CustomTabsCallback() {
                override fun onMessageChannelReady(extras: Bundle?) {
                    activeSession?.postMessage("TWA_CHANNEL_READY", null)
                    channelReady = true
                    finish()
                }
            }

            val session = client.newSession(callback)
            if (session == null) {
                fallbackToWebView(url)
                finish()
                return
            }
            activeSession = session
            session.mayLaunchUrl(Uri.parse(url), null, null)

            session.requestPostMessageChannel(
                Uri.parse("https://app.patiperros-talca.cl")
            )

            val twaIntent = TrustedWebActivityIntentBuilder(Uri.parse(url))
                .build(session)

            startActivity(twaIntent.intent)

            // Timeout de seguridad: si onMessageChannelReady no llega en 6s, finish de todas formas
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!channelReady) finish()
            }, 6000)
        } catch (t: Throwable) {
            fallbackToWebView(url)
            finish()
        }
    }

    private fun fallbackToWebView(url: String) {
        startActivity(
            Intent(this, BridgeWebViewActivity::class.java).apply {
                data = Uri.parse(url)
            }
        )
        finish()
    }

    private fun resolveBrowserPackage(): String? {
        val pm = packageManager
        val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                testIntent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(testIntent, 0)
        }
        val preferred = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.brave.browser",
            "com.microsoft.emmx"
        )
        for (pkg in preferred) {
            if (resolveInfos.any { it.activityInfo.packageName == pkg }) return pkg
        }
        return resolveInfos.firstOrNull()?.activityInfo?.packageName
    }

    override fun onDestroy() {
        super.onDestroy()
        // No se desvincula el servicio aquí: si se hace inmediatamente después de
        // finish(), se corta la conexión con Chrome antes de que el aviso de canal
        // listo (onMessageChannelReady) llegue a la app, y la PWA nunca recibe
        // TWA_CHANNEL_READY. El sistema libera la conexión junto con el proceso.
    }
}
