package com.devcompanion.ui

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.devcompanion.llm.MdBlock
import com.devcompanion.llm.MdSpan
import com.devcompanion.llm.parseMarkdown
import org.commonmark.node.*  // ListItem, BlockQuote, etc.
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.Extension

private const val TAG = "MarkdownRenderer"

// ── Public API ──────────────────────────────────────────────────────────────

private fun measureContentHeight(webView: WebView?, onResult: (Int) -> Unit = {}) {
    webView?.evaluateJavascript("(function(){ return document.body.scrollHeight; })") { result ->
        result?.toIntOrNull()?.let { h ->
            if (h > 0) onResult(h)
        }
    }
}

private val commonmarkParser: Parser by lazy {
    Parser.builder()
        .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
        .build()
}

@Composable
fun MarkdownText(
    text: String?,
    onCssInject: ((styleId: String, css: String) -> Unit)? = null,
    onCssRevert: ((styleId: String) -> Unit)? = null,
    isCssInjected: ((styleId: String) -> Boolean)? = null,
    modifier: Modifier = Modifier
) {
    if (text.isNullOrBlank()) return
    val content = text!!  // safe after isNullOrBlank check

    val cssBlockStates = remember(content) {
        val blocks = parseMarkdown(content)
        var idx = 0
        blocks.mapNotNull { block ->
            if (block is MdBlock.CodeBlock) {
                val i = idx++
                if (block.language.equals("css", ignoreCase = true)) {
                    Triple(i, "css-inject-$i", block.code)
                } else null
            } else null
        }
    }

    val injectedState = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(text) {
        cssBlockStates.forEach { (_, styleId, _) ->
            val isInjected = isCssInjected?.invoke(styleId) ?: false
            injectedState[styleId] = isInjected
        }
    }

    // Check for unclosed code fences (streaming) → use custom parser
    val hasUnclosedFence = remember(content) {
        content.lines().count { it.trimStart().startsWith("```") } % 2 != 0
    }

    val html = remember(text, hasUnclosedFence) {
        if (hasUnclosedFence) {
            // Streaming: custom parser handles unclosed fences
            markdownToHtmlCustom(content)
        } else {
            // Completed: use commonmark for spec-compliant rendering
            markdownToHtmlCommonmark(content)
        }
    }

    // Debug logging
    LaunchedEffect(html) {
        Log.d(TAG, "=== Markdown Render ===")
        Log.d(TAG, "Input (${content.length} chars): ${content.take(300)}")
        Log.d(TAG, "Output HTML (${html.length} chars): ${html.take(300)}")
    }

    var contentHeightPx by remember { mutableStateOf(0) }
    val density = LocalContext.current.resources.displayMetrics.density

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        measureContentHeight(view) { h -> contentHeightPx = h }
                    }
                }
                settings.javaScriptEnabled = true
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(0)

                addJavascriptInterface(
                    MarkdownBridge(
                        onCopy = { code ->
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                        },
                        onCssInject = { idx ->
                            val cssInfo = cssBlockStates.find { it.first == idx }
                            if (cssInfo != null) {
                                val (_, styleId, css) = cssInfo
                                injectedState[styleId] = true
                                onCssInject?.invoke(styleId, css)
                            }
                        },
                        onCssRevert = { idx ->
                            val cssInfo = cssBlockStates.find { it.first == idx }
                            if (cssInfo != null) {
                                val (_, styleId, _) = cssInfo
                                injectedState[styleId] = false
                                onCssRevert?.invoke(styleId)
                            }
                        },
                        onCopyRaw = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("markdown", text))
                        }
                    ),
                    "MarkdownBridge"
                )
            }
        },
        update = { webView ->
            val styledHtml = wrapHtml(html, isDarkTheme(webView.context))
            webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
            webView.post { measureContentHeight(webView) { h -> contentHeightPx = h } }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (contentHeightPx > 0) Modifier.height(((contentHeightPx + 16) * density).toInt().dp)
                else Modifier.wrapContentHeight()
            )
    )
}

// ── JS Bridge ───────────────────────────────────────────────────────────────

