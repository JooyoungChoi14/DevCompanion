package com.devcompanion.llm

/**
 * Pure-Kotlin Markdown parser that converts raw text into a tree of [MdBlock]s.
 *
 * Design goals:
 * - Stateless: every public function is a pure function with no side effects.
 * - Streaming-safe: unclosed fenced code blocks are treated as "in progress"
 *   and rendered as code blocks anyway (with a trailing cursor indicator).
 * - No external dependencies.
 *
 * Supported syntax:
 * - Headings: # ## ### (up to level 6)
 * - Fenced code blocks: ```lang ... ```
 * - Inline code: `code`
 * - Bold: **bold** or __bold__
 * - Italic: *italic* or _italic_
 * - Strikethrough: ~~strike~~
 * - Links: [text](url)
 * - Unordered lists: - * + items
 * - Ordered lists: 1. 2. 3. items
 * - Paragraphs (separated by blank lines)
 * - Hard line breaks (two trailing spaces or explicit \n in context)
 */

// ── AST ────────────────────────────────────────────────────────────────────

/** A top-level block element in the parsed markdown. */
sealed class MdBlock {
    /** A heading with [level] (1–6) and inline [spans]. */
    data class Heading(
        val level: Int,
        val spans: List<MdSpan>
    ) : MdBlock()

    /** A fenced code block with optional [language] and [code] content. */
    data class CodeBlock(
        val language: String,
        val code: String,
        val closed: Boolean = true  // false when streaming & fence is unclosed
    ) : MdBlock()

    /** A list block containing [items]. [ordered] distinguishes ordered vs unordered. */
    data class ListBlock(
        val ordered: Boolean,
        val items: List<ListItem>
    ) : MdBlock()

    /** A paragraph consisting of inline [spans]. */
    data class Paragraph(
        val spans: List<MdSpan>
    ) : MdBlock()

    /** A thematic break (horizontal rule). */
    data object ThematicBreak : MdBlock()

    /** A blockquote with [spans] content. */
    data class Blockquote(
        val spans: List<MdSpan>
    ) : MdBlock()
}

/** A single list item with its inline [spans]. */
data class ListItem(
    val spans: List<MdSpan>
)

/** An inline span within a block. */
sealed class MdSpan {
    /** Plain text. */
    data class Text(val text: String) : MdSpan()
    /** Bold text containing nested [spans]. */
    data class Bold(val spans: List<MdSpan>) : MdSpan()
    /** Italic text containing nested [spans]. */
    data class Italic(val spans: List<MdSpan>) : MdSpan()
    /** Strikethrough text containing nested [spans]. */
    data class Strikethrough(val spans: List<MdSpan>) : MdSpan()
    /** Inline code with [code] content. */
    data class Code(val code: String) : MdSpan()
    /** A hyperlink with [text] and [url]. */
    data class Link(val text: String, val url: String) : MdSpan()
    /** A hard line break. */
    data object LineBreak : MdSpan()
}

// ── Parser ─────────────────────────────────────────────────────────────────

/**
 * Parse raw markdown [text] into a list of [MdBlock]s.
 *
 * This is the main entry point. It is a pure function with no side effects,
 * safe to call from any thread.
 */
