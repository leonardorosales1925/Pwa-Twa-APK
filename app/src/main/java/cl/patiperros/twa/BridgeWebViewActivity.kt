package cl.patiperros.twa

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Activity fallback que carga la PWA Patiperros en un WebView local con el
 * [AndroidBridge] inyectado vía `@JavascriptInterface`.
 *
 * Esta es la ruta efectiva por la que la web puede invocar el Foreground
 * Service nativo (LocationForegroundService) cuando emite los eventos
 * `WALK_STARTED` y `WALK_ENDED`:
 *
 *   ```js
 *   if (window.AndroidBridge?.onWalkStarted) {
 *       window.AndroidBridge.onWalkStarted();
 *   }
 *   ```
 *
 * La PWA puede detectar la presencia del bridge así:
 *   `if (typeof AndroidBridge !== 'undefined') { ... }`
 */
class BridgeWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val LOCATION_PERMISSIONS_REQUEST = 1001
        private const val BACKGROUND_LOCATION_REQUEST = 1002
        private const val NOTIFICATION_PERMISSION_REQUEST = 1003
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantener pantalla encendida mientras la activity esté visible —
        // refuerzo opcional para paseos. El verdadero "keep-alive" lo hace
        // el LocationForegroundService.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setGeolocationEnabled(true)
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = userAgentString + " PatiperrosTWA/1.0"
            }

            // Bridge JS → Kotlin
            addJavascriptInterface(
                AndroidBridge(this@BridgeWebViewActivity),
                AndroidBridge.JS_OBJECT_NAME
            )

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("patiperros://")) {
                        handlePatiperrosScheme(url)
                        return true
                    }
                    return false
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Mantener navegación dentro del host de la PWA.
                    return !url.startsWith("https://app.patiperros-talca.cl")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    // Permitimos siempre que la app ya tenga el permiso del SO.
                    val granted = ContextCompat.checkSelfPermission(
                        this@BridgeWebViewActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    callback?.invoke(origin, granted, false)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }
        setContentView(webView)

        // Solicitar permisos al arrancar.
        ensureLocationPermissions()

        val url = intent?.dataString
            ?: "https://app.patiperros-talca.cl/app?source=pwa-app&mode=standalone"
        webView.loadUrl(url)
    }

    private fun ensureLocationPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, needed.toTypedArray(), LOCATION_PERMISSIONS_REQUEST
            )
        } else {
            ensureBackgroundLocation()
        }
    }

    private fun ensureBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_LOCATION_REQUEST
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSIONS_REQUEST) {
            ensureBackgroundLocation()
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun handlePatiperrosScheme(url: String) {
        android.util.Log.i("BridgeWebView", "URL scheme interceptado: $url")
        val uri = android.net.Uri.parse(url)
        when (uri.host) {
            "walk-started" -> {
                val token = uri.getQueryParameter("token") ?: ""
                val walkId = uri.getQueryParameter("walk_id") ?: ""
                TwaPostMessageService.handleWalkMessage(
                    applicationContext,
                    "WALK_STARTED:$token:$walkId"
                )
            }
            "walk-ended" -> {
                TwaPostMessageService.handleWalkMessage(applicationContext, "WALK_ENDED")
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(AndroidBridge.JS_OBJECT_NAME)
            webView.destroy()
        }
        super.onDestroy()
    }
}
