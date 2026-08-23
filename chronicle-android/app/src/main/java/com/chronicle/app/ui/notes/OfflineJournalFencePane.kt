package com.chronicle.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chronicle.app.JournalFences
import com.chronicle.app.KnowledgePathMap
import com.chronicle.app.ui.components.ShimmerListPlaceholder
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.theme.ChronicleChrome
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.JournalSerif
import com.chronicle.app.ui.theme.isChronicleDark

/**
 * Read-only journal day view from local SAF markdown. Parses entry fences via
 * [JournalFences] — never writes fences on phone. Shows LAN amend CTA when offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineJournalFencePane(
    dayPath: String,
    loadDayText: (onDone: (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
    onAmendViaLan: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val date = remember(dayPath) { journalDateFromPath(dayPath) ?: dayPath }
    var dayText by remember(dayPath) { mutableStateOf<String?>(null) }
    var loading by remember(dayPath) { mutableStateOf(true) }

    LaunchedEffect(dayPath) {
        loading = true
        loadDayText { text ->
            dayText = text
            loading = false
        }
    }

    val entries = remember(dayText) {
        dayText?.let { JournalFences.splitEntries(it) }.orEmpty()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("journal_offline_fence_pane"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            date,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Offline · read-only fences",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("journal_offline_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChronicleChrome.topBarContainer(),
                    scrolledContainerColor = ChronicleChrome.topBarScrolled(),
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when {
            loading -> ShimmerListPlaceholder(
                rows = 4,
                rowHeight = 72.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("journal_offline_loading"),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item(key = "lan_cta") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    ) {
                        Text(
                            "Entry bodies are read-only offline. Connect to your Mac on LAN to amend fences.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                            ),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        if (onAmendViaLan != null) {
                            Button(
                                onClick = onAmendViaLan,
                                modifier = Modifier.testTag("journal_offline_lan_cta"),
                            ) { Text("Amend via Mac (LAN)") }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (dayText.isNullOrBlank()) {
                                "Could not load this journal day."
                            } else {
                                "No entry fences in this day file yet."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            ),
                        )
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    OfflineJournalFenceCard(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun OfflineJournalFenceCard(entry: JournalFences.FenceEntry) {
    val isDark = isChronicleDark()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, GlassTokens.hairlineBrush(isDark), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("journal_offline_entry_${entry.id}"),
    ) {
        Text(
            entry.id,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MarkdownBody(
            content = entry.body,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
            testTag = "journal_offline_body_${entry.id}",
        )
    }
}

private fun journalDateFromPath(path: String): String? {
    val p = KnowledgePathMap.norm(path)
    return Regex("""^40-Journal/(\d{4}-\d{2}-\d{2})\.md$""")
        .matchEntire(p)
        ?.groupValues
        ?.get(1)
}
