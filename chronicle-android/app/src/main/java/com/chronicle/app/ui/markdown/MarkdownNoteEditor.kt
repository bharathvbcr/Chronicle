package com.chronicle.app.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.ui.theme.JournalSerif

/**
 * Shared markdown editor: toolbar + edit field / preview toggle.
 * Used by Knowledge notes; Capture keeps its own layout (mood/tags/media).
 */
@Composable
fun MarkdownNoteEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Write…",
    readOnly: Boolean = false,
    testTagPrefix: String = "md_editor",
) {
    var preview by remember { mutableStateOf(false) }
    val journalStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = JournalSerif,
        fontSize = 18.sp,
        lineHeight = 30.sp,
    )

    Column(modifier = modifier.fillMaxSize()) {
        if (!readOnly) {
            MarkdownToolbar(
                value = value,
                onValueChange = onValueChange,
                preview = preview,
                onPreviewToggle = { preview = !preview },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .testTag("${testTagPrefix}_toolbar"),
            )
        }

        if (!preview && !readOnly) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("${testTagPrefix}_field"),
                placeholder = {
                    Text(
                        placeholder,
                        style = journalStyle.copy(
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        ),
                    )
                },
                textStyle = journalStyle,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        if (preview || readOnly) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
                    .testTag("${testTagPrefix}_preview"),
            ) {
                MarkdownBody(
                    content = value.text.ifBlank { placeholder },
                    style = journalStyle,
                )
            }
        }
    }
}
