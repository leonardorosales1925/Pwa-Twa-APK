# Reglas ProGuard para PatiperrosTWA
# Mantener clases públicas expuestas a JavaScript via @JavascriptInterface
-keepattributes JavascriptInterface
-keepattributes *Annotation*

-keep class cl.patiperros.twa.** { *; }
-keepclassmembers class cl.patiperros.twa.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# TWA / Custom Tabs
-keep class androidx.browser.** { *; }

# Play services location
-keep class com.google.android.gms.location.** { *; }
