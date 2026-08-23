package com.chronicle.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import com.chronicle.app.brain.GraphNode
import com.chronicle.app.net.ServeClient
import com.chronicle.app.ui.components.ChroniclePageHeader
import com.chronicle.app.ui.components.ShimmerListPlaceholder
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.JournalSerif
import com.chronicle.app.ui.theme.SemanticColors
import com.chronicle.app.ui.theme.isChronicleDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class BrainChatTurn(
    val role: String,
    val content: String,
    val citations: List<ServeClient.Citation> = emptyList(),
    val degraded: Boolean = false,
    val offlineSummary: String? = null,
)

private val RECALL_SCOPES = listOf(
    "all" to "All",
    "journal" to "Journal",
    "kb" to "Knowledge",
)

/**
 * Unified Brain workspace (PC parity): top search/recall + force graph + docked transcript.
 * Online: tap node → seed recall; citations highlight nodes.
 * Offline: graph browse + local search only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val graph by viewModel.brainGraph.collectAsState()
    val serveUrl by viewModel.serveBaseUrl.collectAsState()
    val serveToken by viewModel.serveToken.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val lanHealthOk by viewModel.lanHealthOk.collectAsState()
    val client = remember(serveToken) { ServeClient(tokenProvider = { serveToken }) }

    var seedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var highlightIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Drives MindMap 2-hop focus from Send (label/citation match); not auto-seed. */
    var mapFocusId by remember { mutableStateOf<String?>(null) }
    var chatExpanded by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf(listOf<BrainChatTurn>()) }
    var busy by remember { mutableStateOf(false) }
    var recallScope by remember { mutableStateOf("all") }

    val lanReady = serveUrl.isNotBlank() && lanHealthOk == true

    LaunchedEffect(serveUrl) {
        viewModel.checkLanHealth(context)
    }

    fun labelMatchIds(query: String): List<String> =
        graph?.nodes.orEmpty()
            .filter { n ->
                n.label.contains(query, ignoreCase = true) || n.id.contains(query, ignoreCase = true)
            }
            .map { it.id }

    fun toggleSeed(node: GraphNode) {
        seedIds = if (node.id in seedIds) {
            seedIds - node.id
        } else {
            (seedIds + node.id).takeLast(8)
        }
        highlightIds = seedIds.toSet()
        chatExpanded = true
    }

    fun onCitationClick(citation: ServeClient.Citation) {
        val nodes = citation.nodeIds
        if (nodes.isNotEmpty()) {
            highlightIds = (highlightIds + nodes).toSet()
            seedIds = (nodes + seedIds).distinct().take(8)
        }
        when {
            citation.kind == "entry" || citation.id.startsWith("20") -> {
                viewModel.openNote(context, citation.path ?: citation.id, kind = "entry")
            }
            !citation.path.isNullOrBlank() -> {
                viewModel.openNote(context, citation.path, kind = citation.kind)
            }
            citation.kind == "kb" || citation.kind == "note" -> {
                viewModel.openNote(context, citation.id, kind = citation.kind)
            }
        }
    }

    fun sendRecall() {
        val msg = input.trim()
        if (msg.isEmpty() || busy) return
        chatExpanded = true

        // Offline: local entry search + optional provider (Grok/Ollama/Nano) over snippets
        if (!lanReady) {
            input = ""
            turns = turns + BrainChatTurn("user", msg)
            busy = true
            scope.launch {
                val q = msg.lowercase()
                val hits = entries.filter { e ->
                    e.text.lowercase().contains(q) || e.tags.any { it.lowercase().contains(q) }
                }.take(8)
                val graphLabelHits = labelMatchIds(msg)
                val body = when {
                    hits.isNotEmpty() -> hits.joinToString("\n\n") { e ->
                        "**${e.id}** (${e.type})\n${e.text.take(220)}"
                    }
                    graphLabelHits.isNotEmpty() ->
                        "No local journal matches — focused matching graph nodes."
                    else ->
                        "No local matches. Connect to your Mac (Settings → scan QR) for full vault recall."
                }
                val providerAnswer = if (hits.isNotEmpty()) {
                    viewModel.lightweightProviderRecall(
                        context,
                        question = msg,
                        snippets = hits.map { it.text },
                    )
                } else {
                    null
                }
                val summary = providerAnswer ?: if (hits.isNotEmpty()) {
                    viewModel.summarizeRecallOffline(
                        context,
                        answer = body,
                        snippets = hits.map { it.text.take(120) },
                    )
                } else {
                    null
                }
                // Highlight graph nodes that match hit entry ids + label/id query
                val entryNodeIds = hits.map { "entry:${it.id}" }.toSet()
                highlightIds = (entryNodeIds + graphLabelHits).toSet()
                // 2-hop focus like old graph search (do not auto-seed)
                mapFocusId = graphLabelHits.firstOrNull()
                turns = turns + BrainChatTurn(
                    role = "assistant",
                    content = body,
                    degraded = true,
                    offlineSummary = summary,
                )
                busy = false
            }
            return
        }

        val base = ServeClient.normalizeBaseUrl(serveUrl)
        input = ""
        turns = turns + BrainChatTurn("user", msg)
        busy = true
        scope.launch {
            try {
                val history = turns.filter { it.role == "user" || it.role == "assistant" }
                    .dropLast(1)
                    .takeLast(6)
                    .map { it.role to it.content }
                val result = withContext(Dispatchers.IO) {
                    client.recall(
                        baseUrl = base,
                        message = msg,
                        history = history,
                        scope = recallScope,
                        nodeIds = seedIds,
                    )
                }
                val cited = ServeClient.citationNodeIds(result.citations)
                val graphLabelHits = labelMatchIds(msg)
                highlightIds = (cited + result.seedNodeIds + seedIds + graphLabelHits).toSet()
                // Prefer citation nodes for focus when present, else first label hit
                mapFocusId = cited.firstOrNull() ?: graphLabelHits.firstOrNull()
                turns = turns + BrainChatTurn(
                    role = "assistant",
                    content = result.answer,
                    citations = result.citations,
                    degraded = result.degraded,
                )
            } catch (e: Exception) {
                turns = turns + BrainChatTurn(
                    role = "assistant",
                    content = "Could not reach Chronicle serve.\n${e.message ?: "error"}",
                    degraded = true,
                )
            } finally {
                busy = false
            }
        }
    }

    // Plain Column root (same pattern as Capture/Timeline): a single
    // statusBarsPadding here — the old Scaffold + safeDrawing insets applied
    // the status-bar inset twice, leaving a dead gap above the header.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = contentPadding.calculateBottomPadding())
            .testTag("brain_screen"),
    ) {
        ChroniclePageHeader(
            title = "Brain",
            overline = "Graph & Recall",
        ) {
            BrainStatusChip(healthOk = lanHealthOk)
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    viewModel.navigateTo(Screen.SETTINGS)
                },
                modifier = Modifier.testTag("brain_settings"),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        // Top search/recall — always visible (Timeline-style); transcript stays docked below.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("brain_recall_input"),
                placeholder = {
                    Text(if (lanReady) "Ask anything…" else "Search local entries…")
                },
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendRecall() }),
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        if (lanReady) Icons.Default.AutoAwesome else Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { sendRecall() },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.testTag("brain_recall_send"),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }

        if (lanReady) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .testTag("brain_scope_row"),
            ) {
                RECALL_SCOPES.forEach { (value, label) ->
                    FilterChip(
                        selected = recallScope == value,
                        onClick = { recallScope = value },
                        label = { Text(label) },
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(10.dp)
                    .testTag("brain_offline_banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No Mac on LAN — searching local entries. Long-press a node to focus the graph.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (seedIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .testTag("brain_seed_row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Seeds",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                seedIds.take(4).forEach { id ->
                    val label = graph?.nodes?.find { it.id == id }?.label ?: id.substringAfter(':')
                    FilterChip(
                        selected = true,
                        onClick = { seedIds = seedIds - id; highlightIds = seedIds.toSet() },
                        label = { Text(label.take(18)) },
                    )
                }
                TextButton(onClick = { seedIds = emptyList(); highlightIds = emptySet() }) {
                    Text("Clear")
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            MindMapScreen(
                graph = graph,
                viewModel = viewModel,
                onLoadArchive = { year -> viewModel.loadArchiveYear(context, year) },
                highlightNodeIds = highlightIds,
                seedNodeIds = seedIds.toSet(),
                onNodeTap = { toggleSeed(it) },
                showOverflowMenu = false,
                externalFocusId = mapFocusId,
            )
        }

        // Docked transcript — search lives at top.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
                .border(
                    1.dp,
                    GlassTokens.hairlineBrush(
                        isChronicleDark(),
                    ),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                )
                .testTag("brain_recall_dock"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { chatExpanded = !chatExpanded },
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (lanReady) "Answers" else "Results",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (chatExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (chatExpanded) "Collapse" else "Expand",
                    )
                }
            }

            AnimatedVisibility(
                visible = chatExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    if (turns.isEmpty()) {
                        item {
                            Text(
                                if (lanReady) {
                                    "Tap a node to seed recall, then ask across journal & knowledge."
                                } else {
                                    "Ask above to search local entries, or browse the graph."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                    fontFamily = JournalSerif,
                                ),
                            )
                        }
                    }
                    items(turns, key = { "${it.role}_${it.content.hashCode()}" }) { turn ->
                        BrainBubble(
                            turn = turn,
                            onCitationClick = { onCitationClick(it) },
                            onSummarize = { content, cites ->
                                scope.launch {
                                    val summary = viewModel.summarizeRecallOffline(
                                        context,
                                        content,
                                        cites.map { it.snippet.ifBlank { it.id } },
                                    )
                                    if (summary != null) {
                                        turns = turns.map { t ->
                                            if (t === turn) t.copy(offlineSummary = summary) else t
                                        }
                                    } else {
                                        snackbarHostState.showSnackbar("Could not summarize on-device")
                                    }
                                }
                            },
                        )
                    }
                    if (busy) {
                        item {
                            ShimmerListPlaceholder(
                                rows = 2,
                                rowHeight = 28.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("brain_recall_loading"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** M3-style glanceable LAN status chip for the Brain header (status dot + label). */
@Composable
private fun BrainStatusChip(healthOk: Boolean?) {
    val dark = isChronicleDark()
    val (label, color) = when (healthOk) {
        true -> "Mac reachable" to SemanticColors.success(dark)
        false -> "Mac offline" to MaterialTheme.colorScheme.onSurfaceVariant
        null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        modifier = Modifier.testTag("brain_status_chip"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = color,
            )
        }
    }
}

@Composable
private fun BrainBubble(
    turn: BrainChatTurn,
    onCitationClick: (ServeClient.Citation) -> Unit,
    onSummarize: (String, List<ServeClient.Citation>) -> Unit,
) {
    val isUser = turn.role == "user"
    val isDark = isChronicleDark()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (turn.degraded && !isUser) {
            Text(
                "Degraded · limited or offline",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .border(1.dp, GlassTokens.hairlineBrush(isDark), RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            if (isUser) {
                Text(turn.content, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    MarkdownBody(
                        content = turn.content,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                    )
                    turn.offlineSummary?.let { summary ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "On-device summary",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JournalSerif),
                            modifier = Modifier.testTag("brain_offline_summary"),
                        )
                    }
                    if (turn.citations.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Citations",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        turn.citations.take(6).forEach { c ->
                            Text(
                                "· ${c.id}${if (c.snippet.isNotBlank()) " — ${c.snippet.take(60)}" else ""}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { onCitationClick(c) }
                                    .testTag("brain_citation_${c.id}"),
                            )
                        }
                    }
                    if (!isUser && turn.offlineSummary == null && turn.content.length > 120) {
                        TextButton(
                            onClick = { onSummarize(turn.content, turn.citations) },
                            modifier = Modifier.testTag("brain_summarize_turn"),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Summarize on-device")
                        }
                    }
                }
            }
        }
    }
}

/** @deprecated Use [BrainScreen]; kept for any lingering references. */
@Composable
fun RecallScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    BrainScreen(viewModel, snackbarHostState, contentPadding)
}
