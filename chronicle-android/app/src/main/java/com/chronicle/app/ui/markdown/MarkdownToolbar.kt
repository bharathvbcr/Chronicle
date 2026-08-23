package com.chronicle.app.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    preview: Boolean = false,
    onPreviewToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag("markdown_toolbar"),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIcon(
            imageVector = Icons.Default.FormatBold,
            contentDescription = "Bold",
            testTag = "md_bold",
            enabled = !preview,
        ) {
            onValueChange(applyMarkdownEdit(value, insertBold(value)))
        }
        ToolbarIcon(
            imageVector = Icons.Default.FormatItalic,
            contentDescription = "Italic",
            testTag = "md_italic",
            enabled = !preview,
        ) {
            onValueChange(applyMarkdownEdit(value, insertItalic(value)))
        }
        ToolbarIcon(
            imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
            contentDescription = "Bullet list",
            testTag = "md_bullet",
            enabled = !preview,
        ) {
            onValueChange(applyMarkdownEdit(value, insertBulletList(value)))
        }
        ToolbarIcon(
            imageVector = Icons.Default.FormatListNumbered,
            contentDescription = "Numbered list",
            testTag = "md_numbered",
            enabled = !preview,
        ) {
            onValueChange(applyMarkdownEdit(value, insertNumberedList(value)))
        }
        ToolbarIcon(
            imageVector = Icons.Default.Link,
            contentDescription = "Link",
            testTag = "md_link",
            enabled = !preview,
        ) {
            onValueChange(applyMarkdownEdit(value, insertLink(value)))
        }
        if (onPreviewToggle != null) {
            ToolbarIcon(
                imageVector = if (preview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (preview) "Edit" else "Preview",
                testTag = "md_preview_toggle",
                enabled = true,
                onClick = onPreviewToggle,
            )
        }
    }
}

@Composable
private fun ToolbarIcon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).testTag(testTag),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
