package com.chronicle.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.ui.graphics.vector.ImageVector

/** Icons for entry types — shared by Capture chips and Timeline. */
fun entryTypeIcon(type: String): ImageVector = when (type.lowercase()) {
    "idea" -> Icons.Default.Lightbulb
    "dream" -> Icons.Default.Bedtime
    "reflection" -> Icons.Default.Psychology
    else -> Icons.AutoMirrored.Filled.Notes
}
