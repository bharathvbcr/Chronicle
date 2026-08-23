package com.chronicle.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.chronicle.app.KnowledgePathMap
import com.chronicle.app.MainViewModel
import com.chronicle.app.NoteRef
import com.chronicle.app.net.ServeClient
import com.chronicle.app.ui.components.ShimmerListPlaceholder
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.theme.ChronicleChrome
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.JournalSerif
import com.chronicle.app.ui.theme.isChronicleDark

/**
 * Online journal day fence editor (LAN serve). Lists entry fences for
 * `40-Journal/YYYY-MM-DD.md`, with hash-gated amend + 409 conflict refresh.
 * Falls back to [offlineFallback] markdown when load fails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalFencePane(
    viewModel: MainViewModel,
    dayPath: String,
    offlineFallback: NoteRef,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val date = remember(dayPath) { journalDateFromPath(dayPath) ?: dayPath }
    var entries by remember(dayPath) {
        mutableStateOf<Map<String, ServeClient.JournalEntryBody>>(emptyMap())
    }
    var drafts by remember(dayPath) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editingId by remember(dayPath) { mutableStateOf<String?>(null) }
    var savingId by remember(dayPath) { mutableStateOf<String?>(null) }
    var loading by remember(dayPath) { mutableStateOf(true) }
    var loadFailed by remember(dayPath) { mutableStateOf(false) }
    var error by remember(dayPath) { mutableStateOf<String?>(null) }
    var conflict by remember(dayPath) {
        mutableStateOf<Pair<String, ServeClient.JournalAmendConflict>?>(null)
    }

    fun reload(keepDrafts: Boolean = true) {
        loading = true
        loadFailed = false
        error = null
        viewModel.loadJournalDayFences(dayPath) { result ->
            result.fold(
                onSuccess = { list ->
                    entries = list.associateBy { it.id }
                    if (!keepDrafts) drafts = emptyMap()
                    loading = false
                    loadFailed = false
                },
                onFailure = { e ->
                    error = e.message
                    loading = false
                    loadFailed = true
                },
            )
        }
    }

    LaunchedEffect(dayPath) {
        reload(keepDrafts = false)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("journal_fence_pane"),
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
                            "Journal fences · LAN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("journal_fence_back")) {
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
                    .testTag("journal_fence_loading"),
            )

            loadFailed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp)
                    .testTag("journal_fence_offline_fallback"),
            ) {
                Text(
                    "Offline · read-only",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        ),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                MarkdownBody(
                    content = offlineFallback.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                    testTag = "journal_fence_fallback_markdown",
                )
            }

            else -> {
                val ordered = entries.values.toList()
                LazyColumn(
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
                    if (error != null) {
                        item(key = "err") {
                            Text(
                                error!!,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("journal_fence_error"),
                            )
                        }
                    }
                    if (ordered.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "No entries filed for this day.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                ),
                            )
                        }
                    }
                    items(ordered, key = { it.id }) { entry ->
                        JournalFenceEntryCard(
                            entry = entry,
                            draft = drafts[entry.id],
                            editing = editingId == entry.id,
                            saving = savingId == entry.id,
                            onStartEdit = {
                                drafts = drafts + (entry.id to entry.body)
                                editingId = entry.id
                            },
                            onCancelEdit = { editingId = null },
                            onDraftChange = { text ->
                                drafts = drafts + (entry.id to text)
                            },
                            onSave = {
                                val draft = drafts[entry.id] ?: return@JournalFenceEntryCard
                                savingId = entry.id
                                error = null
                                viewModel.amendJournalFence(entry.id, draft, entry.bodyHash) { result ->
                                    savingId = null
                                    result.fold(
                                        onSuccess = { amend ->
                                            entries = entries + (entry.id to entry.copy(
                                                body = draft,
                                                bodyHash = amend.hash.ifBlank { entry.bodyHash },
                                                filedContentHash = amend.hash.ifBlank { entry.filedContentHash },
                                                editable = true,
                                            ))
                                            editingId = null
                                        },
                                        onFailure = { e ->
                                            if (e is ServeClient.JournalAmendConflict) {
                                                conflict = entry.id to e
                                            } else {
                                                error = e.message ?: "Save failed"
                                            }
                                        },
                                    )
                                }
                            },
                            onAcceptDisk = {
                                savingId = entry.id
                                error = null
                                viewModel.acceptJournalDisk(entry.id) { result ->
                                    savingId = null
                                    result.fold(
                                        onSuccess = { accept ->
                                            entries = entries + (entry.id to entry.copy(
                                                bodyHash = accept.hash.ifBlank { entry.bodyHash },
                                                filedContentHash = accept.hash.ifBlank { entry.filedContentHash },
                                                editable = true,
                                            ))
                                        },
                                        onFailure = { e ->
                                            error = e.message ?: "Accept disk failed"
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val conflictPair = conflict
    if (conflictPair != null) {
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Entry edited elsewhere") },
            text = {
                Text(
                    "This entry was edited outside the app (Obsidian or Mac tools) since you loaded it. " +
                        "Refresh to load the latest text — your draft stays in the editor so you can compare and re-apply manually.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = conflictPair.first
                        conflict = null
                        viewModel.loadJournalDayFences(dayPath) { result ->
                            result.onSuccess { list ->
                                val fresh = list.find { it.id == id }
                                if (fresh != null) {
                                    entries = entries + (id to fresh)
                                } else {
                                    entries = list.associateBy { it.id }
                                }
                                // Keep draft so the user can compare / re-apply.
                            }.onFailure { e ->
                                error = e.message ?: "Refresh failed"
                            }
                        }
                    },
                    modifier = Modifier.testTag("journal_fence_conflict_refresh"),
                ) { Text("Refresh") }
            },
            dismissButton = {
                TextButton(onClick = { conflict = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun JournalFenceEntryCard(
    entry: ServeClient.JournalEntryBody,
    draft: String?,
    editing: Boolean,
    saving: Boolean,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onAcceptDisk: () -> Unit,
) {
    val isDark = isChronicleDark()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, GlassTokens.hairlineBrush(isDark), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("journal_fence_entry_${entry.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                entry.id,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                !entry.editable -> {
                    Text(
                        "edited outside · read-only",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        ),
                    )
                    TextButton(
                        onClick = onAcceptDisk,
                        enabled = !saving,
                        modifier = Modifier.testTag("journal_fence_accept_disk_${entry.id}"),
                    ) { Text(if (saving) "…" else "Accept disk") }
                }
                editing -> {
                    TextButton(
                        onClick = onCancelEdit,
                        enabled = !saving,
                        modifier = Modifier.testTag("journal_fence_cancel_${entry.id}"),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = onSave,
                        enabled = !saving,
                        modifier = Modifier.testTag("journal_fence_save_${entry.id}"),
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
                else -> TextButton(
                    onClick = onStartEdit,
                    modifier = Modifier.testTag("journal_fence_edit_${entry.id}"),
                ) { Text("Edit") }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (editing) {
            OutlinedTextField(
                value = draft ?: entry.body,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag("journal_fence_draft_${entry.id}"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                minLines = 4,
            )
        } else {
            MarkdownBody(
                content = entry.body,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                testTag = "journal_fence_body_${entry.id}",
            )
        }
    }
}

private fun journalDateFromPath(path: String): String? {
    val p = KnowledgePathMap.norm(path)
    return Regex("""^40-Journal/(\d{4}-\d{2}-\d{2})\.md$""")
        .matchEntire(p)
        ?.groupValues
        ?.get(1)
}
