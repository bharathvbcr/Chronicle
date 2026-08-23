package com.chronicle.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.components.ChroniclePageHeader
import com.chronicle.app.ui.components.EmptyState
import com.chronicle.app.ui.components.ShimmerListPlaceholder
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.markdown.MarkdownNoteEditor
import com.chronicle.app.ui.notes.JournalFencePane
import com.chronicle.app.ui.notes.KbVisibleRow
import com.chronicle.app.ui.notes.OfflineJournalFencePane
import com.chronicle.app.ui.notes.buildKbSectionTree
import com.chronicle.app.ui.notes.defaultExpandedKbFolders
import com.chronicle.app.ui.notes.filterAndRankKbNotes
import com.chronicle.app.ui.notes.flattenVisibleKbTree
import com.chronicle.app.ui.notes.noteBreadcrumb
import com.chronicle.app.ui.notes.noteDisplayTitle
import com.chronicle.app.ui.theme.ChronicleChrome
import com.chronicle.app.ui.theme.JournalSerif
import kotlinx.coroutines.launch

// Escape `[` inside the character class — Android ICU treats bare `[` as a nested class
// and throws PatternSyntaxException at NotesScreen class init (app crash on Notes tab).
private val WIKILINK_RE = Regex("""\[\[([^\[\]|]+)(?:\|[^\[\]]+)?\]\]""")

internal fun parseWikilinks(text: String): List<String> =
    WIKILINK_RE.findAll(text).map { it.groupValues[1].trim() }.distinct().toList()

private fun isHubNote(note: KbNoteRef): Boolean =
    note.path == KnowledgePathMap.HOME_NOTE || note.name.startsWith("MOC-", ignoreCase = true)

private val FILE_TO_AREAS = listOf(
    "10-Work" to "Work",
    "20-Personal" to "Personal",
    "30-Knowledge" to "Knowledge",
    "90-Archive" to "Archive",
)

