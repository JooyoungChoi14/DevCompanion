# DevCompanion ProGuard rules

# ── Kotlin basics ──
-keepattributes Signature,RuntimeVisibleAnnotations,*Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Gson ──
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ── JavascriptInterface (WebView bridge) ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── DevCompanion data classes (Gson serialization) ──
-keep class com.devcompanion.debug.InspectorTarget { *; }
-keep class com.devcompanion.debug.BoundingRect { *; }
-keep class com.devcompanion.debug.ConsoleItem { *; }
-keep class com.devcompanion.debug.ConsoleItem$* { *; }
-keep class com.devcompanion.llm.ChatMessage { *; }
-keep class com.devcompanion.llm.ConversationExport { *; }
-keep class com.devcompanion.llm.ConversationMeta { *; }
-keep class com.devcompanion.data.Bookmark { *; }
-keep class com.devcompanion.data.UrlHistoryStore { *; }

# ── NanoHTTPD (reflection-based routing) ──
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class com.devcompanion.bridge.BridgeServer { *; }
-keep class com.devcompanion.bridge.BoreTunnel { *; }
-keep class com.devcompanion.bridge.BoreClientMessage { *; }
-keep class com.devcompanion.bridge.BoreClientMessage$* { *; }
-keep class com.devcompanion.bridge.BoreServerMessage { *; }
-keep class com.devcompanion.bridge.BoreServerMessage$* { *; }

# ── AndroidX ViewModel (reflection-based creation) ──
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
    *;
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
    *;
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel { *; }

# ── Compose ──
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── Kotlinx Coroutines & Flow ──
# Keep Flow internal delegate fields (NPE fix: ReadonlyStateFlow.$$delegate_0)
-keep class kotlinx.coroutines.flow.internal.** { *; }
-keep class kotlinx.coroutines.flow.SharedFlow { *; }
-keep class kotlinx.coroutines.flow.MutableSharedFlow { *; }
-keep class kotlinx.coroutines.flow.StateFlow { *; }
-keep class kotlinx.coroutines.flow.MutableStateFlow { *; }
-keep class kotlinx.coroutines.flow.ReadonlyStateFlow { *; }
-keep class kotlinx.coroutines.flow.SafeCollector { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── GeckoView ──
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.gecko.**

# ── DevCompanion core classes (Compose observes these via reflection) ──
-keep class com.devcompanion.DevCompanionApp { *; }
-keep class com.devcompanion.DevCompanionApp$* { *; }
-keep class com.devcompanion.ui.AiChatViewModel { *; }
-keep class com.devcompanion.ui.AiChatViewModel$* { *; }
-keep class com.devcompanion.ui.SettingsViewModel { *; }
-keep class com.devcompanion.ui.SettingsViewModel$* { *; }