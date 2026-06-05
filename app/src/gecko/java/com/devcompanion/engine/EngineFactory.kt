package com.devcompanion.engine

import android.content.Context
import android.view.View
import android.webkit.WebView
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Factory to create the browser View for the gecko flavor (GeckoView).
 */
object EngineFactory {
    private var runtime: GeckoRuntime? = null

    fun createGeckoView(context: Context): Triple<GeckoView, GeckoSession, GeckoRuntime> {
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
        return Triple(geckoView, session, rt)
    }
}