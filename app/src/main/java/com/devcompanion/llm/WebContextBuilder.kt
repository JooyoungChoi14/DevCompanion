package com.devcompanion.llm

import android.util.Base64
import android.util.Log
import com.devcompanion.engine.BrowserEngine
import android.graphics.Bitmap

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException



/**
 * Determines how much page context to capture alongside the screenshot.
 *
 * - [Quick]: Screenshot only (lowest overhead)
 * - [Standard]: Screenshot + key DOM metadata (interactive elements, forms)
 * - [Full]: Screenshot + full DOM dump + computed styles
 */
enum class CaptureMode {
    Quick,
    Standard,
    Full
}

/**
 * Immutable data packet representing a captured web page context.
 */
data class WebContextPacket(
    val url: String,
    val title: String,
    val screenshotBase64: String,
    val screenshotMimeType: String = "image/jpeg",
    val captureMode: CaptureMode,
    val domSnapshot: String = "",          // HTML snippet or key DOM metadata
    val computedStyles: String = "",       // Relevant computed style summary
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Captures a browser engine's state as a [WebContextPacket].
 *
 * This is a standalone function rather than a class to avoid holding
 * a long-lived engine reference, which would leak the Activity context.
 * Callers should pass the engine only for the duration of the capture.
 *
 * Only [CaptureMode.Quick] is implemented in Phase 1A;
 * [CaptureMode.Standard] and [CaptureMode.Full] will be added later.
 */
object WebContextBuilder {

    private const val TAG = "WebContextBuilder"
    private const val TARGET_WIDTH = 800
    private const val JPEG_QUALITY = 80
    private const val MAX_DOM_LENGTH = 4000

    // Track injected style IDs to prevent duplicates
    private val injectedStyleIds = mutableSetOf<String>()

    /**
     * JavaScript that extracts a sanitized DOM snapshot.
     *
     * Captures key structural elements (headings, links, forms, buttons,
     * inputs, images, meta tags) while stripping passwords, auth tokens,
     * and sensitive attribute values.
     */
    private val JS_EXTRACT_DOM = """
        (function() {
            var result = [];
            var seen = new Set();
            var MAX = 4000;
            var len = 0;

            function add(tag, info) {
                if (len + info.length > MAX) return false;
                result.push('<' + tag + ' ' + info + '>');
                len += info.length + tag.length + 4;
                return true;
            }

            // Title & meta
            add('title', 'text="' + (document.title || '').substring(0, 200) + '"');
            document.querySelectorAll('meta[name], meta[property]').forEach(function(m) {
                add('meta', 'name="' + (m.getAttribute('name') || m.getAttribute('property') || '') + '" content="' + (m.getAttribute('content') || '').substring(0, 200) + '"');
            });

            // Headings
            document.querySelectorAll('h1,h2,h3').forEach(function(h) {
                add(h.tagName.toLowerCase(), 'text="' + h.textContent.substring(0, 100) + '"');
            });

            // Links
            document.querySelectorAll('a[href]').forEach(function(a) {
                var href = a.getAttribute('href');
                if (!href || href.startsWith('javascript:')) return;
                add('a', 'href="' + href.substring(0, 200) + '" text="' + a.textContent.substring(0, 80) + '"');
            });

            // Forms & inputs (mask passwords!)
            document.querySelectorAll('form').forEach(function(f) {
                var action = (f.getAttribute('action') || '').substring(0, 200);
                var method = f.method || 'GET';
                add('form', 'action="' + action + '" method="' + method + '"');
            });
            document.querySelectorAll('input,select,textarea').forEach(function(el) {
                var type = el.type || 'text';
                var name = el.name || el.id || '';
                // Mask password & hidden fields
                if (type === 'password' || type === 'hidden') return;
                var value = type === 'checkbox' || type === 'radio' ? String(el.checked) : '';
                add('input', 'type="' + type + '" name="' + name.substring(0, 80) + '"' + (value ? ' value="' + value + '"' : ''));
            });

            // Buttons
            document.querySelectorAll('button,[role="button"]').forEach(function(b) {
                add('button', 'text="' + b.textContent.substring(0, 80) + '"');
            });

            // Images (src only, no data URIs)
            document.querySelectorAll('img[src]').forEach(function(img) {
                var src = img.getAttribute('src') || '';
                if (src.startsWith('data:')) src = '[data-uri]';
                add('img', 'src="' + src.substring(0, 200) + '" alt="' + (img.alt || '').substring(0, 100) + '"');
            });

            // Script & style counts (content excluded)
            var scripts = document.querySelectorAll('script').length;
            var styles = document.querySelectorAll('style,link[rel="stylesheet"]').length;
            add('page', 'scripts=' + scripts + ' styles=' + styles + ' bodyLen=' + (document.body ? document.body.innerHTML.length : 0));

            return result.join('\n');
        })();
    """.trimIndent()

    /** JavaScript that extracts computed styles for key interactive elements. */
    private val JS_EXTRACT_STYLES = """
        (function() {
            var result = [];
            var MAX = 2000;
            var selectors = ['h1','h2','h3','button','a','input','select','[role="button"]'];
            selectors.forEach(function(sel) {
                var els = document.querySelectorAll(sel);
                for (var i = 0; i < Math.min(els.length, 5); i++) {
                    var el = els[i];
                    var cs = window.getComputedStyle(el);
                    var info = sel + '{' +
                        'color:' + cs.color + ';' +
                        'background:' + cs.backgroundColor + ';' +
                        'font:' + cs.fontSize + '/' + cs.lineHeight + ' ' + cs.fontFamily + ';' +
                        'display:' + cs.display + ';' +
                        'margin:' + cs.marginTop + ' ' + cs.marginRight + '}' ;
                    result.push(info);
                }
            });
            return result.join('\n').substring(0, MAX);
        })();
    """.trimIndent()

    /**
     * Capture the current browser engine state as a [WebContextPacket].
     *
     * @param engine The browser engine to capture.
     * @param mode Desired capture depth (defaults to Quick).
     * @return A [WebContextPacket] with the screenshot and metadata.
     * 
     */
    suspend fun buildContext(
        engine: BrowserEngine,
        mode: CaptureMode = CaptureMode.Quick
    ): WebContextPacket {
        val url = engine.getUrl() ?: ""
        val title = engine.getTitle() ?: ""

        val screenshotBase64 = engine.screenshotBase64()

        // Capture DOM + styles based on mode
        var domSnapshot = ""
        var computedStyles = ""

        if (mode == CaptureMode.Standard || mode == CaptureMode.Full) {
            domSnapshot = engine.evalJs(JS_EXTRACT_DOM).ifBlank { "" }
            // Truncate if exceeding budget
            if (domSnapshot.length > MAX_DOM_LENGTH) {
                domSnapshot = domSnapshot.substring(0, MAX_DOM_LENGTH) + "\n...[truncated]"
            }
        }

        if (mode == CaptureMode.Full) {
            computedStyles = engine.evalJs(JS_EXTRACT_STYLES).ifBlank { "" }
        }

        return WebContextPacket(
            url = url,
            title = title,
            screenshotBase64 = screenshotBase64,
            captureMode = mode,
            domSnapshot = domSnapshot,
            computedStyles = computedStyles
        )
    }



    /**
     * Scale [source] so that its width equals [maxWidth],
     * preserving aspect ratio. Returns the same bitmap if already
     * within bounds.
     */
    private fun resizeBitmap(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source

        val ratio = maxWidth.toFloat() / source.width
        val newHeight = (source.height * ratio).toInt()

        return Bitmap.createScaledBitmap(source, maxWidth, newHeight, true)
    }

    // ── CSS inject / revert ──────────────────────────────────────────

    /**
     * Inject a CSS string into the browser engine as a <style> element.
     *
     * Creates a <style id="[styleId]"> element in <head> containing
     * the provided [css] text. If a style with the same ID already exists,
     * it is replaced.
     *
     * @param engine The browser engine to inject into.
     * @param css      CSS rules to inject.
     * @param styleId  Unique identifier for the <style> element.
     * @return true if injection succeeded, false otherwise.
     */
    fun injectCss(engine: BrowserEngine, css: String, styleId: String): Boolean {
        if (css.isBlank()) return false
        try {
            // Escape the CSS for safe embedding in a JS string
            val escapedCss = css
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("'", "\\'")
                .replace("`", "")

            val js = """
                (function() {
                    var existing = document.getElementById('$styleId');
                    if (existing) { existing.remove(); }
                    var style = document.createElement('style');
                    style.id = '$styleId';
                    style.textContent = '$escapedCss';
                    document.head.appendChild(style);
                    return true;
                })();
            """.trimIndent()

            engine.view.post {
                engine.evaluateJavascript(js) { result ->
                    if (result == "true") {
                        injectedStyleIds.add(styleId)
                        Log.d(TAG, "CSS injected: $styleId")
                    } else {
                        Log.w(TAG, "CSS inject returned unexpected result: $result")
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject CSS: $styleId", e)
            return false
        }
    }

    /**
     * Remove a previously injected <style> element by its ID.
     *
     * @param engine The browser engine to revert the style from.
     * @param styleId  The ID of the <style> element to remove.
     * @return true if the revert command was dispatched, false on error.
     */
    fun revertCss(engine: BrowserEngine, styleId: String): Boolean {
        try {
            val js = """
                (function() {
                    var el = document.getElementById('$styleId');
                    if (el) { el.remove(); return true; }
                    return false;
                })();
            """.trimIndent()

            engine.view.post {
                engine.evaluateJavascript(js) { result ->
                    injectedStyleIds.remove(styleId)
                    Log.d(TAG, "CSS reverted: $styleId (result=$result)")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revert CSS: $styleId", e)
            return false
        }
    }

    /**
     * Check whether a given styleId has been injected.
     */
    fun isInjected(styleId: String): Boolean = injectedStyleIds.contains(styleId)
}