package com.devcompanion.engine

/**
 * JS injections to apply on each page load.
 * Free flavor (WebView): all injections needed for rendering fixes.
 */
object InjectionConfig {
    /** Whether this flavor needs JS injection fixes (WebView does, GeckoView doesn't). */
    val needsInjections: Boolean = true

    /**
     * Whether the WebView heartbeat (JS freeze detection) should be active.
     * Only meaningful for WebView — GeckoView doesn't freeze from MutationObserver loops.
     */
    val needsHeartbeat: Boolean = true
}