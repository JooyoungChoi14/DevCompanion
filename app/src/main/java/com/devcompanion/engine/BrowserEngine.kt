package com.devcompanion.engine

/**
 * Abstraction over browser engine implementations.
 *
 * Currently minimal — InjectionConfig controls flavor-specific JS injection behavior.
 * Future: full engine abstraction when BrowserTab is refactored to use this interface
 * instead of directly referencing WebView.
 *
 * GeckoView eliminates the need for JS injection fixes:
 * - vh/dvh computed correctly → VH_FIX unnecessary
 * - Keyboard handling built-in → KEYBOARD_FIX unnecessary
 * - Overflow/scroll handled correctly → OVERFLOW_FIX unnecessary
 * - text-size-adjust controllable via GeckoSettings → TEXT_SIZE_FIX unnecessary
 * - Autofill supported natively → AUTOFILL_INJECTION unnecessary
 * - No MutationObserver → no infinite loop freeze risk
 */