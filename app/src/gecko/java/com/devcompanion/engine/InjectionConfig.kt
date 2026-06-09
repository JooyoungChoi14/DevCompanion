package com.devcompanion.engine

/**
 * JS injections to apply on each page load.
 * Gecko flavor: NO injections needed — Gecko engine handles everything natively.
 *
 * - vh/dvh computed correctly → no VH_FIX
 * - Keyboard handling built-in → no KEYBOARD_FIX
 * - Overflow/scroll handled correctly → no OVERFLOW_FIX
 * - text-size-adjust via GeckoSettings → no TEXT_SIZE_FIX
 * - Autofill supported natively → no AUTOFILL_INJECTION
 * - No MutationObserver → no infinite loop freeze risk → no heartbeat needed
 *
 * Empty stubs are provided for compile-time compatibility only.
 * They are never executed because needsInjections=false and needsHeartbeat=false.
 */
object InjectionConfig {
    val needsInjections: Boolean = false
    val needsHeartbeat: Boolean = false

    // Stub constants — never used (needsInjections=false), but needed for compile compatibility
    val AUTOFILL_INJECTION = ""
    val VH_FIX_INJECTION = ""
    val TEXT_SIZE_FIX_INJECTION = ""
    val HEARTBEAT_INJECTION = ""
    val INSPECTOR_IFRAME_INJECTION = ""
}