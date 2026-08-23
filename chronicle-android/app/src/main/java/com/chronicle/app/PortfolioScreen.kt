package com.chronicle.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.components.EmptyState
import com.chronicle.app.ui.components.ShimmerListPlaceholder
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.theme.ChronicleChrome
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.JournalSerif
import com.chronicle.app.ui.theme.isChronicleDark
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val points by viewModel.resumePoints.collectAsState()
    val isLoading by viewModel.isLoadingResumePoints.collectAsState()
    val selected by viewModel.selectedResumePath.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadResumePoints(context)
    }

    val filtered = remember(points, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) points
        else points.filter {
            it.path.lowercase().contains(q) || it.text.lowercase().contains(q)
        }
    }
    val selectedPoint = selected?.let { path -> points.find { it.path == path } }

    if (selectedPoint != null) {
        ResumePointDetail(
            point = selectedPoint,
            onBack = { viewModel.selectResumePoint(null) },
            onCopyAll = {
                copyToClipboard(context, selectedPoint.text, "Resume Points")
                scope.launch { snackbarHostState.showSnackbar("Copied bank") }
            },
            onCopyBullet = { bullet ->
                copyToClipboard(context, bullet, "STAR bullet")
                scope.launch { snackbarHostState.showSnackbar("Copied bullet") }
            },
            contentPadding = contentPadding,
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Resume Points",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateBack() },
                        modifier = Modifier.testTag("portfolio_back"),
                    ) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Offline STAR banks from 10-Work/ResumePoints (legacy kb/notes/ResumePoints) · read-only",
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
                    .testTag("portfolio_search"),
                placeholder = { Text("Search resume points…") },
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
                isLoading -> ShimmerListPlaceholder(
                    rows = 5,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("portfolio_loading"),
                )
                filtered.isEmpty() -> EmptyState(
                    message = if (points.isEmpty()) {
                        "No resume points yet.\nAdd markdown under 10-Work/ResumePoints/."
                    } else {
                        "No banks match this search."
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("portfolio_empty"),
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                ) {
                    items(filtered, key = { it.path }) { point ->
                        ResumePointRow(point = point) {
                            viewModel.selectResumePoint(point.path)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumePointRow(point: ResumePointRef, onClick: () -> Unit) {
    val isDark = isChronicleDark()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, GlassTokens.hairlineBrush(isDark), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag("resume_row_${point.path}"),
    ) {
        Text(
            point.name.removeSuffix(".md"),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            point.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "(empty)",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JournalSerif,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResumePointDetail(
    point: ResumePointRef,
    onBack: () -> Unit,
    onCopyAll: () -> Unit,
    onCopyBullet: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val bullets = remember(point.text) { extractStarBullets(point.text) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        point.name.removeSuffix(".md"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("portfolio_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onCopyAll, modifier = Modifier.testTag("portfolio_copy_all")) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 16.dp),
        ) {
            if (bullets.isNotEmpty()) {
                Text(
                    "Copy a bullet",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                bullets.take(12).forEachIndexed { idx, bullet ->
                    TextButton(
                        onClick = { onCopyBullet(bullet) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("portfolio_bullet_$idx"),
                    ) {
                        Text(
                            bullet.take(100) + if (bullet.length > 100) "…" else "",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            MarkdownBody(
                content = point.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                testTag = "resume_markdown",
            )
        }
    }
}

/** Pull markdown list items that look like STAR / impact bullets. */
internal fun extractStarBullets(markdown: String): List<String> {
    return markdown.lineSequence()
        .map { it.trim() }
        .filter { line ->
            line.startsWith("- ") || line.startsWith("* ") || Regex("""^\d+\.\s""").containsMatchIn(line)
        }
        .map { line ->
            line.removePrefix("- ").removePrefix("* ").replaceFirst(Regex("""^\d+\.\s+"""), "")
        }
        .filter { it.length >= 24 }
        .distinct()
        .toList()
}
