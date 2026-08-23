package com.chronicle.app.vm

import com.chronicle.app.Entry
import com.chronicle.app.TimelineTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Timeline list + expansion state. */
class TimelineStateHolder {
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _expandedEntryIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedEntryIds: StateFlow<Set<String>> = _expandedEntryIds.asStateFlow()

    private val _recentTags = MutableStateFlow<List<String>>(emptyList())
    val recentTags: StateFlow<List<String>> = _recentTags.asStateFlow()

    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags.asStateFlow()

    private val _isLoadingTimeline = MutableStateFlow(false)
    val isLoadingTimeline: StateFlow<Boolean> = _isLoadingTimeline.asStateFlow()

    private val _activeTimelineTab = MutableStateFlow(TimelineTab.DAY)
    val activeTimelineTab: StateFlow<TimelineTab> = _activeTimelineTab.asStateFlow()

    internal val entriesMutable get() = _entries
    internal val expandedEntryIdsMutable get() = _expandedEntryIds
    internal val recentTagsMutable get() = _recentTags
    internal val allTagsMutable get() = _allTags
    internal val isLoadingTimelineMutable get() = _isLoadingTimeline
    internal val activeTimelineTabMutable get() = _activeTimelineTab

    fun clear() {
        _entries.value = emptyList()
        _expandedEntryIds.value = emptySet()
        _recentTags.value = emptyList()
        _allTags.value = emptyList()
        _activeTimelineTab.value = TimelineTab.DAY
    }

    fun toggleExpand(entryId: String) {
        val cur = _expandedEntryIds.value
        _expandedEntryIds.value = if (entryId in cur) cur - entryId else cur + entryId
    }
}
