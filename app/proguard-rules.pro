# DevCompanion ProGuard rules

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*

# CDP event models
-keep class com.devcompanion.cdp.** { *; }

# JavascriptInterface bridge methods must be kept
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# InspectorTarget and related data classes
-keep class com.devcompanion.debug.InspectorTarget { *; }
-keep class com.devcompanion.debug.BoundingRect { *; }

# ConsoleItem sealed class
-keep class com.devcompanion.debug.ConsoleItem { *; }
-keep class com.devcompanion.debug.ConsoleItem$* { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# NanoHTTPD — uses reflection for route handling
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# BridgeServer — all public methods accessed by NanoHTTPD routing
-keep class com.devcompanion.bridge.BridgeServer { *; }

# BoreTunnel — protocol data classes
-keep class com.devcompanion.bridge.BoreTunnel { *; }
-keep class com.devcompanion.bridge.BoreClientMessage { *; }
-keep class com.devcompanion.bridge.BoreClientMessage$* { *; }
-keep class com.devcompanion.bridge.BoreServerMessage { *; }
-keep class com.devcompanion.bridge.BoreServerMessage$* { *; }