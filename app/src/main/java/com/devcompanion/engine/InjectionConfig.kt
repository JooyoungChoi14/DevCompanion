package com.devcompanion.engine

/**
 * JS injections to apply on each page load.
 *
 * GeckoView only: no injections needed — Gecko engine handles everything natively.
 * - vh/dvh computed correctly → no VH_FIX
 * - Keyboard handling built-in → no KEYBOARD_FIX
 * - Overflow/scroll handled correctly → no OVERFLOW_FIX
 * - text-size-adjust via GeckoSettings → no TEXT_SIZE_FIX
 * - Autofill supported natively → no AUTOFILL_INJECTION
 * - No MutationObserver → no infinite loop freeze risk → no heartbeat needed
 */
object InjectionConfig {
    /** GeckoView never needs JS injections. */
    val needsInjections: Boolean = false

    /** GeckoView never needs heartbeat-based freeze detection. */
    val needsHeartbeat: Boolean = false
}