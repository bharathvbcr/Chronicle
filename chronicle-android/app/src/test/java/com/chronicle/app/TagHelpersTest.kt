package com.chronicle.app

import com.chronicle.app.brain.TagTaxonomyItem
import com.chronicle.app.brain.TagsTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagHelpersTest {

    private val taxonomy = TagsTaxonomy(
        generated = "2026-07-09T00:00:00Z",
        tags = listOf(
            TagTaxonomyItem(
                canonical = "work",
                aliases = listOf("wrk", "job", "office"),
                count = 10,
            ),
            TagTaxonomyItem(
                canonical = "health",
                aliases = listOf("fitness", "gym"),
                count = 5,
            ),
            TagTaxonomyItem(
                canonical = "ideas",
                aliases = listOf("idea", "brainstorm"),
                count = 3,
            ),
        ),
    )

    @Test
    fun normalizeTagAlias_resolvesExactAliasToCanonical() {
        assertEquals("work", normalizeTagAlias("wrk", taxonomy))
        assertEquals("work", normalizeTagAlias("JOB", taxonomy))
        assertEquals("health", normalizeTagAlias("gym", taxonomy))
    }

    @Test
    fun normalizeTagAlias_keepsCanonicalAndUnknown() {
        assertEquals("work", normalizeTagAlias("Work", taxonomy))
        assertEquals("custom", normalizeTagAlias("custom", taxonomy))
        assertEquals("custom", normalizeTagAlias("custom", null))
    }

    @Test
    fun rankTagsByFrequency_ordersByUsageThenPads() {
        val entries = listOf(
            entry("a", listOf("ideas", "work")),
            entry("b", listOf("work")),
            entry("c", listOf("work", "health")),
            entry("d", listOf("ideas")),
        )
        val ranked = rankTagsByFrequency(entries, taxonomy, limit = 12)
        assertEquals("work", ranked[0])
        assertEquals("ideas", ranked[1])
        assertEquals("health", ranked[2])
        assertTrue(ranked.size <= 12)
    }

    @Test
    fun matchTagAutocomplete_prefixAndAliasMatch() {
        val all = listOf("work", "workout", "health", "weekend")
        val hits = matchTagAutocomplete("wor", all, taxonomy, limit = 6)
        assertTrue(hits.contains("work"))
        assertTrue(hits.contains("workout"))
        // alias "wrk" should not match query "wor"; but "work" does via canonical
        val aliasHits = matchTagAutocomplete("wrk", all, taxonomy, limit = 6)
        assertEquals(listOf("work"), aliasHits)
    }

    @Test
    fun matchTagAutocomplete_excludesSelectedAndEmptyQuery() {
        assertTrue(matchTagAutocomplete("", listOf("work"), taxonomy).isEmpty())
        val hits = matchTagAutocomplete("hea", listOf("health", "heart"), taxonomy, exclude = setOf("health"))
        assertFalse(hits.contains("health"))
        assertTrue(hits.contains("heart"))
    }

    @Test
    fun suggestTagsFromText_matchesWordsAgainstTaxonomy() {
        val text = "Long day at the office then gym later."
        val hits = suggestTagsFromText(text, emptyList(), taxonomy, limit = 4)
        assertTrue(hits.contains("work"))
        assertTrue(hits.contains("health"))
    }

    @Test
    fun pinSelectedTagsFront_putsSelectedFirst() {
        val pinned = pinSelectedTagsFront(
            selected = listOf("ghost-accepted", "work"),
            recent = listOf("work", "health", "ideas"),
        )
        assertEquals(listOf("ghost-accepted", "work", "health", "ideas"), pinned)
    }

    @Test
    fun mergeGhostTagSuggestions_dedupesAndCaps() {
        val merged = mergeGhostTagSuggestions(
            enrichmentTags = listOf("alpha", "beta"),
            textSuggestions = listOf("beta", "gamma"),
            themeSuggestions = listOf("delta", "alpha"),
            exclude = setOf("beta"),
            limit = 3,
        )
        assertEquals(listOf("alpha", "gamma", "delta"), merged)
    }

    @Test
    fun mergeGhostTagSuggestions_nanoAfterEnrichment() {
        val merged = mergeGhostTagSuggestions(
            enrichmentTags = listOf("enrich"),
            textSuggestions = listOf("text"),
            themeSuggestions = listOf("theme"),
            exclude = emptySet(),
            limit = 4,
            nanoTags = listOf("nano", "enrich"),
        )
        assertEquals(listOf("enrich", "nano", "text", "theme"), merged)
    }

    @Test
    fun mergeGhostTagSuggestions_nanoBeatsTextAndThemes() {
        // Without enrichment, Nano tags take priority over heuristics.
        val merged = mergeGhostTagSuggestions(
            enrichmentTags = emptyList(),
            textSuggestions = listOf("text-a", "text-b"),
            themeSuggestions = listOf("theme-a"),
            exclude = emptySet(),
            limit = 3,
            nanoTags = listOf("nano-a", "nano-b"),
        )
        assertEquals(listOf("nano-a", "nano-b", "text-a"), merged)
    }

    @Test
    fun mergeGhostTagSuggestions_nanoRespectsExcludeAndLimit() {
        val merged = mergeGhostTagSuggestions(
            enrichmentTags = listOf("enrich"),
            textSuggestions = listOf("text"),
            themeSuggestions = emptyList(),
            exclude = setOf("nano-skip", "text"),
            limit = 2,
            nanoTags = listOf("nano-skip", "nano-keep"),
        )
        assertEquals(listOf("enrich", "nano-keep"), merged)
    }

    @Test
    fun tagSetContains_isCaseInsensitive() {
        assertTrue(tagSetContains(listOf("Work"), "work"))
        assertFalse(tagSetContains(listOf("work"), "health"))
    }

    private fun entry(id: String, tags: List<String>) = Entry(
        id = id,
        ts = "2026-07-09T10:00:00-07:00",
        type = "log",
        text = "x",
        tags = tags,
        processed = false,
    )
}
