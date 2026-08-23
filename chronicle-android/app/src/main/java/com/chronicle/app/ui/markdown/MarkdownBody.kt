package com.chronicle.app.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.theme.JournalSerif
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Shared CommonMark renderer (emphasis, lists, links, code). No arbitrary HTML.
 * Used by Capture preview, Timeline, Notes, and Portfolio.
 */
@Composable
fun MarkdownBody(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
    maxCollapsedHeight: Dp? = null,
    testTag: String = "markdown_body",
) {
    val display = content.ifBlank { " " }
    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onBackground,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
        tableBackground = MaterialTheme.colorScheme.surfaceContainer,
    )
    val typography = markdownTypography(
        text = style,
        code = style.copy(fontFamily = MaterialTheme.typography.bodySmall.fontFamily),
        inlineCode = style.copy(fontFamily = MaterialTheme.typography.bodySmall.fontFamily),
        quote = style.copy(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)),
        h1 = MaterialTheme.typography.titleLarge.copy(fontFamily = JournalSerif),
        h2 = MaterialTheme.typography.titleMedium.copy(fontFamily = JournalSerif),
        h3 = MaterialTheme.typography.titleSmall.copy(fontFamily = JournalSerif),
        h4 = style,
        h5 = style,
        h6 = style,
        paragraph = style,
        ordered = style,
        bullet = style,
        list = style,
        textLink = androidx.compose.ui.text.TextLinkStyles(
            style = style.copy(color = MaterialTheme.colorScheme.primary).toSpanStyle(),
        ),
    )
    val heightMod = if (maxCollapsedHeight != null) {
        Modifier.heightIn(max = maxCollapsedHeight)
    } else {
        Modifier
    }
    Markdown(
        content = display,
        colors = colors,
        typography = typography,
        modifier = modifier
            .fillMaxWidth()
            .then(heightMod)
            .testTag(testTag),
    )
}

/** Approximate 3-line clamp for collapsed Timeline cards (~18sp × 1.4 × 3). */
val CollapsedMarkdownMaxHeight: Dp = 76.dp
