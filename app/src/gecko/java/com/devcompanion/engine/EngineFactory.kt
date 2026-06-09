package com.devcompanion.engine

import android.content.Context
import com.devcompanion.debug.BrowserDebugger
import com.devcompanion.debug.NoOpDebugger
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Factory to create the browser engine for the gecko flavor (GeckoView).
 *
 * The [debugger] parameter is accepted for API parity with the free flavor
 * but is not used — GeckoView has its own debugging tools.
 */
object EngineFactory {
    private var runtime: GeckoRuntime? = null

    /** Create the flavor-appropriate debugger instance (no-op for GeckoView). */
    fun createDebugger(): BrowserDebugger = NoOpDebugger()

    fun create(context: Context, debugger: BrowserDebugger? = null): BrowserEngine {
        val rt = runtime ?: GeckoRuntime.create(context.applicationContext).also {
            runtime = it
        }
        val session = GeckoSession().apply {
            settings.useTrackingProtection = true
            settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            open(rt)
        }
        val geckoView = GeckoView(context.applicationContext)
        geckoView.setSession(session)
        return GeckoEngine(geckoView, session)
    }
}