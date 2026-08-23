package com.chronicle.app

import com.chronicle.app.brain.TagTaxonomyItem
import com.chronicle.app.brain.TagsTaxonomy

/** Resolve an exact alias (or canonical) match to its taxonomy canonical tag. */
fun normalizeTagAlias(raw: String, taxonomy: TagsTaxonomy?): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    val tax = taxonomy ?: return trimmed
    val lower = trimmed.lowercase()
    for (item in tax.tags) {
        if (item.canonical.equals(trimmed, ignoreCase = true)) return item.canonical
        if (item.aliases.any { it.equals(trimmed, ignoreCase = true) }) return item.canonical
        // Also match without leading '#'
        val bare = lower.removePrefix("#")
        if (item.canonical.lowercase().removePrefix("#") == bare) return item.canonical
        if (item.aliases.any { it.lowercase().removePrefix("#") == bare }) return item.canonical
    }
    return trimmed
}

/** Case-insensitive membership check. */
fun tagSetContains(tags: Collection<String>, candidate: String): Boolean {
    val lower = candidate.lowercase()
    return tags.any { it.equals(candidate, ignoreCase = true) || it.lowercase() == lower }
}

/**
 * Rank tags by frequency across entries (most-used first), then pad with defaults.
 * Caps at [limit].
 */
fun rankTagsByFrequency(
    loadedEntries: List<Entry>,
    taxonomy: TagsTaxonomy? = null,
    limit: Int = 12,
    padDefaults: List<String> = listOf("work", "health", "ideas", "#plan"),
): List<String> {
    val counts = linkedMapOf<String, Int>()
    // Preserve first-seen casing as display form
    val display = linkedMapOf<String, String>()
    for (entry in loadedEntries) {
        for (tag in entry.tags) {
            val t = tag.trim()
            if (t.isEmpty()) continue
            val key = t.lowercase()
            display.putIfAbsent(key, t)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    val ranked = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { display[it.key]!! }
        .toMutableList()

    taxonomy?.tags?.sortedByDescending { it.count }?.forEach { item ->
        if (ranked.size >= limit) return@forEach
        if (!tagSetContains(ranked, item.canonical)) {
            ranked.add(item.canonical)
        }
    }

    for (pad in padDefaults) {
        if (ranked.size >= 8) break
        if (!tagSetContains(ranked, pad)) ranked.add(pad)
    }
    return ranked.take(limit)
}

/**
 * Autocomplete candidates: prefix/substring match against allTags + taxonomy
 * canonicals/aliases. Returns canonical display forms, top [limit].
 */
fun matchTagAutocomplete(
    query: String,
    allTags: List<String>,
    taxonomy: TagsTaxonomy?,
    exclude: Collection<String> = emptyList(),
    limit: Int = 6,
): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()

    data class Candidate(val display: String, val score: Int)

    val seen = mutableSetOf<String>()
    val out = mutableListOf<Candidate>()

    fun consider(display: String, matchAgainst: String) {
        val key = display.lowercase()
        if (key in seen) return
        if (tagSetContains(exclude, display)) return
        val m = matchAgainst.lowercase()
        val score = when {
            m == q -> 0
            m.startsWith(q) -> 1
            m.contains(q) -> 2
            else -> return
        }
        seen.add(key)
        out.add(Candidate(display, score))
    }

    for (tag in allTags) {
        consider(tag, tag)
    }
    taxonomy?.tags?.forEach { item ->
        consider(item.canonical, item.canonical)
        item.aliases.forEach { alias ->
            // Matching an alias still surfaces the canonical
            val m = alias.lowercase()
            if (m == q || m.startsWith(q) || m.contains(q)) {
                consider(item.canonical, alias)
            }
        }
    }

    return out.sortedWith(compareBy<Candidate> { it.score }.thenBy { it.display.lowercase() })
        .map { it.display }
        .distinct()
        .take(limit)
}

/**
 * Scan entry text for whole-word matches against taxonomy + known tags.
 * Returns canonical forms not already selected/shown.
 */
fun suggestTagsFromText(
    text: String,
    allTags: List<String>,
    taxonomy: TagsTaxonomy?,
    exclude: Collection<String> = emptyList(),
    limit: Int = 4,
): List<String> {
    if (text.isBlank()) return emptyList()
    val words = text.lowercase()
        .split(Regex("[^a-z0-9#+_-]+"))
        .filter { it.length >= 3 }
        .toSet()
    if (words.isEmpty()) return emptyList()

    val results = linkedSetOf<String>()

    fun tryAdd(display: String, keys: List<String>) {
        if (results.size >= limit) return
        if (tagSetContains(exclude, display) || tagSetContains(results, display)) return
        if (keys.any { it.lowercase() in words || it.lowercase().removePrefix("#") in words }) {
            results.add(display)
        }
    }

    taxonomy?.tags?.forEach { item ->
        tryAdd(item.canonical, listOf(item.canonical) + item.aliases)
    }
    for (tag in allTags) {
        tryAdd(tag, listOf(tag))
    }
    return results.take(limit).toList()
}

/**
 * Pin selected tags to the front of the chip row, preserving relative order of the rest.
 */
fun pinSelectedTagsFront(selected: Collection<String>, recent: List<String>): List<String> {
    val selectedOrdered = selected.map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    val selectedKeys = selectedOrdered.map { it.lowercase() }.toSet()
    val rest = recent.filter { it.lowercase() !in selectedKeys }
    return selectedOrdered + rest
}

/**
 * Merge ghost suggestion sources (enrichment, Nano, live text, themes), dedupe, exclude
 * selected/recent, cap at [limit].
 *
 * Priority: enrichment → nano → text heuristics → themes.
 */
fun mergeGhostTagSuggestions(
    enrichmentTags: List<String>,
    textSuggestions: List<String>,
    themeSuggestions: List<String>,
    exclude: Collection<String>,
    limit: Int = 4,
    nanoTags: List<String> = emptyList(),
): List<String> {
    val out = linkedSetOf<String>()
    for (tag in enrichmentTags + nanoTags + textSuggestions + themeSuggestions) {
        val t = tag.trim()
        if (t.isEmpty()) continue
        if (tagSetContains(exclude, t)) continue
        if (tagSetContains(out, t)) continue
        out.add(t)
        if (out.size >= limit) break
    }
    return out.toList()
}

/** Build alias→canonical lookup from taxonomy items. */
fun taxonomyAliasMap(tags: List<TagTaxonomyItem>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (item in tags) {
        map[item.canonical.lowercase()] = item.canonical
        for (alias in item.aliases) {
            map[alias.lowercase()] = item.canonical
        }
    }
    return map
}
