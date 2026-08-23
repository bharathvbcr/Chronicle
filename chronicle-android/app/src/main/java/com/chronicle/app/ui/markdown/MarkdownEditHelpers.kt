package com.chronicle.app.ui.markdown

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure helpers that insert CommonMark around the current selection (or at the cursor).
 * Stored entry text remains a plain markdown string — no HTML/AST schema.
 */
data class MarkdownEditResult(
    val text: String,
    val selection: TextRange,
)

fun wrapSelection(
    value: TextFieldValue,
    prefix: String,
    suffix: String = prefix,
    placeholder: String = "",
): MarkdownEditResult {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    val selected = text.substring(start, end)
    val inner = selected.ifEmpty { placeholder }
    val inserted = prefix + inner + suffix
    val newText = text.replaceRange(start, end, inserted)
    val newStart = start + prefix.length
    val newEnd = newStart + inner.length
    return MarkdownEditResult(newText, TextRange(newStart, newEnd))
}

fun toggleLinePrefix(
    value: TextFieldValue,
    marker: String,
): MarkdownEditResult {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    val lineStart = text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val lineEndExclusive = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val block = text.substring(lineStart, lineEndExclusive)
    val lines = if (block.isEmpty()) listOf("") else block.split('\n')
    val allPrefixed = lines.isNotEmpty() && lines.all { it.startsWith(marker) || it.isBlank() }
    val rewritten = lines.joinToString("\n") { line ->
        when {
            line.isBlank() && !allPrefixed -> marker
            allPrefixed && line.startsWith(marker) -> line.removePrefix(marker)
            allPrefixed -> line
            else -> marker + line
        }
    }
    val newText = text.replaceRange(lineStart, lineEndExclusive, rewritten)
    val newEnd = lineStart + rewritten.length
    return MarkdownEditResult(newText, TextRange(lineStart, newEnd))
}

fun insertBulletList(value: TextFieldValue): MarkdownEditResult =
    toggleLinePrefix(value, "- ")

fun insertNumberedList(value: TextFieldValue): MarkdownEditResult =
    toggleLinePrefix(value, "1. ")

fun insertBold(value: TextFieldValue): MarkdownEditResult =
    wrapSelection(value, "**", "**", "bold")

fun insertItalic(value: TextFieldValue): MarkdownEditResult =
    wrapSelection(value, "*", "*", "italic")

fun insertLink(value: TextFieldValue, url: String = "https://"): MarkdownEditResult {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    val selected = text.substring(start, end).ifEmpty { "link" }
    val inserted = "[$selected]($url)"
    val newText = text.replaceRange(start, end, inserted)
    // Select the URL so the user can replace it immediately.
    val urlStart = start + selected.length + 3 // "[" + selected + "]("
    val urlEnd = urlStart + url.length
    return MarkdownEditResult(newText, TextRange(urlStart, urlEnd))
}

fun applyMarkdownEdit(value: TextFieldValue, result: MarkdownEditResult): TextFieldValue =
    value.copy(text = result.text, selection = result.selection)
