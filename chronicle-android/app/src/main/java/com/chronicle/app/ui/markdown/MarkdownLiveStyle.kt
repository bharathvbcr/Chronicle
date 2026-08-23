package com.chronicle.app.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Live markdown styling for the capture TextField: styles CommonMark syntax in place
 * (bold, italic, strikethrough, inline code, links, headings, quotes, list markers)
 * while the user types, without altering the underlying text or cursor positions.
 * Syntax markers stay visible but dimmed, so editing remains predictable.
 */
data class MarkdownLiveColors(
    val accent: Color,
    val marker: Color,
    val codeBackground: Color,
    val quote: Color,
)

class MarkdownLiveTransformation(
    private val colors: MarkdownLiveColors,
) : VisualTransformation {
    private var cachedText: AnnotatedString? = null
    private var cachedResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        cachedResult?.let { if (text == cachedText) return it }
        val styled = AnnotatedString(
            text = text.text,
            spanStyles = buildMarkdownSpanStyles(text.text, colors),
        )
        return TransformedText(styled, OffsetMapping.Identity).also {
            cachedText = text
            cachedResult = it
        }
    }
}

@Composable
fun rememberMarkdownLiveTransformation(): VisualTransformation {
    val colors = MarkdownLiveColors(
        accent = MaterialTheme.colorScheme.primary,
        marker = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        quote = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
    )
    return remember(colors) { MarkdownLiveTransformation(colors) }
}

private val HEADING = Regex("^(#{1,6})\\s")
private val QUOTE = Regex("^>\\s?")
private val LIST_MARKER = Regex("^(\\s*)([-+]|\\d+\\.|\\*)\\s")
private val INLINE_CODE = Regex("`([^`]+)`")
private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
private val ITALIC = Regex("(?<![*\\w])\\*([^*]+)\\*(?!\\*)")
private val STRIKETHROUGH = Regex("~~(.+?)~~")
private val LINK = Regex("\\[([^\\]]*)]\\(([^)]*)\\)")

/**
 * Pure span computation over raw markdown [text]. Offsets index into [text] unchanged,
 * so the result is safe to pair with [OffsetMapping.Identity].
 */
fun buildMarkdownSpanStyles(
    text: String,
    colors: MarkdownLiveColors,
): List<AnnotatedString.Range<SpanStyle>> {
    val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    var lineStart = 0
    while (lineStart <= text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline
        styleLine(text.substring(lineStart, lineEnd), lineStart, colors, spans)
        if (newline < 0) break
        lineStart = newline + 1
    }
    return spans
}

private fun styleLine(
    line: String,
    offset: Int,
    colors: MarkdownLiveColors,
    out: MutableList<AnnotatedString.Range<SpanStyle>>,
) {
    if (line.isBlank()) return
    val markerStyle = SpanStyle(color = colors.marker)

    val heading = HEADING.find(line)
    val quote = if (heading == null) QUOTE.find(line) else null
    val list = if (heading == null && quote == null) LIST_MARKER.find(line) else null
    when {
        heading != null -> {
            out += span(markerStyle, offset, offset + heading.value.length)
            if (line.length > heading.value.length) {
                out += span(
                    headingStyle(heading.groupValues[1].length),
                    offset + heading.value.length,
                    offset + line.length,
                )
            }
        }
        quote != null -> {
            out += span(markerStyle, offset, offset + quote.value.length)
            if (line.length > quote.value.length) {
                out += span(
                    SpanStyle(color = colors.quote, fontStyle = FontStyle.Italic),
                    offset + quote.value.length,
                    offset + line.length,
                )
            }
        }
        list != null -> {
            out += span(
                SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold),
                offset + list.groupValues[1].length,
                offset + list.value.length,
            )
        }
    }

    // Inline code first: other inline syntax inside a code span stays literal.
    val codeRanges = mutableListOf<IntRange>()
    for (match in INLINE_CODE.findAll(line)) {
        codeRanges += match.range
        out += span(markerStyle, offset + match.range.first, offset + match.range.first + 1)
        out += span(
            SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground),
            offset + match.range.first + 1,
            offset + match.range.last,
        )
        out += span(markerStyle, offset + match.range.last, offset + match.range.last + 1)
    }

    fun insideCode(range: IntRange) = codeRanges.any { it.first <= range.last && range.first <= it.last }

    for (match in BOLD.findAll(line)) {
        if (insideCode(match.range)) continue
        out += span(markerStyle, offset + match.range.first, offset + match.range.first + 2)
        out += span(
            SpanStyle(fontWeight = FontWeight.Bold),
            offset + match.range.first + 2,
            offset + match.range.last - 1,
        )
        out += span(markerStyle, offset + match.range.last - 1, offset + match.range.last + 1)
    }

    for (match in ITALIC.findAll(line)) {
        if (insideCode(match.range)) continue
        out += span(markerStyle, offset + match.range.first, offset + match.range.first + 1)
        out += span(
            SpanStyle(fontStyle = FontStyle.Italic),
            offset + match.range.first + 1,
            offset + match.range.last,
        )
        out += span(markerStyle, offset + match.range.last, offset + match.range.last + 1)
    }

    for (match in STRIKETHROUGH.findAll(line)) {
        if (insideCode(match.range)) continue
        out += span(markerStyle, offset + match.range.first, offset + match.range.first + 2)
        out += span(
            SpanStyle(textDecoration = TextDecoration.LineThrough),
            offset + match.range.first + 2,
            offset + match.range.last - 1,
        )
        out += span(markerStyle, offset + match.range.last - 1, offset + match.range.last + 1)
    }

    for (match in LINK.findAll(line)) {
        if (insideCode(match.range)) continue
        val label = match.groups[1]!!
        out += span(markerStyle, offset + match.range.first, offset + label.range.first)
        if (!label.range.isEmpty()) {
            out += span(
                SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
                offset + label.range.first,
                offset + label.range.last + 1,
            )
        }
        out += span(markerStyle, offset + label.range.last + 1, offset + match.range.last + 1)
    }
}

private fun headingStyle(level: Int): SpanStyle = when (level) {
    1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp)
    2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
    3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
    else -> SpanStyle(fontWeight = FontWeight.Bold)
}

private fun span(style: SpanStyle, start: Int, end: Int): AnnotatedString.Range<SpanStyle> =
    AnnotatedString.Range(style, start, end)
