package com.devcompanion.llm

/**
 * Represents a single content block within an LLM message.
 *
 * Providers like Anthropic support multi-modal messages composed of
 * an ordered list of content blocks (text, images, etc.).
 * This sealed class abstracts those blocks for request construction.
 *
 * NOTE: Currently unused in adapters — vision content is built inline
 * in each adapter's buildRequestBody(). This class will be leveraged
 * in Phase 3 when multi-turn history and structured content are added.
 */
sealed class ContentBlock {

    /** A plain-text content block. */
    data class Text(val text: String) : ContentBlock()

    /**
     * An image content block carrying Base64-encoded image data.
     *
     * @param base64Data The raw Base64 string (no data-URI prefix).
     * @param mimeType   MIME type of the image (default: JPEG).
     */
    data class Image(
        val base64Data: String,
        val mimeType: String = "image/jpeg"
    ) : ContentBlock()
}