fun parseMarkdown(text: String): List<MdBlock> {
    if (text.isBlank()) return emptyList()

    val lines = text.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Blank line — skip
        if (line.isBlank()) {
            i++
            continue
        }

        // Fenced code block
        val fenceMatch = FENCE_REGEX.matchEntire(line)
        if (fenceMatch != null) {
            val lang = fenceMatch.groupValues[1].trim()
            val codeLines = mutableListOf<String>()
            var closed = false
            i++
            while (i < lines.size) {
                if (FENCE_REGEX.matchEntire(lines[i]) != null) {
                    closed = true
                    i++
                    break
                }
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(
                language = lang,
                code = codeLines.joinToString("\n"),
                closed = closed
            ))
            continue
        }

        // Heading
        val headingMatch = HEADING_REGEX.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
            val content = headingMatch.groupValues[2].trimEnd()
            blocks.add(MdBlock.Heading(level, parseSpans(content)))
            i++
            continue
        }

        // Thematic break
        if (THEMATIC_BREAK_REGEX.matches(line.trim())) {
            blocks.add(MdBlock.ThematicBreak)
            i++
            continue
        }

        // Blockquote
        val bqMatch = BLOCKQUOTE_REGEX.matchEntire(line)
        if (bqMatch != null) {
            blocks.add(MdBlock.Blockquote(parseSpans(bqMatch.groupValues[1].trimStart())))
            i++
            continue
        }

        // List (unordered)
        val ulMatch = UNORDERED_LIST_REGEX.matchEntire(line)
        if (ulMatch != null) {
            val items = mutableListOf<ListItem>()
            while (i < lines.size) {
                val ulLine = UNORDERED_LIST_REGEX.matchEntire(lines[i])
                if (ulLine != null) {
                    items.add(ListItem(parseSpans(ulLine.groupValues[2].trimStart())))
                    i++
                } else if (lines[i].isNotBlank() && lines[i].startsWith("  ")) {
                    // Continuation line (indented)
                    val lastItem = items.lastOrNull()
                    if (lastItem != null) {
                        val existing = (lastItem.spans.firstOrNull() as? MdSpan.Text)?.text ?: ""
                        val combined = existing + "\n" + lines[i].trim()
                        items[items.lastIndex] = ListItem(listOf(MdSpan.Text(combined)))
                    }
                    i++
                } else {
                    break
                }
            }
            blocks.add(MdBlock.ListBlock(ordered = false, items = items))
            continue
        }

        // List (ordered)
        val olMatch = ORDERED_LIST_REGEX.matchEntire(line)
        if (olMatch != null) {
            val items = mutableListOf<ListItem>()
            while (i < lines.size) {
                val olLine = ORDERED_LIST_REGEX.matchEntire(lines[i])
                if (olLine != null) {
                    items.add(ListItem(parseSpans(olLine.groupValues[2].trimStart())))
                    i++
                } else if (lines[i].isNotBlank() && lines[i].startsWith("  ")) {
                    val lastItem = items.lastOrNull()
                    if (lastItem != null) {
                        val existing = (lastItem.spans.firstOrNull() as? MdSpan.Text)?.text ?: ""
                        val combined = existing + "\n" + lines[i].trim()
                        items[items.lastIndex] = ListItem(listOf(MdSpan.Text(combined)))
                    }
                    i++
                } else {
                    break
                }
            }
            blocks.add(MdBlock.ListBlock(ordered = true, items = items))
            continue
        }

        // Paragraph — collect consecutive non-blank, non-special lines
        val paraLines = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank()
            && !HEADING_REGEX.matches(lines[i])
            && !FENCE_REGEX.matches(lines[i])
            && !UNORDERED_LIST_REGEX.matches(lines[i])
            && !ORDERED_LIST_REGEX.matches(lines[i])
            && !THEMATIC_BREAK_REGEX.matches(lines[i].trim())
        ) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(parseSpans(paraLines.joinToString(" "))))
        }
    }

    return blocks
}

/**
 * Parse inline markdown spans within a single line of text.
 *
 * Handles: bold, italic, strikethrough, inline code, links, and plain text.
 * This is intentionally simple — it covers the most common patterns
 * without trying to be a full CommonMark parser.
 */
