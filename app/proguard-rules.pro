# DevCompanion ProGuard rules

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*

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

# Data classes used with Gson — must preserve field names for serialization
-keep class com.devcompanion.llm.ChatMessage { *; }
-keep class com.devcompanion.llm.ConversationExport { *; }
-keep class com.devcompanion.llm.ConversationMeta { *; }
-keep class com.devcompanion.data.Bookmark { *; }
-keep class com.devcompanion.data.UrlHistoryStore { *; }

# Gson TypeToken and generic type resolution
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**