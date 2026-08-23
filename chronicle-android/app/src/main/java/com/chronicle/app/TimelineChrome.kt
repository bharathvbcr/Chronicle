package com.chronicle.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chronicle.app.ui.markdown.MarkdownBody
import com.chronicle.app.ui.theme.JournalSerif
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TimelineModeChrome(
    activeTab: TimelineTab,
    periodLabel: String,
    onTabChange: (TimelineTab) -> Unit,
    onPrevPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = TimelineTab.entries
    Column(modifier = modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("timeline_mode_row"),
        ) {
            tabs.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = activeTab == tab,
                    onClick = { onTabChange(tab) },
                    shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                    modifier = Modifier
                        .testTag("timeline_tab_${tab.name.lowercase()}")
                        .height(48.dp),
                ) {
                    Text(
                        when (tab) {
                            TimelineTab.DAY -> "Day"
                            TimelineTab.WEEK -> "Week"
                            TimelineTab.MONTH -> "Month"
                        },
                    )
                }
            }
        }
        if (activeTab != TimelineTab.DAY) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrevPeriod,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("timeline_period_prev"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous period",
                    )
                }
                Text(
                    text = periodLabel,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("timeline_period_label"),
                )
                IconButton(
                    onClick = onNextPeriod,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("timeline_period_next"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next period",
                    )
                }
            }
        }
    }
}

@Composable
fun WeekDayStrip(
    weekStart: LocalDate,
    entriesByDay: Map<LocalDate, List<Entry>>,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("timeline_week_strip"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0..6) {
            val day = weekStart.plusDays(i.toLong())
            val dayEntries = entriesByDay[day].orEmpty()
            val marker = TimelinePeriod.dayCellMarker(dayEntries)
            val selected = selectedDay == day
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            day == today -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
                        },
                    )
                    .clickable {
                        onSelectDay(if (selected) null else day)
                    }
                    .padding(vertical = 6.dp)
                    .testTag("timeline_week_day_$day"),
            ) {
                Text(
                    text = day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected || day == today) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                Spacer(modifier = Modifier.height(2.dp))
                when (marker) {
                    is DayCellMarker.Face -> Text(marker.emoji, fontSize = 14.sp)
                    DayCellMarker.Dot -> Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    DayCellMarker.Empty -> Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
fun MonthCalendarGrid(
    yearMonth: YearMonth,
    entriesByDay: Map<LocalDate, List<Entry>>,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = remember(yearMonth) { TimelinePeriod.monthGrid(yearMonth) }
    val today = LocalDate.now()
    val weekdayLabels = remember {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("timeline_month_grid"),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        grid.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                week.forEach { day ->
                    val inMonth = YearMonth.from(day) == yearMonth
                    val dayEntries = entriesByDay[day].orEmpty()
                    val marker = TimelinePeriod.dayCellMarker(dayEntries)
                    val selected = selectedDay == day
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    day == today -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                },
                            )
                            .clickable { onSelectDay(if (selected) null else day) }
                            .padding(vertical = 4.dp)
                            .testTag("timeline_month_day_$day"),
                    ) {
                        Text(
                            text = day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected || day == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (inMonth) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                },
                            ),
                        )
                        when (marker) {
                            is DayCellMarker.Face -> Text(marker.emoji, fontSize = 12.sp)
                            DayCellMarker.Dot -> Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(5.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = if (inMonth) 1f else 0.45f,
                                        ),
                                        CircleShape,
                                    ),
                            )
                            DayCellMarker.Empty -> Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RollupSummaryCard(
    title: String,
    body: String?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onDeviceSource: Boolean = false,
) {
    if (!loading && !streaming && body == null) return
    var expanded by remember { mutableStateOf(true) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp)
            .testTag("timeline_rollup_card"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                when {
                    loading && body.isNullOrBlank() -> Text(
                        "Loading summary…",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    body != null -> {
                        MarkdownBody(
                            content = body,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JournalSerif),
                            testTag = "timeline_rollup_body",
                        )
                        if (streaming) {
                            Text(
                                "Generating…",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (onDeviceSource) {
                            Text(
                                "On-device · not saved to vault",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .testTag("timeline_rollup_nano_footnote"),
                            )
                        }
                    }
                }
            }
        }
    }
}

fun timelinePeriodLabel(tab: TimelineTab, anchor: LocalDate, locale: Locale): String {
    return when (tab) {
        TimelineTab.DAY -> ""
        TimelineTab.WEEK -> {
            val start = TimelinePeriod.weekStartMonday(anchor)
            val end = TimelinePeriod.weekEndSunday(start)
            val startFmt = DateTimeFormatter.ofPattern("MMM d", locale)
            val endFmt = if (start.month == end.month) {
                DateTimeFormatter.ofPattern("d, yyyy", locale)
            } else {
                DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
            }
            "${start.format(startFmt)} – ${end.format(endFmt)}"
        }
        TimelineTab.MONTH -> {
            DateTimeFormatter.ofPattern("MMMM yyyy", locale).format(YearMonth.from(anchor))
        }
    }
}

@Composable
fun ClearDaySelectionChip(
    selectedDay: LocalDate?,
    onClear: () -> Unit,
) {
    if (selectedDay == null) return
    TextButton(
        onClick = onClear,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .testTag("timeline_clear_day"),
    ) {
        Text("Showing ${formatDayLabel(selectedDay)} · Clear")
    }
}