/** Build PARA destination when filing a note into another area. */
internal fun moveTargetForArea(fromPath: String, destArea: String): String? {
    val rel = KnowledgePathMap.validateKnowledgeRel(fromPath) ?: return null
    if (destArea !in KnowledgePathMap.PARA_AREAS) return null
    for (area in KnowledgePathMap.PARA_AREAS) {
        if (rel == area) return "$destArea/${rel.substringAfterLast('/')}"
        if (rel.startsWith("$area/")) {
            return "$destArea/${rel.removePrefix("$area/")}"
        }
    }
    if (rel == KnowledgePathMap.HOME_NOTE) return null
    return "$destArea/${rel.substringAfterLast('/')}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by viewModel.notes.collectAsState()
    val kbNotes by viewModel.kbNotes.collectAsState()
    val isLoading by viewModel.isLoadingNotes.collectAsState()
    val isLoadingKb by viewModel.isLoadingKbNotes.collectAsState()
    val isSavingKb by viewModel.isSavingKbNote.collectAsState()
    val selected by viewModel.selectedNotePath.collectAsState()
    val selectedKb by viewModel.selectedKbNotePath.collectAsState()
    val section by viewModel.notesSection.collectAsState()
    val lanHealthOk by viewModel.lanHealthOk.collectAsState()
    val serveBaseUrl by viewModel.serveBaseUrl.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newNoteName by remember { mutableStateOf("") }
    var createArea by remember {
        mutableStateOf(KnowledgePathMap.defaultCreateArea(KnowledgePathMap.SECTION_NOTES))
    }
    var createFolder by remember { mutableStateOf("") }
    var templates by remember { mutableStateOf<List<MarkdownFileMeta>>(emptyList()) }
    var selectedTemplate by remember { mutableStateOf<String?>(null) }
    var expandedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folderContext by remember { mutableStateOf<String?>(null) }

    val isKnowledgeSection =
        section == KnowledgePathMap.SECTION_KB || section == KnowledgePathMap.SECTION_NOTES
    val createAreas = when (section) {
        KnowledgePathMap.SECTION_KB -> listOf(KnowledgePathMap.KB_AREA)
        KnowledgePathMap.SECTION_NOTES -> KnowledgePathMap.NOTES_AREAS
        else -> emptyList()
    }

    LaunchedEffect(Unit) {
        viewModel.loadNotes(context)
        viewModel.loadKbNotes(context)
    }
    LaunchedEffect(showCreateDialog) {
        if (showCreateDialog) {
            viewModel.listKbTemplates(context) { templates = it }
        }
    }
    LaunchedEffect(selectedKb) {
        val openPath = selectedKb ?: return@LaunchedEffect
        folderContext = KnowledgePathMap.parentFolder(openPath).ifBlank { null }
    }

    // Bottom-bar action pill requests note creation while on knowledge sections.
    val newNoteRequest by viewModel.newNoteRequest.collectAsState()
    LaunchedEffect(newNoteRequest) {
        if (newNoteRequest <= 0) return@LaunchedEffect
        if (!isKnowledgeSection) return@LaunchedEffect
        val prefill = folderContext?.let { KnowledgePathMap.splitCreateFolderContext(it) }
        if (prefill != null) {
            createArea = prefill.area
            createFolder = prefill.folder
        } else {
            createArea = KnowledgePathMap.defaultCreateArea(section)
            createFolder = ""
        }
        showCreateDialog = true
    }

    val selectedNote = selected?.let { path -> notes.find { it.path == path } }
    val selectedKbNote = selectedKb?.let { path -> kbNotes.find { it.path == path } }

    if (showCreateDialog && isKnowledgeSection) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text(
                    if (section == KnowledgePathMap.SECTION_KB) {
                        "New knowledge note"
                    } else {
                        "New note"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newNoteName,
                        onValueChange = { newNoteName = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Skills") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("kb_note_create_name"),
                    )
                    OutlinedTextField(
                        value = createFolder,
                        onValueChange = { createFolder = it },
                        label = { Text("Folder (optional)") },
                        placeholder = { Text("e.g. Projects/Active") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("kb_note_create_folder"),
                    )
                    if (!folderContext.isNullOrBlank()) {
                        Text(
                            "Creates under $folderContext/",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            ),
                        )
                    }
                    if (templates.isNotEmpty()) {
                        Text(
                            "Template",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = selectedTemplate == null,
                                onClick = { selectedTemplate = null },
                                label = { Text("Blank") },
                            )
                            templates.forEach { template ->
                                FilterChip(
                                    selected = selectedTemplate == template.path,
                                    onClick = { selectedTemplate = template.path },
                                    label = { Text(template.name.removeSuffix(".md")) },
                                    modifier = Modifier.testTag("kb_note_template_${template.name}"),
                                )
                            }
                        }
                    }
                    Text(
                        "Area",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        createAreas.forEach { area ->
                            FilterChip(
                                selected = createArea == area,
                                onClick = { createArea = area },
                                label = { Text(area.substringAfter('-')) },
                                modifier = Modifier.testTag("kb_note_create_area_$area"),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newNoteName.trim()
                        if (name.isEmpty()) return@TextButton
                        val area = createArea
                        val createSection = section
                        val folder = createFolder.trim().ifBlank { null }
                        val templatePath = selectedTemplate
                        showCreateDialog = false
                        newNoteName = ""
                        createFolder = ""
                        selectedTemplate = null
                        createArea = KnowledgePathMap.defaultCreateArea(createSection)
                        fun doCreate(seedText: String?) {
                            viewModel.createKbNote(
                                context,
                                name,
                                area = area,
                                folder = folder,
                                text = seedText,
                                section = createSection,
                            ) { result ->
                                scope.launch {
                                    val message = when (result) {
                                        is CreateKbNoteResult.Success -> "Created"
                                        is CreateKbNoteResult.AlreadyExists -> "Note already exists"
                                        CreateKbNoteResult.InvalidPath -> "Invalid path"
                                        CreateKbNoteResult.NoVault -> "No vault selected"
                                        CreateKbNoteResult.WriteFailed -> "Create failed"
                                    }
                                    snackbarHostState.showSnackbar(message)
                                }
                            }
                        }
                        if (templatePath != null) {
                            viewModel.loadTemplateBody(context, templatePath) { body ->
                                val title = name.removeSuffix(".md").ifBlank { "Note" }
                                val seed = body?.let {
                                    NoteFrontmatter.applyTemplatePlaceholders(it, title)
                                }
                                doCreate(seed)
                            }
                        } else {
                            doCreate(null)
                        }
                    },
                    modifier = Modifier.testTag("kb_note_create_confirm"),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (selectedKbNote != null) {
        KbNoteEditor(
            note = selectedKbNote,
            kbNotes = kbNotes,
            journalNotes = notes,
            isSaving = isSavingKb,
            onBack = { viewModel.selectKbNote(null) },
            onSave = { text, onDone ->
                viewModel.saveKbNote(context, selectedKbNote.path, text) { ok ->
                    onDone(ok)
                    scope.launch {
                        snackbarHostState.showSnackbar(if (ok) "Saved" else "Save failed")
                    }
                }
            },
            onArchive = {
                viewModel.archiveKbNote(context, selectedKbNote.path) { ok ->
                    scope.launch {
                        snackbarHostState.showSnackbar(if (ok) "Archived" else "Archive failed")
                    }
                }
            },
            onDelete = {
                viewModel.deleteKbNote(context, selectedKbNote.path) { ok ->
                    scope.launch {
                        snackbarHostState.showSnackbar(if (ok) "Deleted" else "Delete failed")
                    }
                }
            },
            onMove = { destArea ->
                val target = moveTargetForArea(selectedKbNote.path, destArea)
                if (target == null) {
                    scope.launch { snackbarHostState.showSnackbar("Move failed") }
                    return@KbNoteEditor
                }
                if (destArea == "90-Archive") {
                    viewModel.archiveKbNote(context, selectedKbNote.path) { ok ->
                        scope.launch {
                            snackbarHostState.showSnackbar(if (ok) "Archived" else "Archive failed")
                        }
                    }
                } else {
                    viewModel.moveKbNote(context, selectedKbNote.path, target) { ok ->
                        scope.launch {
                            snackbarHostState.showSnackbar(if (ok) "Moved" else "Move failed")
                        }
                    }
                }
            },
            onOpenWikilink = { target ->
                val resolved = resolveWikilinkTarget(target, kbNotes, notes)
                if (resolved != null) viewModel.openNote(context, resolved)
            },
            loadBody = { onDone ->
                viewModel.loadKbNoteBody(context, selectedKbNote.path, onDone)
            },
            contentPadding = contentPadding,
        )
        return
    }

    if (selectedNote != null) {
        val onlineFence = KnowledgePathMap.isJournalFencePath(selectedNote.path) &&
            lanHealthOk == true &&
            serveBaseUrl.isNotBlank()
        if (onlineFence) {
            JournalFencePane(
                viewModel = viewModel,
                dayPath = selectedNote.path,
                offlineFallback = selectedNote,
                onBack = { viewModel.selectNote(null) },
                contentPadding = contentPadding,
            )
        } else if (KnowledgePathMap.isJournalFencePath(selectedNote.path)) {
            OfflineJournalFencePane(
                dayPath = selectedNote.path,
                loadDayText = { onDone ->
                    viewModel.loadNoteBody(context, selectedNote.path, onDone)
                },
                onBack = { viewModel.selectNote(null) },
                onAmendViaLan = if (serveBaseUrl.isNotBlank()) {
                    { scope.launch { snackbarHostState.showSnackbar("Connect on LAN to amend journal fences") } }
                } else {
                    null
                },
                contentPadding = contentPadding,
            )
        } else {
            val readOnlyLabel = when {
                selectedNote.path == "Upcoming.md" ||
                    selectedNote.category == "derived" ||
                    KnowledgePathMap.isJournalDerivedPath(selectedNote.path) -> "Derived · read-only"
                else -> "Read-only"
            }
            NoteDetail(
                note = selectedNote,
                onBack = { viewModel.selectNote(null) },
                onCopy = { text ->
                    copyToClipboard(context, text)
                    scope.launch { snackbarHostState.showSnackbar("Copied note") }
                },
                loadBody = { onDone ->
                    viewModel.loadNoteBody(context, selectedNote.path, onDone)
                },
                contentPadding = contentPadding,
                readOnlyLabel = readOnlyLabel,
            )
        }
        return
    }

    val filteredJournal = remember(notes, searchQuery) {
        val q = searchQuery.trim().lowercase()
        notes.filter { note ->
            q.isEmpty() ||
                note.path.lowercase().contains(q) ||
                note.name.lowercase().contains(q) ||
                note.text.lowercase().contains(q)
        }
    }
    val sectionKbNotes = remember(kbNotes, section) {
        kbNotes.filter { KnowledgePathMap.sectionFor(it.path) == section }
    }
    val isSearching = searchQuery.isNotBlank()
    val searchMatches = remember(sectionKbNotes, searchQuery) {
        filterAndRankKbNotes(sectionKbNotes, searchQuery)
    }
    val kbTree = remember(sectionKbNotes, kbNotes, section) {
        buildKbSectionTree(sectionKbNotes, section, allNotes = kbNotes)
    }
    LaunchedEffect(section, kbTree.areaRoots.map { it.path }) {
        expandedFolders = defaultExpandedKbFolders(kbTree.areaRoots, section)
    }
    val visibleRows = remember(kbTree.areaRoots, expandedFolders) {
        flattenVisibleKbTree(kbTree.areaRoots, expandedFolders)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ChroniclePageHeader(
                title = "Notes",
                overline = "Vault",
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                FilterChip(
                    selected = section == KnowledgePathMap.SECTION_KB,
                    onClick = {
                        searchQuery = ""
                        folderContext = null
                        viewModel.setNotesSection(KnowledgePathMap.SECTION_KB)
                    },
                    label = { Text("Knowledge Base") },
                    modifier = Modifier.testTag("notes_section_kb"),
                )
                FilterChip(
                    selected = section == KnowledgePathMap.SECTION_NOTES,
                    onClick = {
                        searchQuery = ""
                        folderContext = null
                        viewModel.setNotesSection(KnowledgePathMap.SECTION_NOTES)
                    },
                    label = { Text("Notes") },
                    modifier = Modifier.testTag("notes_section_notes"),
                )
                FilterChip(
                    selected = section == KnowledgePathMap.SECTION_JOURNAL,
                    onClick = {
                        searchQuery = ""
                        folderContext = null
                        viewModel.setNotesSection(KnowledgePathMap.SECTION_JOURNAL)
                    },
                    label = { Text("Journal") },
                    modifier = Modifier.testTag("notes_section_journal"),
                )
            }

            Text(
                when (section) {
                    KnowledgePathMap.SECTION_KB ->
                        "30-Knowledge · editable offline"
                    KnowledgePathMap.SECTION_NOTES ->
                        "Inbox, Work, Personal, Archive · editable offline"
                    else ->
                        "40-Journal fences + derived · amend via Mac when online"
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                ),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notes_search"),
                placeholder = {
                    Text(
                        when (section) {
                            KnowledgePathMap.SECTION_KB -> "Filter notes…"
                            KnowledgePathMap.SECTION_NOTES -> "Filter notes…"
                            else -> "Filter journal…"
                        },
                    )
                },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            when {
                (section == KnowledgePathMap.SECTION_JOURNAL && isLoading) ||
                    (isKnowledgeSection && isLoadingKb) -> ShimmerListPlaceholder(
                    rows = 6,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("notes_loading"),
                )

                isKnowledgeSection && isSearching && searchMatches.isEmpty() -> EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = "No matches",
                    message = "No notes match this filter.",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("kb_notes_empty"),
                )

                isKnowledgeSection && isSearching -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 88.dp,
                        ),
                        modifier = Modifier.testTag("kb_notes_tree"),
                    ) {
                        items(searchMatches, key = { "search_${it.path}" }) { note ->
                            CompactNoteRow(
                                title = noteDisplayTitle(note.name),
                                breadcrumb = noteBreadcrumb(note.path),
                                testTag = "kb_note_row_${note.path}",
                                onClick = { viewModel.openNote(context, note.path) },
                            )
                        }
                    }
                }

                isKnowledgeSection -> {
                    val sectionEmpty = sectionKbNotes.isEmpty() && kbTree.areaRoots.isEmpty()
                    LazyColumn(
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 88.dp,
                        ),
                        modifier = Modifier.testTag("kb_notes_tree"),
                    ) {
                        item(key = "hub_hdr") {
                            Text(
                                "Hub",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                        }
                        items(kbTree.hubs, key = { "hub_${it.path}" }) { note ->
                            CompactNoteRow(
                                title = noteDisplayTitle(note.name),
                                breadcrumb = noteBreadcrumb(note.path).ifEmpty { null },
                                badge = if (isHubNote(note)) "Hub" else null,
                                testTag = "kb_note_row_${note.path}",
                                onClick = { viewModel.openNote(context, note.path) },
                            )
                        }
                        item(key = "hub_upcoming") {
                            UpcomingHubRow {
                                viewModel.openNote(context, "Upcoming.md")
                            }
                        }
                        if (sectionEmpty) {
                            item(key = "kb_empty") {
                                EmptyState(
                                    icon = if (section == KnowledgePathMap.SECTION_KB) {
                                        Icons.Default.MenuBook
                                    } else {
                                        Icons.Default.Description
                                    },
                                    title = if (section == KnowledgePathMap.SECTION_KB) {
                                        "No knowledge notes yet"
                                    } else {
                                        "No notes yet"
                                    },
                                    message = if (section == KnowledgePathMap.SECTION_KB) {
                                        "Create one under 30-Knowledge with the + button."
                                    } else {
                                        "Create one in Inbox or another Notes area with the + button."
                                    },
                                    modifier = Modifier
                                        .padding(vertical = 8.dp)
                                        .testTag("kb_notes_empty"),
                                )
                            }
                        }
                        items(
                            visibleRows,
                            key = { row ->
                                when (row) {
                                    is KbVisibleRow.Folder -> "folder_${row.path}"
                                    is KbVisibleRow.File -> "file_${row.note.path}"
                                }
                            },
                        ) { row ->
                            when (row) {
                                is KbVisibleRow.Folder -> KbFolderRow(
                                    path = row.path,
                                    name = row.name,
                                    depth = row.depth,
                                    noteCount = row.noteCount,
                                    expanded = row.expanded,
                                    selected = folderContext == row.path,
                                    onToggle = {
                                        folderContext = row.path
                                        expandedFolders = if (row.path in expandedFolders) {
                                            expandedFolders - row.path
                                        } else {
                                            expandedFolders + row.path
                                        }
                                    },
                                )
                                is KbVisibleRow.File -> CompactNoteRow(
                                    title = noteDisplayTitle(row.note.name),
                                    breadcrumb = null,
                                    depth = row.depth,
                                    testTag = "kb_note_row_${row.note.path}",
                                    onClick = { viewModel.openNote(context, row.note.path) },
                                )
                            }
                        }
                    }
                }

                section == KnowledgePathMap.SECTION_JOURNAL && filteredJournal.isEmpty() -> EmptyState(
                    icon = if (notes.isEmpty()) Icons.Default.CalendarMonth else Icons.Default.SearchOff,
                    title = if (notes.isEmpty()) "No journal days yet" else "No matches",
                    message = if (notes.isEmpty()) {
                        "Process on your Mac to file entries into 40-Journal/."
                    } else {
                        "No notes match this search."
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("notes_empty"),
                )

                else -> {
                    val journalDays = filteredJournal.filter {
                        it.category == "journal" || KnowledgePathMap.isJournalFencePath(it.path)
                    }
                    val derived = filteredJournal.filter {
                        it.category != "journal" && !KnowledgePathMap.isJournalFencePath(it.path)
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                    ) {
                        if (journalDays.isNotEmpty()) {
                            item(key = "hdr_journal") {
                                Text(
                                    "Journal",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    ),
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(journalDays, key = { it.path }) { note ->
                                CompactNoteRow(
                                    title = noteDisplayTitle(note.name),
                                    breadcrumb = noteBreadcrumb(note.path).ifEmpty { null },
                                    testTag = "note_row_${note.path}",
                                    onClick = { viewModel.selectNote(note.path) },
                                )
                            }
                        }
                        if (derived.isNotEmpty()) {
                            item(key = "hdr_derived") {
                                Text(
                                    "Derived",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    ),
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(derived, key = { it.path }) { note ->
                                CompactNoteRow(
                                    title = noteDisplayTitle(note.name),
                                    breadcrumb = noteBreadcrumb(note.path).ifEmpty { null },
                                    testTag = "note_row_${note.path}",
                                    onClick = { viewModel.selectNote(note.path) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingHubRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .testTag("kb_hub_upcoming"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Upcoming",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Journal",
            style = MaterialTheme.typography.labelSmall.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun KbFolderRow(
    path: String,
    name: String,
    depth: Int,
    noteCount: Int,
    expanded: Boolean,
    selected: Boolean = false,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onToggle)
            .padding(start = (depth * 12).dp, top = 8.dp, bottom = 8.dp)
            .testTag("kb_tree_folder_$path"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
        )
        Text(
            "$name · $noteCount",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactNoteRow(
    title: String,
    breadcrumb: String? = null,
    badge: String? = null,
    depth: Int = 0,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (depth * 12).dp)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (!breadcrumb.isNullOrBlank()) {
            Text(
                breadcrumb,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KbNoteEditor(
    note: KbNoteRef,
    kbNotes: List<KbNoteRef>,
    journalNotes: List<NoteRef>,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: (String, (Boolean) -> Unit) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMove: (String) -> Unit,
    onOpenWikilink: (String) -> Unit,
    loadBody: (onDone: (String?) -> Unit) -> Unit,
    contentPadding: PaddingValues,
) {
    var savedText by remember(note.path) { mutableStateOf<String?>(null) }
    var fieldValue by remember(note.path) { mutableStateOf(TextFieldValue("")) }
    var loadingBody by remember(note.path) { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showFileToSheet by remember { mutableStateOf(false) }
    var pendingWikilink by remember { mutableStateOf<String?>(null) }
    val dirty = savedText != null && fieldValue.text != savedText
    val wikilinks = remember(fieldValue.text) { parseWikilinks(fieldValue.text) }

    LaunchedEffect(note.path) {
        loadingBody = true
        loadBody { body ->
            val text = body.orEmpty()
            savedText = text
            fieldValue = TextFieldValue(text)
            loadingBody = false
        }
    }

    fun requestBack() {
        if (dirty) {
            pendingWikilink = null
            showDiscardConfirm = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = dirty) { requestBack() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        note.path,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }, modifier = Modifier.testTag("kb_note_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showFileToSheet = true },
                        modifier = Modifier.testTag("kb_note_file_to"),
                    ) {
                        Icon(Icons.Default.DriveFileMove, contentDescription = "File to")
                    }
                    IconButton(
                        onClick = onArchive,
                        modifier = Modifier.testTag("kb_note_archive"),
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = "Archive")
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("kb_note_delete"),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                    TextButton(
                        onClick = {
                            onSave(fieldValue.text) { ok ->
                                if (ok) savedText = fieldValue.text
                            }
                        },
                        enabled = dirty && !isSaving && !loadingBody,
                        modifier = Modifier.testTag("kb_note_save"),
                    ) {
                        Text(if (isSaving) "Saving…" else "Save")
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
        if (loadingBody) {
            ShimmerListPlaceholder(
                rows = 4,
                rowHeight = 72.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("note_editor_loading"),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
            ) {
                if (wikilinks.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                    ) {
                        wikilinks.forEach { target ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    if (resolveWikilinkTarget(target, kbNotes, journalNotes) == null) {
                                        return@FilterChip
                                    }
                                    if (dirty) pendingWikilink = target else onOpenWikilink(target)
                                },
                                label = { Text("[[$target]]") },
                                modifier = Modifier.testTag("kb_wikilink_$target"),
                            )
                        }
                    }
                }
                MarkdownNoteEditor(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    modifier = Modifier.fillMaxSize(),
                    placeholder = "Write your knowledge note…",
                    testTagPrefix = "kb_note",
                )
            }
        }
    }

    if (showFileToSheet) {
        AlertDialog(
            onDismissRequest = { showFileToSheet = false },
            title = { Text("File to…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FILE_TO_AREAS.forEach { (area, label) ->
                        TextButton(
                            onClick = {
                                showFileToSheet = false
                                onMove(area)
                            },
                            modifier = Modifier.testTag("kb_file_to_$area"),
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFileToSheet = false }) { Text("Cancel") }
            },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved edits. Leave this note without saving?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        val link = pendingWikilink
                        pendingWikilink = null
                        if (link != null) {
                            onOpenWikilink(link)
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.testTag("kb_note_discard_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    pendingWikilink = null
                }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete note?") },
            text = { Text("Delete ${note.path}? Prefer Archive when possible.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("kb_note_delete_confirm"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetail(
    note: NoteRef,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    loadBody: (onDone: (String?) -> Unit) -> Unit,
    contentPadding: PaddingValues,
    readOnlyLabel: String = "Read-only",
) {
    var body by remember(note.path) { mutableStateOf(note.text) }
    var loading by remember(note.path) { mutableStateOf(true) }

    LaunchedEffect(note.path) {
        loading = true
        loadBody { loaded ->
            if (loaded != null) body = loaded
            loading = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            note.path,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            readOnlyLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("notes_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onCopy(body) },
                        enabled = !loading,
                        modifier = Modifier.testTag("notes_copy"),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
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
        if (loading) {
            ShimmerListPlaceholder(
                rows = 5,
                rowHeight = 64.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("readonly_note_loading"),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                MarkdownBody(
                    content = body,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                    testTag = "note_markdown",
                )
            }
        }
    }
}

internal fun copyToClipboard(context: Context, text: String, label: String = "Chronicle") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