fun parseSpans(text: String): List<MdSpan> {
    if (text.isEmpty()) return emptyList()

    val spans = mutableListOf<MdSpan>()
    var pos = 0
    val len = text.length

    while (pos < len) {
        // Inline code: `...`
        if (text[pos] == '`') {
            val end = text.indexOf('`', pos + 1)
            if (end != -1) {
                spans.add(MdSpan.Code(text.substring(pos + 1, end)))
                pos = end + 1
                continue
            }
        }

        // Link: [text](url)
        if (text[pos] == '[') {
            val linkMatch = LINK_REGEX.find(text, pos)
            if (linkMatch != null && linkMatch.range.first == pos) {
                val linkText = linkMatch.groupValues[1]
                val linkUrl = linkMatch.groupValues[2]
                spans.add(MdSpan.Link(linkText, linkUrl))
                pos = linkMatch.range.last + 1
                continue
            }
        }

        // Strikethrough: ~~text~~
        if (pos + 1 < len && text[pos] == '~' && text[pos + 1] == '~') {
            val end = text.indexOf("~~", pos + 2)
            if (end != -1) {
                spans.add(MdSpan.Strikethrough(parseSpans(text.substring(pos + 2, end))))
                pos = end + 2
                continue
            }
        }

        // Bold: **text** or __text__
        if (pos + 1 < len && ((text[pos] == '*' && text[pos + 1] == '*') ||
                        (text[pos] == '_' && text[pos + 1] == '_'))) {
            val marker = text.substring(pos, pos + 2)
            val end = text.indexOf(marker, pos + 2)
            if (end != -1) {
                val inner = text.substring(pos + 2, end)
                // Check for bold+italic: ***text***
                if (inner.startsWith("*") && inner.endsWith("*") && inner.length > 1) {
                    spans.add(MdSpan.Bold(listOf(MdSpan.Italic(parseSpans(inner.substring(1, inner.length - 1))))))
                } else if (inner.startsWith("_") && inner.endsWith("_") && inner.length > 1) {
                    spans.add(MdSpan.Bold(listOf(MdSpan.Italic(parseSpans(inner.substring(1, inner.length - 1))))))
                } else {
                    spans.add(MdSpan.Bold(parseSpans(inner)))
                }
                pos = end + 2
                continue
            }
        }

        // Italic: *text* or _text_ (single delimiter)
        if ((text[pos] == '*' || text[pos] == '_') && (pos + 1 < len)) {
            val delim = text[pos]
            // Only match italic if not preceded by same delimiter (to avoid bold conflicts)
            if (pos == 0 || text[pos - 1] != delim) {
                val end = text.indexOf(delim, pos + 1)
                if (end != -1 && (end + 1 >= len || text[end + 1] != delim)) {
                    val inner = text.substring(pos + 1, end)
                    if (inner.isNotBlank()) {
                        spans.add(MdSpan.Italic(parseSpans(inner)))
                        pos = end + 1
                        continue
                    }
                }
            }
        }

        // Hard line break: two spaces at end of line
        if (pos + 1 < len && text[pos] == ' ' && text[pos + 1] == ' ' &&
            (pos + 2 >= len || text[pos + 2] == '\n')) {
            spans.add(MdSpan.LineBreak)
            pos += 2
            continue
        }

        // Plain text — collect until next special character
        val start = pos
        while (pos < len) {
            val c = text[pos]
            if (c == '`' || c == '[' || c == '*' || c == '_' || c == '~') break
            if (c == ' ' && pos + 1 < len && text[pos + 1] == ' ') break
            pos++
        }
        if (pos == start) {
            // No special char found, treat as plain text and advance
            spans.add(MdSpan.Text(text[pos].toString()))
            pos++
        } else {
            spans.add(MdSpan.Text(text.substring(start, pos)))
        }
    }

    return mergeTextSpans(spans)
}

/**
 * Merge adjacent [MdSpan.Text] spans that were produced by the
 * character-by-character fallback.
 */
private fun mergeTextSpans(spans: List<MdSpan>): List<MdSpan> {
    val result = mutableListOf<MdSpan>()
    var textBuffer = StringBuilder()

    for (span in spans) {
        if (span is MdSpan.Text) {
            textBuffer.append(span.text)
        } else {
            if (textBuffer.isNotEmpty()) {
                result.add(MdSpan.Text(textBuffer.toString()))
                textBuffer = StringBuilder()
            }
            result.add(span)
        }
    }
    if (textBuffer.isNotEmpty()) {
        result.add(MdSpan.Text(textBuffer.toString()))
    }
    return result
}

