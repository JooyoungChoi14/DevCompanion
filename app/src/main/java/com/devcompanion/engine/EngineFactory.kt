package com.devcompanion.engine

import android.content.Context
import android.util.Log
import com.devcompanion.debug.BrowserDebugger
import com.devcompanion.debug.NoOpDebugger
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Factory to create the browser engine (GeckoView).
 *
 * GeckoView handles rendering natively, eliminating the need for JS injections.
 * DevTools are not yet supported — NoOpDebugger is returned.
 *
 * The ResourceCollector WebExtension is registered on the GeckoRuntime
 * (not per-session) and must be installed before any session is opened.
 */
object EngineFactory {
    private const val TAG = "EngineFactory"
    private var runtime: GeckoRuntime? = null
    private var resourceCollector: ResourceCollector? = null
    private var extensionRegistered = false

    /** Create the debugger instance (no-op for GeckoView). */
    fun createDebugger(): BrowserDebugger = NoOpDebugger()

    fun create(context: Context, debugger: BrowserDebugger? = null): BrowserEngine {
        val rt = runtime ?: GeckoRuntime.create(context.applicationContext).also {
            runtime = it
        }

        // Register the resource monitor WebExtension on the runtime.
        // This must happen before any session is opened so that webRequest
        // listeners are active when navigation begins.
        val collector = resourceCollector ?: ResourceCollector(rt).also { rc ->
            resourceCollector = rc
        }

        if (!extensionRegistered) {
            // Synchronous registration — installBuiltIn returns GeckoResult but
            // we optimistically proceed. If it fails, collectPageResources()
            // will fall back to the Performance API.
            try {
                collector.register()
                extensionRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "WebExtension registration failed, will use Performance API fallback", e)
            }
        }

        val session = GeckoSession().apply {
            settings.useTrackingProtection = true
            settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            open(rt)
        }
        val geckoView = GeckoView(context.applicationContext)
        geckoView.setSession(session)
        return GeckoEngine(geckoView, session, collector)
    }
}