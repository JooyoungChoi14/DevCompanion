package com.devcompanion.debug

/**
 * Represents a DOM element selected via Inspector mode.
 * Populated by JS injection into the browser engine.
 */
data class InspectorTarget(
    val tagName: String,
    val id: String? = null,
    val className: String? = null,
    val xpath: String,
    val cssSelector: String,
    val textContent: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val boundingRect: BoundingRect? = null,
)

data class BoundingRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)