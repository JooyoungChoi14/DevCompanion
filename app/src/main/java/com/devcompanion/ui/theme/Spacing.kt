package com.devcompanion.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Design token system for consistent spacing.
 * Based on a 4dp grid — all spacing values are multiples of 4.
 *
 * Usage: Spacing.xs, Spacing.sm, Spacing.md, etc.
 * Instead of hardcoding `8.dp` everywhere, use `Spacing.sm`.
 */
object Spacing {
    val xxl = 32.dp
    val xl = 24.dp
    val lg = 16.dp
    val md = 12.dp
    val sm = 8.dp
    val xs = 4.dp
    val xxs = 2.dp
}

/**
 * Design tokens for sizing — icon sizes, component heights, etc.
 */
object Sizing {
    val iconSmall = 14.dp
    val iconDefault = 24.dp
    val iconLarge = 32.dp
    val tagHeight = 24.dp
    val tagWidth = 48.dp
}

/**
 * Typography scale — monospace defaults for code/console content.
 * Extend MaterialTheme.typography with code-specific styles.
 */
object CodeTypography {
    // Used in console, network, inspector panels
}