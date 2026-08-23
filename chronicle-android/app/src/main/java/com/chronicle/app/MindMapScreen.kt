package com.chronicle.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.brain.BrainGraph
import com.chronicle.app.brain.CurationOp
import com.chronicle.app.brain.GraphNode
import com.chronicle.app.brain.SimLink
import com.chronicle.app.brain.SimNode
import com.chronicle.app.brain.capGraphNodes
import com.chronicle.app.brain.forceNodeRadius
import com.chronicle.app.brain.layoutForce
import com.chronicle.app.ui.components.EmptyState
import com.chronicle.app.ui.theme.isChronicleDark
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI

private enum class MapDensityMode { Major, Full }

@Composable
fun MindMapScreen(
    graph: BrainGraph?,
    viewModel: MainViewModel,
    onLoadArchive: (String) -> Unit,
    /** Citation / seed highlights from Brain recall. */
    highlightNodeIds: Set<String> = emptySet(),
    seedNodeIds: Set<String> = emptySet(),
    /** When set, node tap seeds recall without auto-opening the inspector. */
    onNodeTap: ((GraphNode) -> Unit)? = null,
    showOverflowMenu: Boolean = true,
    /**
     * External 2-hop focus from Brain Send (label/citation match).
     * Long-press and Clear focus still manage local focus independently.
     */
    externalFocusId: String? = null,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(DEFAULT_MAP_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var focusNodeId by remember { mutableStateOf<String?>(null) }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    var threadMode by remember { mutableStateOf(false) }
    var mapMode by remember { mutableStateOf(MapDensityMode.Major) }
    var expandedMajorId by remember { mutableStateOf<String?>(null) }
    var selectedNode by remember { mutableStateOf<GraphNode?>(null) }
    var lastInteractedNode by remember { mutableStateOf<GraphNode?>(null) }
    var showCreateConcept by remember { mutableStateOf(false) }
    var newConceptLabel by remember { mutableStateOf("") }
    var linkFromId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var annotateText by remember { mutableStateOf("") }
    var mergeIntoId by remember { mutableStateOf("") }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var positions by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var layoutGeneration by remember { mutableIntStateOf(0) }
    val brainFreshness by viewModel.brainFreshness.collectAsState()

    LaunchedEffect(externalFocusId) {
        if (externalFocusId != null) {
            focusNodeId = externalFocusId
        }
    }

    if (graph == null || graph.nodes.isEmpty()) {
        EmptyState(
            message = "Your mind map is still forming.\nProcess entries on your Mac to build brain/graph.json.",
            modifier = Modifier
                .fillMaxSize()
                .testTag("brain_graph_empty"),
        )
        return
    }

    val capped = remember(graph) { capGraphNodes(graph) }

    val degreeById = remember(capped) {
        val deg = mutableMapOf<String, Int>()
        for (e in capped.edges) {
            deg[e.from] = (deg[e.from] ?: 0) + 1
            deg[e.to] = (deg[e.to] ?: 0) + 1
        }
        deg
    }

    val majorIds = remember(capped, degreeById) {
        capped.nodes
            .filter { isMajorNode(it, degreeById[it.id] ?: 0) }
            .map { it.id }
            .toSet()
    }

    val visibleNodes = remember(
        capped, kindFilter, focusNodeId, threadMode, mapMode, expandedMajorId, majorIds,
    ) {
        var nodes = capped.nodes
        kindFilter?.let { k -> nodes = nodes.filter { it.kind == k } }
        if (focusNodeId != null) {
            val hops = neighborhood(capped.nodes, capped.edges, focusNodeId!!, hops = 2)
            nodes = nodes.filter { it.id in hops }
        }
        if (threadMode) {
            val threadIds = capped.edges.filter { it.rel == "continues" }
                .flatMap { listOf(it.from, it.to) }
                .toSet()
            nodes = nodes.filter { it.id in threadIds || it.kind != "entry" }
        }
        if (mapMode == MapDensityMode.Major) {
            val expandId = expandedMajorId
            val expandedEntries = if (expandId != null) {
                capped.edges
                    .asSequence()
                    .filter { it.from == expandId || it.to == expandId }
                    .flatMap { sequenceOf(it.from, it.to) }
                    .filter { id -> capped.nodes.any { it.id == id && it.kind == "entry" } }
                    .toSet()
            } else {
                emptySet()
            }
            nodes = nodes.filter { it.id in majorIds || it.id in expandedEntries }
        }
        nodes
    }

    val visibleEdges = remember(capped, visibleNodes, threadMode) {
        val ids = visibleNodes.map { it.id }.toSet()
        capped.edges.filter { e ->
            e.from in ids && e.to in ids &&
                (!threadMode || e.rel == "continues" || e.rel == "manual")
        }
    }

    // Force layout. On major expand, pin existing majors and only place new entries.
    LaunchedEffect(visibleNodes, visibleEdges, canvasSize, expandedMajorId, mapMode) {
        val w = canvasSize.width.toFloat().coerceAtLeast(1f)
        val h = canvasSize.height.toFloat().coerceAtLeast(1f)
        if (w < 8f || h < 8f || visibleNodes.isEmpty()) return@LaunchedEffect

        val visibleIds = visibleNodes.map { it.id }.toSet()
        val prior = positions
        val newIds = visibleIds - prior.keys
        val isExpandLayout = mapMode == MapDensityMode.Major &&
            expandedMajorId != null &&
            newIds.isNotEmpty() &&
            newIds.all { id -> visibleNodes.any { it.id == id && it.kind == "entry" } }

        val hubPos = expandedMajorId?.let { prior[it] }
        val entryNewIds = if (isExpandLayout) newIds.toList() else emptyList()

        val simNodes = visibleNodes.map { n ->
            val prev = prior[n.id]
            val pinMajor = isExpandLayout && n.id in majorIds && prev != null
            val seeded = if (prev == null && isExpandLayout && hubPos != null && n.kind == "entry") {
                val idx = entryNewIds.indexOf(n.id).coerceAtLeast(0)
                val count = entryNewIds.size.coerceAtLeast(1)
                val angle = (2.0 * PI * idx / count).toFloat()
                val ring = 48f + forceNodeRadius(n) * 2f
                Offset(hubPos.x + cos(angle) * ring, hubPos.y + sin(angle) * ring)
            } else {
                null
            }
            SimNode(
                id = n.id,
                x = seeded?.x ?: 0f,
                y = seeded?.y ?: 0f,
                radius = forceNodeRadius(n),
                fx = if (pinMajor) prev!!.x else null,
                fy = if (pinMajor) prev!!.y else null,
            ).also { sim ->
                if (seeded != null) {
                    sim.x = seeded.x
                    sim.y = seeded.y
                }
            }
        }
        // Seed ring positions into previous so layoutForce does not randomize them.
        val previousForLayout = if (isExpandLayout && hubPos != null) {
            prior + simNodes
                .filter { it.id in newIds }
                .associate { it.id to Offset(it.x, it.y) }
        } else {
            prior
        }
        val simLinks = visibleEdges.map { SimLink(it.from, it.to) }
        positions = layoutForce(
            nodes = simNodes,
            links = simLinks,
            width = w,
            height = h,
            previous = previousForLayout,
            iterations = if (isExpandLayout) 90 else 280,
        )
        // Expand-only layouts must not re-trigger default camera recenter.
        if (!isExpandLayout) {
            layoutGeneration++
        }
    }

    fun applyDefaultRecenter() {
        val fitted = cameraForVisibleGraph(
            nodes = visibleNodes,
            positions = positions,
            canvasW = canvasSize.width.toFloat(),
            canvasH = canvasSize.height.toFloat(),
        )
        if (fitted != null) {
            scale = fitted.first
            offset = fitted.second
        } else {
            scale = DEFAULT_MAP_SCALE
            offset = Offset.Zero
        }
    }

    // Default camera: weighted recenter at base zoom after each full layout.
    // Citation/seed highlight pans to that node instead (same zoom).
    LaunchedEffect(highlightNodeIds, seedNodeIds, layoutGeneration, canvasSize) {
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        if (w < 8f || h < 8f) return@LaunchedEffect
        if (positions.isEmpty()) return@LaunchedEffect

        val targetId = highlightNodeIds.firstOrNull() ?: seedNodeIds.firstOrNull()
        if (targetId != null) {
            val p = positions[targetId]
            if (p != null) {
                scale = DEFAULT_MAP_SCALE
                offset = Offset(w / 2f - p.x * DEFAULT_MAP_SCALE, h / 2f - p.y * DEFAULT_MAP_SCALE)
                return@LaunchedEffect
            }
        }
        applyDefaultRecenter()
    }

    val primary = MaterialTheme.colorScheme.primary
    val isDarkSurface = isChronicleDark()
    val labelHaloColor = MaterialTheme.colorScheme.surface

    fun openInspector(node: GraphNode) {
        selectedNode = node
        renameText = node.label
        annotateText = node.annotation.orEmpty()
    }

    fun hitTest(world: Offset): GraphNode? {
        return visibleNodes.minByOrNull { n ->
            val p = positions[n.id] ?: Offset.Zero
            hypot((p.x - world.x).toDouble(), (p.y - world.y).toDouble())
        }?.let { n ->
            val p = positions[n.id] ?: return null
            val hitR = (forceNodeRadius(n) + 22f).toDouble()
            if (hypot((p.x - world.x).toDouble(), (p.y - world.y).toDouble()) < hitR) n else null
        }
    }

    val latestScale = rememberUpdatedState(scale)
    val latestOffset = rememberUpdatedState(offset)

    Column(modifier = Modifier.fillMaxSize()) {
        if (showOverflowMenu) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                MoreOptionsMenu(viewModel = viewModel)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip("Major", mapMode == MapDensityMode.Major) {
                mapMode = MapDensityMode.Major
            }
            FilterChip("Full", mapMode == MapDensityMode.Full) {
                mapMode = MapDensityMode.Full
                expandedMajorId = null
            }
            listOf(
                null to "All",
                "topic" to "Topics",
                "person" to "People",
                "place" to "Places",
                "project" to "Projects",
                "concept" to "Concepts",
                "entry" to "Entries",
            ).forEach { (k, label) ->
                val sel = kindFilter == k
                FilterChip(label, sel) { kindFilter = k }
            }
            FilterChip("Thread", threadMode) { threadMode = !threadMode }
            FilterChip("2025", false) { onLoadArchive("2025") }
            FilterChip("2026", false) { onLoadArchive("2026") }
            if (focusNodeId != null) {
                FilterChip("Clear focus", true) { focusNodeId = null }
            }
            if (expandedMajorId != null) {
                FilterChip("Collapse", true) { expandedMajorId = null }
            }
            if (onNodeTap != null && lastInteractedNode != null) {
                FilterChip("Details", false) {
                    lastInteractedNode?.let { openInspector(it) }
                }
            }
            FilterChip("New concept", false) { showCreateConcept = true }
        }

        Text(
            text = graph.generated.ifBlank { "graph" }.let { "Freshness · ${brainFreshness ?: it}" },
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                fontSize = 11.sp,
            ),
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (graph.groups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                graph.groups.entries.forEach { (key, def) ->
                    val used = visibleNodes.any { it.group == key }
                    if (!used) return@forEach
                    val swatch = com.chronicle.app.ui.theme.MindMapColors.parseHexColor(def.color)
                        ?: MaterialTheme.colorScheme.primary
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(swatch),
                        )
                        Text(
                            text = def.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                            ),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val oldScale = scale
                            val newScale = (oldScale * zoom).coerceIn(MIN_MAP_SCALE, MAX_MAP_SCALE)
                            // Keep the world point under the pinch fingers fixed, then apply pan.
                            // screen = world * scale + offset  →  offset' = c - (c - offset) * (s'/s) + pan
                            val ratio = if (oldScale > 1e-6f) newScale / oldScale else 1f
                            offset = centroid - (centroid - offset) * ratio + pan
                            scale = newScale
                        }
                    }
                    .pointerInput(
                        visibleNodes,
                        positions,
                        focusNodeId,
                        mapMode,
                        expandedMajorId,
                        majorIds,
                        linkFromId,
                        onNodeTap != null,
                    ) {
                        // Read camera via updated state so this block is NOT restarted mid-pinch.
                        detectTapGestures(
                            onLongPress = { tap ->
                                val world = (tap - latestOffset.value) / latestScale.value
                                hitTest(world)?.let { focusNodeId = it.id }
                            },
                            onTap = { tap ->
                                val world = (tap - latestOffset.value) / latestScale.value
                                hitTest(world)?.let { node ->
                                    if (linkFromId != null && linkFromId != node.id) {
                                        val op = CurationOp(
                                            op = "link",
                                            ts = nowOpTs(),
                                            from = linkFromId,
                                            to = node.id,
                                            rel = "manual",
                                        )
                                        viewModel.appendCurationOp(context, op)
                                        linkFromId = null
                                    } else {
                                        if (mapMode == MapDensityMode.Major && node.id in majorIds) {
                                            expandedMajorId =
                                                if (expandedMajorId == node.id) null else node.id
                                        }
                                        lastInteractedNode = node
                                        if (onNodeTap != null) {
                                            onNodeTap.invoke(node)
                                            // Brain: seed/expand only — inspect via Details chip.
                                        } else {
                                            openInspector(node)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    .testTag("mindmap_canvas"),
            ) {
                val isDark = isDarkSurface
                val haloArgb = labelHaloColor.toArgb()
                for (e in visibleEdges) {
                    val a = positions[e.from] ?: continue
                    val b = positions[e.to] ?: continue
                    val p1 = a * scale + offset
                    val p2 = b * scale + offset
                    val strong = e.from in highlightNodeIds || e.to in highlightNodeIds ||
                        e.from in seedNodeIds || e.to in seedNodeIds
                    drawLine(
                        color = when {
                            e.rel == "continues" -> primary.copy(alpha = 0.55f)
                            strong -> com.chronicle.app.ui.theme.MindMapColors.edgeStrong(isDark)
                            else -> com.chronicle.app.ui.theme.MindMapColors.edge(isDark)
                        },
                        start = p1,
                        end = p2,
                        strokeWidth = if (e.rel == "continues" || strong) 3f else 1.5f,
                    )
                }
                for (n in visibleNodes) {
                    val p = (positions[n.id] ?: continue) * scale + offset
                    val radius = forceNodeRadius(n) * NODE_DRAW_SCALE * scale
                    val color = com.chronicle.app.ui.theme.MindMapColors.forNode(
                        kind = n.kind,
                        group = n.group,
                        groups = graph.groups,
                        dark = isDark,
                    )
                    val highlighted = n.id in highlightNodeIds
                    val seeded = n.id in seedNodeIds
                    if (highlighted || seeded) {
                        drawCircle(
                            color = com.chronicle.app.ui.theme.MindMapColors.pulse(isDark),
                            radius = radius + 9f,
                            center = p,
                        )
                    }
                    drawCircle(color = color.copy(alpha = 0.2f), radius = radius + 1.5f, center = p)
                    drawCircle(color = color, radius = radius, center = p)
                    if (n.pinned || seeded) {
                        drawCircle(
                            color = if (seeded) com.chronicle.app.ui.theme.MindMapColors.highlight(isDark) else Color.White,
                            radius = radius,
                            center = p,
                            style = Stroke(width = 2f),
                        )
                    }
                    if (highlighted) {
                        drawCircle(
                            color = com.chronicle.app.ui.theme.MindMapColors.highlight(isDark),
                            radius = radius + 3f,
                            center = p,
                            style = Stroke(width = 2.5f),
                        )
                    }
                    if (scale > 0.7f || n.kind != "entry") {
                        val labelColor = com.chronicle.app.ui.theme.MindMapColors.label(isDark)
                        val label = n.label.take(22)
                        val labelX = p.x
                        val labelY = p.y + radius + 10f
                        val textSizePx = 12f * scale.coerceIn(0.85f, 1.2f)
                        val paint = android.graphics.Paint().apply {
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                            textSize = textSizePx
                        }
                        // Mac-like stroke halo (~3px theme surface)
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeWidth = 3f
                        paint.color = haloArgb
                        drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, paint)
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.color = android.graphics.Color.argb(
                            220,
                            (labelColor.red * 255).toInt(),
                            (labelColor.green * 255).toInt(),
                            (labelColor.blue * 255).toInt(),
                        )
                        drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, paint)
                    }
                }
            }

            SmallFloatingActionButton(
                onClick = { applyDefaultRecenter() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .testTag("mindmap_recenter"),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recenter brain")
            }
        }
    }

    selectedNode?.let { node ->
        AlertDialog(
            onDismissRequest = { selectedNode = null },
            title = { Text(node.label) },
            text = {
                Column {
                    Text("${node.kind} · ${node.id}", style = MaterialTheme.typography.labelMedium)
                    node.group?.let { gKey ->
                        val def = graph.groups[gKey]
                        val swatch = com.chronicle.app.ui.theme.MindMapColors.parseHexColor(def?.color)
                            ?: MaterialTheme.colorScheme.primary
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(swatch),
                            )
                            Text(
                                text = def?.label ?: gKey,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                ),
                            )
                        }
                    }
                    node.doc?.takeIf { it.isNotBlank() }?.let { doc ->
                        Text(
                            "doc: $doc",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Rename") })
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(value = annotateText, onValueChange = { annotateText = it }, label = { Text("Annotate") })
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = mergeIntoId,
                        onValueChange = { mergeIntoId = it },
                        label = { Text("Other node id (merge / unlink)") },
                    )
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            viewModel.appendCurationOp(
                                context,
                                CurationOp(op = if (node.pinned) "unpin" else "pin", ts = nowOpTs(), node = node.id),
                            )
                            selectedNode = null
                        }) { Text(if (node.pinned) "Unpin" else "Pin") }
                        TextButton(onClick = {
                            viewModel.appendCurationOp(
                                context,
                                CurationOp(op = if (node.hidden) "unhide" else "hide", ts = nowOpTs(), node = node.id),
                            )
                            selectedNode = null
                        }) { Text(if (node.hidden) "Unhide" else "Hide") }
                        TextButton(onClick = {
                            linkFromId = node.id
                            selectedNode = null
                        }) { Text("Link…") }
                        TextButton(onClick = {
                            val target = mergeIntoId.trim()
                            if (target.isNotEmpty()) {
                                viewModel.appendCurationOp(
                                    context,
                                    CurationOp(op = "unlink", ts = nowOpTs(), from = node.id, to = target),
                                )
                            }
                            selectedNode = null
                        }) { Text("Unlink") }
                    }
                    if (!node.doc.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val doc = node.doc
                                selectedNode = null
                                viewModel.openNote(context, doc)
                            },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("mindmap_open_note"),
                        ) { Text("Open note") }
                    }
                    if (node.kind == "concept" || node.kind == "project") {
                        TextButton(
                            onClick = {
                                viewModel.appendCurationOp(
                                    context,
                                    CurationOp(op = "delete_concept", ts = nowOpTs(), node = node.id),
                                )
                                selectedNode = null
                            },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("mindmap_delete_concept"),
                        ) { Text("Delete concept") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank() && renameText != node.label) {
                        viewModel.appendCurationOp(
                            context,
                            CurationOp(op = "rename", ts = nowOpTs(), node = node.id, label = renameText),
                        )
                    }
                    if (annotateText != node.annotation.orEmpty()) {
                        viewModel.appendCurationOp(
                            context,
                            CurationOp(op = "annotate", ts = nowOpTs(), node = node.id, text = annotateText),
                        )
                    }
                    if (mergeIntoId.isNotBlank()) {
                        viewModel.appendCurationOp(
                            context,
                            CurationOp(op = "merge", ts = nowOpTs(), from = node.id, into = mergeIntoId.trim()),
                        )
                    }
                    selectedNode = null
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { selectedNode = null }) { Text("Close") }
            },
        )
    }

    if (showCreateConcept) {
        AlertDialog(
            onDismissRequest = { showCreateConcept = false },
            title = { Text("New concept") },
            text = {
                OutlinedTextField(
                    value = newConceptLabel,
                    onValueChange = { newConceptLabel = it },
                    label = { Text("Label") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val slug = newConceptLabel.trim().lowercase().replace(Regex("""\s+"""), "-")
                    if (slug.isNotEmpty()) {
                        viewModel.appendCurationOp(
                            context,
                            CurationOp(
                                op = "create_concept",
                                ts = nowOpTs(),
                                id = "concept:$slug",
                                label = newConceptLabel.trim(),
                            ),
                        )
                    }
                    newConceptLabel = ""
                    showCreateConcept = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateConcept = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
    }
}

/** Default camera zoom on phone. */
private const val DEFAULT_MAP_SCALE = 5f
private const val MIN_MAP_SCALE = 0.25f
private const val MAX_MAP_SCALE = 8f

/** Visual-only shrink vs Mac force radii (layout/collide still use full forceNodeRadius). */
private const val NODE_DRAW_SCALE = 0.4f

/**
 * Recenter on the weight-weighted centroid of visible nodes at [DEFAULT_MAP_SCALE].
 */
private fun cameraForVisibleGraph(
    nodes: List<GraphNode>,
    positions: Map<String, Offset>,
    canvasW: Float,
    canvasH: Float,
): Pair<Float, Offset>? {
    if (canvasW < 8f || canvasH < 8f || nodes.isEmpty()) return null
    val placed = nodes.mapNotNull { n -> positions[n.id]?.let { n to it } }
    if (placed.isEmpty()) return null

    var sumWx = 0f
    var sumWy = 0f
    var sumW = 0f
    for ((n, p) in placed) {
        val w = n.weight.coerceAtLeast(1.0).toFloat()
        sumWx += p.x * w
        sumWy += p.y * w
        sumW += w
    }

    val scale = DEFAULT_MAP_SCALE
    val cx = sumWx / sumW
    val cy = sumWy / sumW
    val offset = Offset(canvasW / 2f - cx * scale, canvasH / 2f - cy * scale)
    return scale to offset
}

/** Major backbone nodes for density mode (entries are never major). */
private fun isMajorNode(n: GraphNode, degree: Int): Boolean {
    if (n.kind == "entry") return false
    if (n.pinned) return true
    if (!n.group.isNullOrBlank()) return true
    if (n.kind in setOf("topic", "person", "place") && degree >= 2) return true
    if (n.kind in setOf("concept", "project") && degree >= 1) return true
    return false
}

private fun neighborhood(
    @Suppress("UNUSED_PARAMETER") nodes: List<GraphNode>,
    edges: List<com.chronicle.app.brain.GraphEdge>,
    start: String,
    hops: Int,
): Set<String> {
    var frontier = setOf(start)
    val all = mutableSetOf(start)
    repeat(hops) {
        val next = mutableSetOf<String>()
        for (e in edges) {
            if (e.from in frontier) next.add(e.to)
            if (e.to in frontier) next.add(e.from)
        }
        all.addAll(next)
        frontier = next
    }
    return all
}

private fun nowOpTs(): String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private operator fun Offset.times(scale: Float): Offset = Offset(x * scale, y * scale)
private operator fun Offset.div(scale: Float): Offset = Offset(x / scale, y / scale)
