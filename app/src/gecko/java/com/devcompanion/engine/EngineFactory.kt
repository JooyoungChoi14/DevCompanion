package com.devcompanion.engine

import android.content.Context
import android.view.View
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Factory to create a GeckoView-based browser engine.
 * This is the gecko flavor implementation using GeckoView.
 *
 * GeckoView provides:
 * - Full browser engine (no dependency on system WebView)
 * - Proper overflow/scroll handling (fixes DuckDuckGo scroll issue)
 * - Consistent rendering across all Android devices
 * - Built-in tracking protection
 */
object EngineFactory {

    private var runtime: GeckoRuntime? = null

    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        return runtime ?: GeckoRuntime.create(context.applicationContext).also {
            runtime = it
        }
    }

    fun createGeckoView(context: Context): Pair<GeckoView, GeckoSession> {
        val geckoView = GeckoView(context.applicationContext)
        val session = GeckoSession()

        // Enable tracking protection
        session.settings.useTrackingProtection = true
        // Set mobile user agent
        session.settings.userAgentMode = GeckoSession.USER_AGENT_MODE_MOBILE

        val rt = getOrCreateRuntime(context)
        session.open(rt)

        geckoView.setSession(session)

        return Pair(geckoView, session)
    }

    fun createView(context: Context, block: GeckoView.(GeckoSession) -> Unit): View {
        val (geckoView, session) = createGeckoView(context)
        geckoView.block(session)
        return geckoView
    }
}