private class MarkdownBridge(
    private val onCopy: (String) -> Unit,
    private val onCssInject: (Int) -> Unit,
    private val onCssRevert: (Int) -> Unit,
    private val onCopyRaw: (String) -> Unit
) {
    @JavascriptInterface fun copyCode(code: String) = onCopy(code)
    @JavascriptInterface fun cssInject(index: Int) = onCssInject(index)
    @JavascriptInterface fun cssRevert(index: Int) = onCssRevert(index)
    @JavascriptInterface fun copyRawText() = onCopyRaw("")
}

// ── CommonMark → HTML ──────────────────────────────────────────────────────

/**
 * Pre-process markdown to ensure GFM tables are parseable by commonmark.
 * commonmark-java requires a blank line before a table block; LLMs sometimes
 * omit this. Also normalises leading spaces on table rows.
 */
/**
 * Pre-process markdown for robust commonmark parsing.
 *
 * 1. Ensure blank line after headings (commonmark requirement for following blocks)
 * 2. Normalize GFM tables: strip leading spaces, ensure leading |, insert blank line before
 * 3. Regex fallback: convert any remaining pipe-delimited tables to HTML <table>
 */
private fun normalizeMarkdown(text: String): String {
    // Step 1: Ensure blank line after headings
    val lines = content.lines()
    val step1 = mutableListOf<String>()
    for (i in lines.indices) {
        step1.add(lines[i])
        // After a heading line, ensure at least one blank line
        if (lines[i].matches(Regex("^#{1,6}\\s+.+"))) {
            // Look ahead: if next line is not blank and not another heading
            val next = if (i + 1 < lines.size) lines[i + 1] else ""
            if (next.isNotBlank() && !next.matches(Regex("^#{1,6}\\s+.+"))) {
                step1.add("")
            }
        }
    }
    val afterHeadings = step1.joinToString("\n")

    // Step 2: Normalize GFM table regions
    val tableLines = afterHeadings.lines()
    val tableRanges = mutableListOf<IntRange>()
    var i = 0
    while (i < tableLines.size) {
        val trimmed = tableLines[i].trimStart()
        if (isTableSeparator(trimmed) && i > 0) {
            val prevTrimmed = tableLines[i - 1].trimStart()
            if (prevTrimmed.contains("|") || prevTrimmed.startsWith("|")) {
                var end = i + 1
                while (end < tableLines.size && isTableRow(tableLines[end].trimStart())) end++
                tableRanges.add((i - 1) until end)
                i = end
                continue
            }
        }
        i++
    }

    val step2 = mutableListOf<String>()
    for (idx in tableLines.indices) {
        val inTable = tableRanges.any { idx in it }
        if (inTable) {
            val normalized = tableLines[idx].trimStart()
            val withPipe = if (normalized.startsWith("|")) normalized else "| " + normalized
            val isFirstInTable = tableRanges.any { it.first == idx }
            if (isFirstInTable && step2.isNotEmpty() && step2.last().isNotBlank()) {
                step2.add("")
            }
            step2.add(withPipe)
        } else {
            step2.add(tableLines[idx])
        }
    }
    return step2.joinToString("\n")
}

private fun isTableSeparator(line: String): Boolean {
    if (line.isEmpty()) return false
    val trimmed = line.removePrefix("|").trimStart()
    return trimmed.isNotEmpty() &&
        trimmed.replace("|", "").replace(":", "").replace("-", "").replace(" ", "").isEmpty() &&
        trimmed.count { it == '-' } >= 3
}

private fun isTableRow(line: String): Boolean {
    if (line.isEmpty() || line.startsWith("#")) return false
    return line.contains("|") || line.startsWith("|")
}

private fun markdownToHtmlCommonmark(text: String): String {
    val normalized = normalizeMarkdown(text)
    val document = commonmarkParser.parse(normalized)
    val visitor = CommonMarkHtmlVisitor()
    document.accept(visitor)
    return visitor.build()
}

private class CommonMarkHtmlVisitor : AbstractVisitor() {
    private val sb = StringBuilder()
    private var codeBlockIndex = 0

    fun build(): String = sb.toString()

    override fun visit(heading: Heading) {
        sb.append("<h${heading.level}>")
        visitChildren(heading)
        sb.appendLine("</h${heading.level}>")
    }

    override fun visit(paragraph: Paragraph) {
        sb.append("<p>")
        visitChildren(paragraph)
        sb.appendLine("</p>")
    }

