package com.chronicle.app

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.chronicle.app.ai.GenAiService
import com.chronicle.app.brain.DayInsight
import com.chronicle.app.brain.Enrichment
import com.chronicle.app.health.HealthDay
import com.chronicle.app.health.formatHealthChip
import com.chronicle.app.ui.components.ChroniclePageHeader
import com.chronicle.app.ui.components.EmptyState
import com.chronicle.app.ui.components.ShimmerListPlaceholder
import com.chronicle.app.ui.markdown.CollapsedMarkdownMaxHeight
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.theme.EntryTypeColors
import com.chronicle.app.ui.theme.GlassTokens
import com.chronicle.app.ui.theme.JournalSerif
import com.chronicle.app.ui.theme.MotionSpec
import com.chronicle.app.ui.theme.isChronicleDark
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val monthYearFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    }
    val weekHeaderFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("MMM d", locale)
    }
    val scope = rememberCoroutineScope()
    val entries by viewModel.entries.collectAsState()
    val expanded by viewModel.expandedEntryIds.collectAsState()
    val isLoading by viewModel.isLoadingTimeline.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val folderUri by viewModel.folderUri.collectAsState()
    val relatedCache by viewModel.relatedCache.collectAsState()
    val enrichment by viewModel.enrichment.collectAsState()
    val healthByDate by viewModel.healthByDate.collectAsState()
    val daySummary by viewModel.daySummary.collectAsState()
    val todayInsight by viewModel.todayInsight.collectAsState()
    val dailyDigest by viewModel.dailyDigest.collectAsState()
    val periodRollup by viewModel.periodRollup.collectAsState()
    val activeTab by viewModel.activeTimelineTab.collectAsState()
    val listState = rememberLazyListState()

    var searchQuery by remember { mutableStateOf("") }
    var filterTag by remember { mutableStateOf<String?>(null) }
    var pendingDeleteIds by remember { mutableStateOf(setOf<String>()) }
    var periodAnchor by remember { mutableStateOf(LocalDate.now()) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var rollupBody by remember { mutableStateOf<String?>(null) }
    var rollupLoading by remember { mutableStateOf(false) }
    var macRollupChecked by remember { mutableStateOf(false) }

    val searchFiltered = remember(entries, searchQuery, filterTag, pendingDeleteIds) {
        entries.filter { e ->
            if (e.id in pendingDeleteIds) return@filter false
            val q = searchQuery.trim().lowercase()
            val matchesQ = q.isEmpty() || e.text.lowercase().contains(q) || e.tags.any { it.lowercase().contains(q) }
            val matchesTag = filterTag == null || e.tags.any { it.equals(filterTag, ignoreCase = true) }
            matchesQ && matchesTag
        }
    }

    val weekStart = remember(periodAnchor) { TimelinePeriod.weekStartMonday(periodAnchor) }
    val yearMonth = remember(periodAnchor) { YearMonth.from(periodAnchor) }

    val periodEntries = remember(searchFiltered, activeTab, weekStart, yearMonth) {
        when (activeTab) {
            TimelineTab.DAY -> searchFiltered
            TimelineTab.WEEK -> {
                val end = TimelinePeriod.weekEndSunday(weekStart)
                searchFiltered.filter {
                    val d = entryLocalDate(it)
                    !d.isBefore(weekStart) && !d.isAfter(end)
                }
            }
            TimelineTab.MONTH -> searchFiltered.filter {
                YearMonth.from(entryLocalDate(it)) == yearMonth
            }
        }
    }

    // Grid/strip include padding days outside the month for emoji only.
    val chromeEntriesByDay = remember(searchFiltered, activeTab, weekStart, yearMonth) {
        val pool = when (activeTab) {
            TimelineTab.DAY -> emptyList()
            TimelineTab.WEEK -> {
                val end = TimelinePeriod.weekEndSunday(weekStart)
                searchFiltered.filter {
                    val d = entryLocalDate(it)
                    !d.isBefore(weekStart) && !d.isAfter(end)
                }
            }
            TimelineTab.MONTH -> {
                val grid = TimelinePeriod.monthGrid(yearMonth)
                val start = grid.first()
                val end = grid.last()
                searchFiltered.filter {
                    val d = entryLocalDate(it)
                    !d.isBefore(start) && !d.isAfter(end)
                }
            }
        }
        pool.groupBy { entryLocalDate(it) }
    }

    val listEntries = remember(periodEntries, selectedDay, activeTab, searchFiltered) {
        if (activeTab != TimelineTab.DAY && selectedDay != null) {
            // Selected padding day may sit outside the calendar month.
            searchFiltered.filter { entryLocalDate(it) == selectedDay }
        } else {
            periodEntries
        }
    }

    val dayGroups = remember(listEntries) { groupEntriesByDay(listEntries) }
    val weekGroupedDays = remember(dayGroups, activeTab) {
        if (activeTab != TimelineTab.MONTH) {
            emptyList()
        } else {
            dayGroups
                .groupBy { TimelinePeriod.weekStartMonday(it.first) }
                .toList()
                .sortedByDescending { it.first }
        }
    }
    val hasActiveFilters = searchQuery.isNotBlank() || filterTag != null
    val periodLabel = remember(activeTab, periodAnchor, locale) {
        timelinePeriodLabel(activeTab, periodAnchor, locale)
    }
    val showDayChrome = activeTab == TimelineTab.DAY || selectedDay != null

    LaunchedEffect(activeTab, weekStart, yearMonth) {
        if (activeTab == TimelineTab.DAY) {
            selectedDay = null
            return@LaunchedEffect
        }
        val day = selectedDay ?: return@LaunchedEffect
        val inPeriod = when (activeTab) {
            TimelineTab.WEEK -> {
                val end = TimelinePeriod.weekEndSunday(weekStart)
                !day.isBefore(weekStart) && !day.isAfter(end)
            }
            TimelineTab.MONTH -> day in TimelinePeriod.monthGrid(yearMonth)
            TimelineTab.DAY -> false
        }
        if (!inPeriod) selectedDay = null
    }

    LaunchedEffect(activeTab, weekStart, yearMonth, folderUri) {
        if (activeTab == TimelineTab.DAY) {
            rollupBody = null
            rollupLoading = false
            macRollupChecked = false
            viewModel.clearPeriodRollup()
            return@LaunchedEffect
        }
        rollupLoading = true
        rollupBody = null
        macRollupChecked = false
        viewModel.clearPeriodRollup()
        val paths = when (activeTab) {
            TimelineTab.WEEK -> TimelinePeriod.weeklyRollupPaths(weekStart)
            TimelineTab.MONTH -> TimelinePeriod.monthlyRollupPaths(yearMonth)
            TimelineTab.DAY -> emptyList()
        }
        // Await inside LaunchedEffect so restart cancels the prior load (no stale onDone).
        val body = viewModel.loadFirstNoteBody(context, paths)
        if (!isActive) return@LaunchedEffect
        rollupBody = body?.let { NoteFrontmatter.stripFrontmatter(it) }?.takeIf { it.isNotBlank() }
        rollupLoading = false
        macRollupChecked = true
    }

    val periodCacheKey = remember(activeTab, weekStart, yearMonth) {
        when (activeTab) {
            TimelineTab.WEEK -> GenAiService.periodCacheKeyWeek(weekStart)
            TimelineTab.MONTH -> GenAiService.periodCacheKeyMonth(yearMonth)
            TimelineTab.DAY -> ""
        }
    }

    LaunchedEffect(
        macRollupChecked,
        rollupBody,
        activeTab,
        periodCacheKey,
        periodEntries,
        periodLabel,
    ) {
        if (activeTab == TimelineTab.DAY) return@LaunchedEffect
        if (!macRollupChecked || rollupLoading) return@LaunchedEffect
        if (rollupBody != null) {
            viewModel.clearPeriodRollup()
            return@LaunchedEffect
        }
        val title = when (activeTab) {
            TimelineTab.WEEK -> "Week summary"
            TimelineTab.MONTH -> "Month summary"
            TimelineTab.DAY -> "Summary"
        }
        val nanoPeriodLabel = when (activeTab) {
            TimelineTab.WEEK -> "week"
            TimelineTab.MONTH -> "month"
            TimelineTab.DAY -> "period"
        }
        viewModel.maybeRefreshPeriodDigest(
            context = context,
            cacheKey = periodCacheKey,
            title = title,
            periodLabel = nanoPeriodLabel,
            periodEntries = periodEntries,
        )
    }

    val todayEntries = remember(entries) {
        val today = LocalDate.now()
        entries.filter { entryLocalDate(it) == today }
    }

    LaunchedEffect(todayEntries, todayInsight, activeTab) {
        if (activeTab == TimelineTab.DAY) {
            viewModel.maybeRefreshDailyDigest(context, todayEntries)
        }
    }

    val listBottomPad = contentPadding.calculateBottomPadding() + 72.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshAll(context) },
            modifier = Modifier
                .fillMaxSize()
                .testTag("timeline_pull_refresh"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ChroniclePageHeader(
                    title = "Timeline",
                    overline = "Journal",
                ) {
                    MoreOptionsMenu(viewModel = viewModel)
                }
                TimelineModeChrome(
                    activeTab = activeTab,
                    periodLabel = periodLabel,
                    onTabChange = { tab ->
                        viewModel.setTimelineTab(tab)
                        if (tab == TimelineTab.DAY) selectedDay = null
                    },
                    onPrevPeriod = {
                        periodAnchor = when (activeTab) {
                            TimelineTab.WEEK -> periodAnchor.minusWeeks(1)
                            TimelineTab.MONTH -> periodAnchor.minusMonths(1)
                            TimelineTab.DAY -> periodAnchor
                        }
                    },
                    onNextPeriod = {
                        periodAnchor = when (activeTab) {
                            TimelineTab.WEEK -> periodAnchor.plusWeeks(1)
                            TimelineTab.MONTH -> periodAnchor.plusMonths(1)
                            TimelineTab.DAY -> periodAnchor
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("timeline_search"),
                    placeholder = { Text("Search entries…") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
                    ),
                )
                val allTags = remember(entries) {
                    entries.flatMap { it.tags }.distinct().sorted().take(20)
                }
                if (allTags.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        items(allTags, key = { it }) { tag ->
                            val sel = filterTag == tag
                            FilterChip(
                                selected = sel,
                                onClick = { filterTag = if (sel) null else tag },
                                label = { Text(tag) },
                                modifier = Modifier.testTag("filter_tag_$tag"),
                            )
                        }
                    }
                }
                when (activeTab) {
                    TimelineTab.WEEK -> WeekDayStrip(
                        weekStart = weekStart,
                        entriesByDay = chromeEntriesByDay,
                        selectedDay = selectedDay,
                        onSelectDay = { selectedDay = it },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    TimelineTab.MONTH -> MonthCalendarGrid(
                        yearMonth = yearMonth,
                        entriesByDay = chromeEntriesByDay,
                        selectedDay = selectedDay,
                        onSelectDay = { selectedDay = it },
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    TimelineTab.DAY -> Unit
                }
                ClearDaySelectionChip(
                    selectedDay = selectedDay,
                    onClear = { selectedDay = null },
                )

                if (isLoading) {
                    ShimmerListPlaceholder(
                        rows = 6,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("timeline_loading"),
                    )
                } else if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Default.AutoStories,
                            title = "No entries yet",
                            message = "Capture your first thought — text, photo, or voice — and it will show up here.",
                            actionLabel = "Open Capture",
                            onAction = { viewModel.navigateTo(Screen.CAPTURE) },
                            actionModifier = Modifier.testTag("timeline_empty_capture"),
                            modifier = Modifier.testTag("timeline_empty"),
                        )
                    }
                } else if (searchFiltered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = "No matches",
                            message = "Nothing fits this search or filter. Try different keywords or clear the filters.",
                            actionLabel = if (hasActiveFilters) "Clear filters" else null,
                            onAction = if (hasActiveFilters) {
                                { searchQuery = ""; filterTag = null }
                            } else {
                                null
                            },
                            actionModifier = Modifier.testTag("clear_filters"),
                            modifier = Modifier.testTag("timeline_empty"),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = listBottomPad),
                    ) {
                        if (activeTab == TimelineTab.DAY) {
                            dailyDigest?.let { digest ->
                                item(key = "daily_digest") {
                                    DailyDigestCard(
                                        digest = digest,
                                        onDismiss = { viewModel.dismissDailyDigest() },
                                    )
                                }
                            }
                        }
                        if (activeTab != TimelineTab.DAY) {
                            val nanoForPeriod = periodRollup?.takeIf { rollup ->
                                rollup.cacheKey == periodCacheKey &&
                                    (rollup.body != null || rollup.loading || rollup.streaming)
                            }
                            val showRollup = rollupLoading ||
                                rollupBody != null ||
                                nanoForPeriod != null
                            if (showRollup) {
                                item(key = "rollup") {
                                    val useNano = rollupBody == null && nanoForPeriod != null
                                    RollupSummaryCard(
                                        title = when {
                                            useNano -> nanoForPeriod!!.title
                                            else -> when (activeTab) {
                                                TimelineTab.WEEK -> "Week summary"
                                                TimelineTab.MONTH -> "Month summary"
                                                TimelineTab.DAY -> "Summary"
                                            }
                                        },
                                        body = if (useNano) nanoForPeriod!!.body else rollupBody,
                                        loading = if (useNano) {
                                            nanoForPeriod!!.loading && nanoForPeriod.body.isNullOrBlank()
                                        } else {
                                            rollupLoading
                                        },
                                        streaming = useNano && (nanoForPeriod?.streaming == true),
                                        onDeviceSource = useNano,
                                    )
                                }
                            }
                        }

                        if (listEntries.isEmpty()) {
                            item(key = "empty_period") {
                                EmptyState(
                                    icon = Icons.Default.EventBusy,
                                    title = when (activeTab) {
                                        TimelineTab.WEEK -> "Nothing this week"
                                        TimelineTab.MONTH -> "Nothing this month"
                                        TimelineTab.DAY -> "No entries"
                                    },
                                    message = run {
                                        val day = selectedDay
                                        when {
                                            day != null ->
                                                "No entries on ${formatDayLabel(day)}. Tap the day again to clear."
                                            activeTab == TimelineTab.WEEK ->
                                                "No journal entries in this week. Try another week."
                                            activeTab == TimelineTab.MONTH ->
                                                "No journal entries in this month. Try another month."
                                            else -> "Nothing to show."
                                        }
                                    },
                                    modifier = Modifier
                                        .padding(vertical = 32.dp)
                                        .testTag("timeline_empty_period"),
                                )
                            }
                        } else if (activeTab == TimelineTab.MONTH && selectedDay == null) {
                            weekGroupedDays.forEach { (week, daysInWeek) ->
                                item(key = "week_header_$week") {
                                    val end = TimelinePeriod.weekEndSunday(week)
                                    Text(
                                        text = "Week of ${week.format(weekHeaderFormatter)} – ${end.format(weekHeaderFormatter)}",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        ),
                                        modifier = Modifier
                                            .padding(top = 12.dp, bottom = 2.dp)
                                            .testTag("timeline_week_header_$week"),
                                    )
                                }
                                daysInWeek.forEach { (day, dayEntries) ->
                                    timelineDaySection(
                                        day = day,
                                        dayEntries = dayEntries,
                                        showMonthTitle = false,
                                        monthYearFormatter = monthYearFormatter,
                                        showDayChrome = showDayChrome,
                                        healthByDate = healthByDate,
                                        todayInsight = todayInsight,
                                        viewModel = viewModel,
                                        context = context,
                                        expanded = expanded,
                                        folderUri = folderUri,
                                        relatedCache = relatedCache,
                                        enrichment = enrichment,
                                        entries = entries,
                                        dayGroups = dayGroups,
                                        listState = listState,
                                        snackbarHostState = snackbarHostState,
                                        scope = scope,
                                        onAddPendingDelete = { pendingDeleteIds = pendingDeleteIds + it },
                                        onRemovePendingDelete = { pendingDeleteIds = pendingDeleteIds - it },
                                    )
                                }
                            }
                        } else {
                            var lastMonth: String? = null
                            dayGroups.forEach { (day, dayEntries) ->
                                val monthKey = "${day.year}-${day.monthValue}"
                                val showMonthTitle = activeTab == TimelineTab.DAY && monthKey != lastMonth
                                if (showMonthTitle) lastMonth = monthKey
                                timelineDaySection(
                                    day = day,
                                    dayEntries = dayEntries,
                                    showMonthTitle = showMonthTitle,
                                    monthYearFormatter = monthYearFormatter,
                                    showDayChrome = showDayChrome,
                                    healthByDate = healthByDate,
                                    todayInsight = todayInsight,
                                    viewModel = viewModel,
                                    context = context,
                                    expanded = expanded,
                                    folderUri = folderUri,
                                    relatedCache = relatedCache,
                                    enrichment = enrichment,
                                    entries = entries,
                                    dayGroups = dayGroups,
                                    listState = listState,
                                    snackbarHostState = snackbarHostState,
                                    scope = scope,
                                    onAddPendingDelete = { pendingDeleteIds = pendingDeleteIds + it },
                                    onRemovePendingDelete = { pendingDeleteIds = pendingDeleteIds - it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    daySummary?.let { summaryUi ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissDaySummary() },
            sheetState = sheetState,
            modifier = Modifier.testTag("day_summary_sheet"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = summaryUi.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    summaryUi.loading && summaryUi.summary.isNullOrBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    summaryUi.error != null && summaryUi.summary.isNullOrBlank() -> {
                        Text(
                            text = summaryUi.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        Text(
                            text = summaryUi.summary.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = JournalSerif),
                            modifier = Modifier.testTag("day_summary_text"),
                        )
                        if (summaryUi.streaming) {
                            Text(
                                text = "Generating…",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Text(
                            text = "On-device only · not saved to vault",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.timelineDaySection(
    day: LocalDate,
    dayEntries: List<Entry>,
    showMonthTitle: Boolean,
    monthYearFormatter: DateTimeFormatter,
    showDayChrome: Boolean,
    healthByDate: Map<String, HealthDay>,
    todayInsight: DayInsight?,
    viewModel: MainViewModel,
    context: Context,
    expanded: Set<String>,
    folderUri: String?,
    relatedCache: Map<String, List<String>>,
    enrichment: Map<String, Enrichment>,
    entries: List<Entry>,
    dayGroups: List<Pair<LocalDate, List<Entry>>>,
    listState: LazyListState,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onAddPendingDelete: (String) -> Unit,
    onRemovePendingDelete: (String) -> Unit,
) {
    if (showMonthTitle) {
        item(key = "month_${day.year}-${day.monthValue}") {
            Text(
                text = day.format(monthYearFormatter),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
        }
    }
    stickyHeader(key = "day_$day") {
        if (showDayChrome) {
            val health = healthByDate[day.toString()]
            val healthChip = health?.let { formatHealthChip(it) }
            val offerSummarize = remember(day, dayEntries, todayInsight) {
                viewModel.canSummarizeDay(day, dayEntries)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                    .padding(top = 8.dp, bottom = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDayLabel(day),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    if (offerSummarize) {
                        TextButton(
                            onClick = { viewModel.summarizeDay(context, day, dayEntries) },
                            modifier = Modifier.testTag("summarize_day_$day"),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Summarize day")
                        }
                    }
                }
                if (healthChip != null) {
                    Text(
                        text = healthChip,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .testTag("timeline_health_$day"),
                    )
                }
            }
        } else {
            Text(
                text = formatDayLabel(day),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                    .padding(top = 8.dp, bottom = 4.dp),
            )
        }
    }
    items(dayEntries, key = { it.id }) { entry ->
        EntryCard(
            entry = entry,
            expanded = expanded.contains(entry.id),
            folderUri = folderUri,
            relatedIds = relatedCache[entry.id].orEmpty(),
            ghostTags = enrichment[entry.id]?.autoTags.orEmpty()
                .filter { it !in entry.tags },
            canSummarize = viewModel.canSummarizeEntry(entry),
            onToggle = { viewModel.toggleExpandEntry(entry.id) },
            onEdit = { viewModel.startEditEntry(entry) },
            onDelete = {
                onAddPendingDelete(entry.id)
                scope.launch {
                    var undone = false
                    try {
                        val result = snackbarHostState.showSnackbar(
                            message = "Entry deleted",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        undone = result == SnackbarResult.ActionPerformed
                    } finally {
                        if (!undone) {
                            viewModel.deleteEntry(context, entry)
                        }
                        onRemovePendingDelete(entry.id)
                    }
                }
            },
            onAcceptGhost = { tag ->
                viewModel.acceptGhostTagOnEntry(context, entry, tag)
            },
            onSummarize = {
                viewModel.summarizeEntry(context, entry)
            },
            onRelatedClick = { relatedId ->
                viewModel.toggleExpandEntry(relatedId)
                scope.launch {
                    scrollToEntry(listState, dayGroups, relatedId)
                }
            },
            allEntries = entries,
            modifier = Modifier.animateItem(
                fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                placementSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        )
    }
}

private suspend fun scrollToEntry(
    listState: LazyListState,
    dayGroups: List<Pair<LocalDate, List<Entry>>>,
    entryId: String,
) {
    // Best-effort: expand is enough; list keys are entry ids so animateItem handles visibility.
    listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.key == entryId }
        ?.let { listState.animateScrollToItem(it.index) }
}

@Composable
fun EntryCard(
    entry: Entry,
    expanded: Boolean,
    folderUri: String?,
    relatedIds: List<String>,
    ghostTags: List<String>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAcceptGhost: (String) -> Unit,
    onRelatedClick: (String) -> Unit = {},
    onSummarize: () -> Unit = {},
    canSummarize: Boolean = false,
    allEntries: List<Entry>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )
    val isDark = isChronicleDark()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val elevation by animateDpAsState(
        targetValue = if (pressed) MotionSpec.PressElevation else MotionSpec.RestElevation,
        label = "entry_press_elev",
    )
    val typeColor = EntryTypeColors.of(entry.type, isDark)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, GlassTokens.hairlineBrush(isDark), shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onToggle,
            )
            .animateContentSize()
            .padding(14.dp)
            .testTag("entry_card_${entry.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(typeColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.type.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatEntryTime(context, entry.ts),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                ),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (entry.processed) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Processed",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
            entry.mood?.let {
                Text(MOOD_FACES.getOrElse(it - 1) { "·" }, fontSize = 14.sp)
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val bodyText = entry.text.ifBlank {
            when {
                entry.audio.isNotEmpty() -> "🎙 Voice note (awaiting transcription)"
                entry.images.isNotEmpty() -> "📷 Photo entry"
                else -> "(empty)"
            }
        }
        MarkdownBody(
            content = bodyText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
            maxCollapsedHeight = if (expanded) null else CollapsedMarkdownMaxHeight,
            testTag = "entry_markdown_${entry.id}",
        )

        if (entry.tags.isNotEmpty() || ghostTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entry.tags, key = { it }) { tag ->
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (!entry.processed) {
                    items(ghostTags.take(4), key = { "ghost_$it" }) { tag ->
                        Text(
                            text = "+ $tag",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                            ),
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onAcceptGhost(tag) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column {
                if (entry.images.isNotEmpty() && folderUri != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entry.images, key = { it }) { path ->
                            val uri = resolveRelativeFileUri(context, folderUri, path)
                            if (uri != null) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
                if (relatedIds.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Related memories",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    relatedIds.take(5).forEach { rid ->
                        val related = allEntries.find { it.id == rid }
                        Text(
                            text = related?.text?.take(80)?.let {
                                if (related.text.length > 80) "$it…" else it
                            } ?: rid,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            ),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onRelatedClick(rid) },
                        )
                    }
                }
                if (!entry.processed) {
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = onEdit, modifier = Modifier.testTag("edit_${entry.id}")) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit")
                        }
                        TextButton(onClick = onDelete, modifier = Modifier.testTag("delete_${entry.id}")) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                        if (canSummarize) {
                            TextButton(
                                onClick = onSummarize,
                                modifier = Modifier.testTag("summarize_entry_${entry.id}"),
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Summarize")
                            }
                        }
                    }
                } else if (canSummarize) {
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(
                            onClick = onSummarize,
                            modifier = Modifier.testTag("summarize_entry_${entry.id}"),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Summarize")
                        }
                    }
                }
            }
        }
    }
}

/** Theme-aware type accent; prefers [EntryTypeColors] (tokens.css parity). */
fun getTypeColor(type: String, dark: Boolean = true): Color = EntryTypeColors.of(type, dark)

fun formatEntryTime(context: android.content.Context, ts: String): String {
    return try {
        val zdt = ZonedDateTime.parse(ts)
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        zdt.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    } catch (_: Exception) {
        ts.takeLast(8)
    }
}

/** @deprecated Use [formatEntryTime] with context for locale-aware formatting. */
fun formatEntryTime(ts: String): String {
    return try {
        ZonedDateTime.parse(ts).format(DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) {
        ts.takeLast(8)
    }
}

fun formatDayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String {
    val daysBetween = ChronoUnit.DAYS.between(day, today)
    return when {
        daysBetween == 0L -> "Today"
        daysBetween == 1L -> "Yesterday"
        daysBetween in 2..6 -> day.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.getDefault()))
    }
}

fun groupEntriesByDay(entries: List<Entry>): List<Pair<LocalDate, List<Entry>>> {
    return entries
        .groupBy { entryLocalDate(it) }
        .toList()
        .sortedByDescending { it.first }
}

fun resolveRelativeFileUri(context: android.content.Context, treeUriStr: String, relativePath: String): Uri? {
    return try {
        val tree = Uri.parse(treeUriStr)
        val candidates = mediaDualReadCandidates(relativePath).ifEmpty { listOf(relativePath) }
        for (candidate in candidates) {
            resolveRelativePath(context, tree, candidate)?.let { return it }
        }
        null
    } catch (_: Exception) {
        null
    }
}

@Composable
fun MoreOptionsMenu(viewModel: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("more_options_button"),
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    expanded = false
                    viewModel.navigateTo(Screen.SETTINGS)
                },
                modifier = Modifier.testTag("more_settings"),
            )
        }
    }
}

@Composable
private fun DailyDigestCard(
    digest: DaySummaryUi,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(
                1.dp,
                GlassTokens.hairlineBrush(
                    isChronicleDark(),
                ),
                RoundedCornerShape(16.dp),
            )
            .padding(14.dp)
            .testTag("daily_digest_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                digest.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("daily_digest_dismiss")) {
                Text("Dismiss")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            digest.loading && digest.summary.isNullOrBlank() -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            }
            digest.error != null && digest.summary.isNullOrBlank() -> {
                Text(
                    digest.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Text(
                    digest.summary.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                    modifier = Modifier
                        .testTag("daily_digest_text")
                        .animateContentSize(),
                )
                if (digest.streaming) {
                    Text(
                        "Generating…",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "On-device only · not saved to vault",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
