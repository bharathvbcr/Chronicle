package com.chronicle.app.vm

import com.chronicle.app.brain.BrainGraph
import com.chronicle.app.brain.DayInsight
import com.chronicle.app.brain.Enrichment
import com.chronicle.app.brain.PromptItem
import com.chronicle.app.brain.TagsTaxonomy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Brain graph, enrichment, prompts, and today-insight state. */
class BrainStateHolder {
    private val _brainFreshness = MutableStateFlow<String?>(null)
    val brainFreshness: StateFlow<String?> = _brainFreshness.asStateFlow()

    private val _brainGraph = MutableStateFlow<BrainGraph?>(null)
    val brainGraph: StateFlow<BrainGraph?> = _brainGraph.asStateFlow()

    private val _enrichment = MutableStateFlow<Map<String, Enrichment>>(emptyMap())
    val enrichment: StateFlow<Map<String, Enrichment>> = _enrichment.asStateFlow()

    private val _tagsTaxonomy = MutableStateFlow<TagsTaxonomy?>(null)
    val tagsTaxonomy: StateFlow<TagsTaxonomy?> = _tagsTaxonomy.asStateFlow()

    private val _relatedCache = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val relatedCache: StateFlow<Map<String, List<String>>> = _relatedCache.asStateFlow()

    private val _todayInsight = MutableStateFlow<DayInsight?>(null)
    val todayInsight: StateFlow<DayInsight?> = _todayInsight.asStateFlow()

    private val _todayCardDismissed = MutableStateFlow(false)
    val todayCardDismissed: StateFlow<Boolean> = _todayCardDismissed.asStateFlow()

    private val _activePrompt = MutableStateFlow<PromptItem?>(null)
    val activePrompt: StateFlow<PromptItem?> = _activePrompt.asStateFlow()

    internal val brainFreshnessMutable get() = _brainFreshness
    internal val brainGraphMutable get() = _brainGraph
    internal val enrichmentMutable get() = _enrichment
    internal val tagsTaxonomyMutable get() = _tagsTaxonomy
    internal val relatedCacheMutable get() = _relatedCache
    internal val todayInsightMutable get() = _todayInsight
    internal val todayCardDismissedMutable get() = _todayCardDismissed
    internal val activePromptMutable get() = _activePrompt

    fun clear() {
        _brainFreshness.value = null
        _brainGraph.value = null
        _enrichment.value = emptyMap()
        _tagsTaxonomy.value = null
        _relatedCache.value = emptyMap()
        _todayInsight.value = null
        _activePrompt.value = null
    }
}