    override fun visit(bulletList: BulletList) {
        sb.appendLine("<ul>")
        visitChildren(bulletList)
        sb.appendLine("</ul>")
    }

    override fun visit(orderedList: OrderedList) {
        sb.appendLine("<ol>")
        visitChildren(orderedList)
        sb.appendLine("</ol>")
    }

    override fun visit(listItem: ListItem) {
        sb.append("<li>")
        // If the list item has multiple paragraphs, handle them properly
        // A list item with just text comes as Paragraph child
        val firstChild = listItem.firstChild
        if (listItem.firstChild?.next == null && firstChild is Paragraph) {
            // Single paragraph in list item — don't wrap in <p>
            visitChildren(firstChild)
        } else {
            visitChildren(listItem)
        }
        sb.appendLine("</li>")
    }

    override fun visit(blockQuote: BlockQuote) {
        sb.appendLine("<blockquote>")
        visitChildren(blockQuote)
        sb.appendLine("</blockquote>")
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        renderCodeBlock(fencedCodeBlock.info?.trim()?.ifBlank { null }, fencedCodeBlock.literal)
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        renderCodeBlock(null, indentedCodeBlock.literal)
    }

    private fun renderCodeBlock(lang: String?, code: String) {
        val langStr = lang?.ifBlank { null }
        val langAttr = langStr?.let { """ class="language-$it"""" } ?: ""
        val langLabel = langStr?.let { """<span class="code-lang">$it</span>""" } ?: ""
        val escapedCode = escapeHtml(code)
        val idx = codeBlockIndex++
        val isCss = langStr?.equals("css", ignoreCase = true) == true
        val cssBtns = if (isCss) {
            """
            |<button class="css-inject-btn" onclick="MarkdownBridge.cssInject($idx)">▶ Inject</button>
            |<button class="css-revert-btn" onclick="MarkdownBridge.cssRevert($idx)">↩ Revert</button>
            """.trimMargin()
        } else ""

        sb.appendLine("""<div class="code-block">""")
        sb.appendLine("""  <div class="code-header">""")
        sb.append("""    $langLabel""")
        sb.appendLine("""    <div class="code-actions">$cssBtns<button class="copy-btn" onclick="copyCodeBlock(this)">Copy</button></div>""")
        sb.appendLine("""  </div>""")
        sb.appendLine("""  <pre><code$langAttr>$escapedCode</code></pre>""")
        sb.appendLine("""</div>""")
    }

    override fun visit(thematicBreak: ThematicBreak) {
        sb.appendLine("<hr/>")
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        sb.append("<strong>")
        visitChildren(strongEmphasis)
        sb.append("</strong>")
    }

    override fun visit(emphasis: Emphasis) {
        sb.append("<em>")
        visitChildren(emphasis)
        sb.append("</em>")
    }

    override fun visit(code: Code) {
        sb.append("""<code class="inline-code">${escapeHtml(code.literal)}</code>""")
    }

    override fun visit(text: Text) {
        sb.append(escapeHtml(text.literal))
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        sb.append(" ")
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        sb.append("<br/>")
    }

    override fun visit(link: Link) {
        sb.append("""<a href="${escapeHtml(link.destination ?: "")}" target="_blank" rel="noopener">""")
        visitChildren(link)
        sb.append("</a>")
    }

    override fun visit(image: Image) {
        sb.append("""<img src="${escapeHtml(image.destination ?: "")}" alt=""/>""")
    }

    override fun visit(htmlBlock: HtmlBlock) {
        sb.appendLine(htmlBlock.literal)
    }

    override fun visit(htmlInline: HtmlInline) {
        sb.append(htmlInline.literal)
    }

    // GFM extensions handled via type-safe checks
    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                sb.append("<del>")
                visitChildren(customNode)
                sb.append("</del>")
            }
            is TableRow -> {
                sb.append("<tr>")
                visitChildren(customNode)
                sb.appendLine("</tr>")
            }
            is TableCell -> {
                val tag = if (customNode.isHeader) "th" else "td"
                sb.append("<$tag>")
                visitChildren(customNode)
                sb.append("</$tag>")
            }
            else -> visitChildren(customNode)
        }
    }

    override fun visit(customBlock: CustomBlock) {
        when (customBlock) {
            is TableBlock -> {
                sb.appendLine("<div class=\"table-wrapper\"><table>")
                visitChildren(customBlock)
                sb.appendLine("</table></div>")
            }
            is TableHead -> {
                sb.appendLine("<thead>")
                visitChildren(customBlock)
                sb.appendLine("</thead>")
            }
            is TableBody -> {
                sb.appendLine("<tbody>")
                visitChildren(customBlock)
                sb.appendLine("</tbody>")
            }
            else -> visitChildren(customBlock)
        }
    }
}