// ── Regex patterns ─────────────────────────────────────────────────────────

private val FENCE_REGEX = Regex("""^(`{3,})(.*)$""")
private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
private val THEMATIC_BREAK_REGEX = Regex("""^[-*_]{3,}\s*$""")
private val BLOCKQUOTE_REGEX = Regex("""^>\s?(.*)$""")
private val UNORDERED_LIST_REGEX = Regex("""^(\s*)([-*+])\s+(.+)$""")
private val ORDERED_LIST_REGEX = Regex("""^(\s*)(\d+)\.\s+(.+)$""")
private val LINK_REGEX = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

// ── Rendering analysis data classes ─────────────────────────────────────

data class MarkdownAnalysis(
    val rawLength: Int,
    val blocks: List<BlockAnalysis>
)

data class BlockAnalysis(
    val type: String,
    val details: String,
    val contentPreview: String,
    val spanCount: Int,
    val renderedLength: Int
)

/**
 * Analyze a markdown text and return detailed parse + render info.
 * Used by the agent for self-diagnosis of rendering issues.
 */
fun analyzeMarkdown(text: String): MarkdownAnalysis {
    val blocks = parseMarkdown(text)
    val blockAnalyses = blocks.map { block ->
        when (block) {
            is MdBlock.Heading -> BlockAnalysis(
                type = "Heading",
                details = "level=${block.level}",
                contentPreview = block.spans.mapNotNull { (it as? MdSpan.Text)?.text }.joinToString(" ").take(80),
                spanCount = block.spans.size,
                renderedLength = block.spans.sumOf { spanTextLength(it) }
            )
            is MdBlock.ListBlock -> BlockAnalysis(
                type = "List",
                details = "ordered=${block.ordered}, items=${block.items.size}",
                contentPreview = block.items.firstOrNull()?.spans?.mapNotNull { (it as? MdSpan.Text)?.text }?.joinToString(" ")?.take(80) ?: "",
                spanCount = block.items.sumOf { it.spans.size },
                renderedLength = block.items.sumOf { item -> item.spans.sumOf { spanTextLength(it) } }
            )
            is MdBlock.Paragraph -> BlockAnalysis(
                type = "Paragraph",
                details = "spans=${block.spans.size}",
                contentPreview = block.spans.mapNotNull { (it as? MdSpan.Text)?.text }.joinToString(" ").take(80),
                spanCount = block.spans.size,
                renderedLength = block.spans.sumOf { spanTextLength(it) }
            )
            is MdBlock.CodeBlock -> BlockAnalysis(
                type = "CodeBlock",
                details = "lang=${block.language}, closed=${block.closed}",
                contentPreview = block.code.take(60),
                spanCount = 0,
                renderedLength = block.code.length
            )
            is MdBlock.Blockquote -> BlockAnalysis(
                type = "Blockquote",
                details = "spans=${block.spans.size}",
                contentPreview = block.spans.mapNotNull { (it as? MdSpan.Text)?.text }.joinToString(" ").take(80),
                spanCount = block.spans.size,
                renderedLength = block.spans.sumOf { spanTextLength(it) }
            )
            is MdBlock.ThematicBreak -> BlockAnalysis(
                type = "ThematicBreak",
                details = "---",
                contentPreview = "",
                spanCount = 0,
                renderedLength = 0
            )
        }
    }
    return MarkdownAnalysis(
        rawLength = text.length,
        blocks = blockAnalyses
    )
}

private fun spanTextLength(span: MdSpan): Int = when (span) {
    is MdSpan.Text -> span.text.length
    is MdSpan.Bold -> span.spans.sumOf { spanTextLength(it) }
    is MdSpan.Italic -> span.spans.sumOf { spanTextLength(it) }
    is MdSpan.Strikethrough -> span.spans.sumOf { spanTextLength(it) }
    is MdSpan.Code -> span.code.length
    is MdSpan.Link -> span.text.length
    is MdSpan.LineBreak -> 1
}