// ── Streaming fallback (custom parser) ──────────────────────────────────────

private fun markdownToHtmlCustom(text: String): String {
    val blocks = parseMarkdown(content)
    val sb = StringBuilder()
    var codeBlockIndex = 0

    for (block in blocks) {
        when (block) {
            is MdBlock.Heading -> {
                val tag = "h${block.level.coerceIn(1, 6)}"
                sb.appendLine("<$tag>${escapeHtml(spansToPlainText(block.spans))}</$tag>")
            }
            is MdBlock.CodeBlock -> {
                val lang = block.language.ifBlank { null }
                val langAttr = lang?.let { """ class="language-$it"""" } ?: ""
                val langLabel = lang?.let { """<span class="code-lang">$it</span>""" } ?: ""
                val escapedCode = escapeHtml(block.code)
                val idx = codeBlockIndex++
                val isCss = block.language.equals("css", ignoreCase = true)
                val cssBtns = if (isCss) {
                    """
                    |<button class="css-inject-btn" onclick="MarkdownBridge.cssInject($idx)">▶ Inject</button>
                    |<button class="css-revert-btn" onclick="MarkdownBridge.cssRevert($idx)">↩ Revert</button>
                    """.trimMargin()
                } else ""
                val streamingCursor = if (!block.closed) """<span class="streaming-cursor">▌</span>""" else ""

                sb.appendLine("""<div class="code-block">""")
                sb.appendLine("""  <div class="code-header">""")
                sb.append("""    $langLabel""")
                sb.appendLine("""    <div class="code-actions">$cssBtns<button class="copy-btn" onclick="copyCodeBlock(this)">Copy</button></div>""")
                sb.appendLine("""  </div>""")
                sb.appendLine("""  <pre><code$langAttr>${escapedCode}$streamingCursor</code></pre>""")
                sb.appendLine("""</div>""")
            }
            is MdBlock.ListBlock -> {
                val tag = if (block.ordered) "ol" else "ul"
                sb.appendLine("<$tag>")
                for (item in block.items) {
                    sb.appendLine("  <li>${escapeHtml(spansToPlainText(item.spans))}</li>")
                }
                sb.appendLine("</$tag>")
            }
            is MdBlock.Paragraph -> {
                sb.appendLine("<p>${escapeHtml(spansToPlainText(block.spans))}</p>")
            }
            is MdBlock.ThematicBreak -> sb.appendLine("<hr/>")
            is MdBlock.Blockquote -> {
                sb.appendLine("<blockquote>${escapeHtml(spansToPlainText(block.spans))}</blockquote>")
            }
        }
    }
    return sb.toString()
}

private fun spansToPlainText(spans: List<MdSpan>): String = spans.map { span ->
    when (span) {
        is MdSpan.Text -> span.text
        is MdSpan.Bold -> spansToPlainText(span.spans)
        is MdSpan.Italic -> spansToPlainText(span.spans)
        is MdSpan.Strikethrough -> spansToPlainText(span.spans)
        is MdSpan.Code -> span.code
        is MdSpan.Link -> span.text
        is MdSpan.LineBreak -> "\n"
    }
}.joinToString("")

// ── HTML escaping ────────────────────────────────────────────────────────────

private fun escapeHtml(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

// ── Theme detection ────────────────────────────────────────────────────────

private fun isDarkTheme(context: Context): Boolean {
    val uiMode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
}

// ── HTML wrapper ───────────────────────────────────────────────────────────

private fun wrapHtml(bodyHtml: String, isDark: Boolean): String {
    val fg = if (isDark) "#e0e0e0" else "#1a1a1a"
    val codeBg = if (isDark) "#2a2a2e" else "#f0f0f0"
    val border = if (isDark) "#3a3a3c" else "#d0d0d0"
    val linkColor = if (isDark) "#8ab4f8" else "#1a73e8"
    val accentBg = if (isDark) "#2a2a2e" else "#e8e8e8"
    val btnFg = if (isDark) "#a0a0a0" else "#666666"
    val injectFg = if (isDark) "#64b5f6" else "#1565c0"
    val revertFg = if (isDark) "#ef9a9a" else "#c62828"

    return """
    |<!DOCTYPE html>
    |<html>
    |<head>
    |<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
    |<style>
    |  body, p, h1, h2, h3, h4, h5, h6, blockquote, hr, pre, .code-block, .code-header, .copied-toast {
    |    margin: 0; padding: 0;
    |  }
    |  *, *::before, *::after { box-sizing: border-box; }
    |  body {
    |    font-family: -apple-system, system-ui, sans-serif;
    |    font-size: 15px; line-height: 1.5;
    |    color: $fg; background: transparent;
    |    word-wrap: break-word; overflow-wrap: break-word;
    |    -webkit-user-select: none; user-select: none;
    |  }
    |  h1 { font-size: 1.4em; font-weight: 700; margin: 0.6em 0 0.3em; }
    |  h2 { font-size: 1.25em; font-weight: 700; margin: 0.5em 0 0.25em; }
    |  h3 { font-size: 1.1em; font-weight: 600; margin: 0.4em 0 0.2em; }
    |  h4 { font-size: 1em; font-weight: 600; margin: 0.3em 0 0.15em; }
    |  h5, h6 { font-size: 0.95em; font-weight: 600; margin: 0.2em 0 0.1em; }
    |  p { margin: 0.4em 0; }
    |  a { color: $linkColor; text-decoration: none; }
    |  a:hover { text-decoration: underline; }
    |  strong { font-weight: 700; }
    |  em { font-style: italic; }
    |  del { text-decoration: line-through; opacity: 0.7; }
    |  .inline-code {
    |    font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
    |    font-size: 0.88em; background: $accentBg;
    |    padding: 0.15em 0.35em; border-radius: 3px;
    |  }
    |  .code-block {
    |    background: $codeBg; border: 1px solid $border;
    |    border-radius: 6px; margin: 0.5em 0; overflow: hidden;
    |  }
    |  .code-header {
    |    display: flex; align-items: center; justify-content: space-between;
    |    padding: 0.3em 0.6em; border-bottom: 1px solid $border;
    |  }
    |  .code-lang {
    |    font-size: 0.75em; color: $btnFg;
    |    font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
    |  }
    |  .code-actions {
    |    display: inline-flex; align-items: center; gap: 0.3em;
    |  }
    |  .code-header button, .code-actions button {
    |    font-size: 0.7em; padding: 0.2em 0.5em;
    |    border: 1px solid $border; border-radius: 3px;
    |    background: transparent; color: $btnFg; cursor: pointer;
    |  }
    |  .code-header button:hover, .code-actions button:hover { background: $accentBg; }
    |  .css-inject-btn { color: $injectFg !important; border-color: $injectFg !important; }
    |  .css-revert-btn { color: $revertFg !important; border-color: $revertFg !important; }
    |  .css-revert-btn.active { font-weight: 600; }
    |  pre { padding: 0.6em; margin: 0; overflow-x: auto; -webkit-overflow-scrolling: touch; }
  pre, code, .code-block, .inline-code { -webkit-user-select: text; user-select: text; }
    |  code { font-family: 'SF Mono', 'Menlo', 'Consolas', monospace; font-size: 0.85em; line-height: 1.4; }
    |  .streaming-cursor { color: $linkColor; animation: blink 1s infinite; }
    |  @keyframes blink { 0%,100%{opacity:1} 50%{opacity:0} }
    |  ul { margin: 0.3em 0; padding-left: 1.5em; list-style-type: disc; }
    |  ol { margin: 0.3em 0; padding-left: 1.5em; list-style-type: decimal; }
    |  li { margin: 0.15em 0; }
    |  li::marker { color: $btnFg; }
    |  blockquote {
    |    border-left: 3px solid $linkColor; padding: 0.3em 0.6em;
    |    margin: 0.4em 0; background: $accentBg; border-radius: 0 4px 4px 0;
    |  }
    |  .table-wrapper { overflow-x: auto; -webkit-overflow-scrolling: touch; margin: 0.5em 0; }
    |  table { border-collapse: collapse; width: 100%; font-size: 0.9em; }
    |  th, td { border: 1px solid $border; padding: 0.35em 0.6em; text-align: left; }
    |  th { background: $accentBg; font-weight: 600; }
    |  tr:nth-child(even) td { background: ${if (isDark) "#252527" else "#f8f8f8"}; }
    |  hr { border: none; border-top: 1px solid $border; margin: 0.6em 0; }
    |  .copied-toast {
    |    position: fixed; bottom: 8px; right: 8px;
    |    background: $border; color: $fg;
    |    padding: 0.3em 0.6em; border-radius: 4px;
    |    font-size: 0.75em; opacity: 0; transition: opacity 0.3s;
    |  }
    |  .copied-toast.show { opacity: 1; }
    |</style>
    |</head>
    |<body>
    |$bodyHtml
    |<div id="toast" class="copied-toast">Copied!</div>
    |<script>
    |  var longPressTimer = null;
    |  var toastTimeout = null;
    |  function showToast(msg) {
    |    var t = document.getElementById('toast');
    |    t.textContent = msg;
    |    t.classList.add('show');
    |    if (toastTimeout) clearTimeout(toastTimeout);
    |    toastTimeout = setTimeout(function() { t.classList.remove('show'); }, 1500);
    |  }
    |  document.addEventListener('touchstart', function(e) {
    |    if (e.target.closest('pre, code, .code-block')) return;
    |    longPressTimer = setTimeout(function() {
    |      var el = e.target.closest('p, li, h1, h2, h3, h4, h5, h6, blockquote, td, th') || e.target;
    |      var text = (el.innerText || el.textContent || '').trim();
    |      if (content.length > 0) {
    |        var range = document.createRange();
    |        range.selectNodeContents(el);
    |        var sel = window.getSelection();
    |        sel.removeAllRanges();
    |        sel.addRange(range);
    |        try { document.execCommand('copy'); showToast('Copied!'); } catch(ex) { showToast('Copy failed'); }
    |        sel.removeAllRanges();
    |      }
    |    }, 500);
    |  }, { passive: true });
    |  document.addEventListener('touchmove', function() {
    |    if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
    |  }, { passive: true });
    |  document.addEventListener('touchend', function() {
    |    if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
    |  }, { passive: true });
    |  function copyCodeBlock(btn) {
    |    var block = btn.closest('.code-block');
    |    var codeEl = block ? block.querySelector('code') : null;
    |    if (codeEl) {
    |      var text = codeEl.textContent || '';
    |      text = text.replace(/▌$/, '');
    |      MarkdownBridge.copyCode(text);
    |      btn.textContent = 'Copied!';
    |      setTimeout(function(){ btn.textContent = 'Copy'; }, 1500);
    |      var toast = document.getElementById('toast');
    |      if (toast) { toast.classList.add('show'); setTimeout(function(){ toast.classList.remove('show'); }, 1500); }
    |    }
    |  }
    |  // Long press: copy raw markdown to clipboard
    |  var longPressTimer = null;
    |  var longPressTriggered = false;
    |  document.body.addEventListener('touchstart', function(e) {
    |    longPressTriggered = false;
    |    longPressTimer = setTimeout(function() {
    |      longPressTriggered = true;
    |      MarkdownBridge.copyRawText();
    |      var toast = document.getElementById('toast');
    |      if (toast) {
    |        toast.textContent = 'Raw markdown copied!';
    |        toast.classList.add('show');
    |        setTimeout(function() { toast.classList.remove('show'); toast.textContent = 'Copied!'; }, 1500);
    |      }
    |    }, 500);
    |  }, { passive: true });
    |  document.body.addEventListener('touchend', function() { clearTimeout(longPressTimer); }, { passive: true });
    |  document.body.addEventListener('touchmove', function() { clearTimeout(longPressTimer); }, { passive: true });
    |  document.body.addEventListener('contextmenu', function(e) {
    |    if (longPressTriggered) { e.preventDefault(); longPressTriggered = false; }
    |  });
    |</script>
    |</body>
    |</html>
    """.trimMargin()
}