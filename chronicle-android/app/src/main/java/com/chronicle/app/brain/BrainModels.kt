package com.chronicle.app.brain

import org.json.JSONArray
import org.json.JSONObject

data class GraphNode(
    val id: String,
    val kind: String,
    val label: String,
    val weight: Double = 1.0,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val annotation: String? = null,
    val entryId: String? = null,
    val ts: String? = null,
    /** Vault-relative knowledge note path (PARA preferred; legacy kb/notes/ still valid). */
    val doc: String? = null,
    /** KB category key (e.g. bio, ai) — colors via BrainGraph.groups */
    val group: String? = null,
)

data class GraphEdge(
    val from: String,
    val to: String,
    val rel: String,
    val score: Double? = null,
)

data class GraphGroup(
    val label: String,
    val color: String,
)

data class BrainGraph(
    val version: Int = 1,
    val generated: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val groups: Map<String, GraphGroup> = emptyMap(),
)

data class DayInsight(
    val version: Int = 1,
    val date: String,
    val generated: String,
    val summary: String,
    val moodAvg: Double? = null,
    val themes: List<String> = emptyList(),
    val connections: List<String> = emptyList(),
    val relatedEntries: Map<String, List<String>> = emptyMap(),
    val onThisDay: List<String> = emptyList(),
    val timeCapsules: List<TimeCapsule> = emptyList(),
)

data class TimeCapsule(
    val entryId: String,
    val due: String? = null,
    val text: String? = null,
)

data class PromptItem(
    val id: String,
    val text: String,
    val type: String? = null,
)

data class PromptsFile(
    val version: Int = 1,
    val generated: String? = null,
    val prompts: List<PromptItem>,
)

data class Enrichment(
    val autoTags: List<String> = emptyList(),
    val summaryLine: String = "",
    val entities: List<String> = emptyList(),
)

data class TagTaxonomyItem(
    val canonical: String,
    val aliases: List<String> = emptyList(),
    val parent: String? = null,
    val count: Int = 0,
)

data class TagsTaxonomy(
    val version: Int = 1,
    val generated: String,
    val tags: List<TagTaxonomyItem>,
)

data class CurationOp(
    val op: String,
    val ts: String,
    val device: String = "phone",
    val node: String? = null,
    val label: String? = null,
    val from: String? = null,
    val into: String? = null,
    val to: String? = null,
    val rel: String? = null,
    val text: String? = null,
    val id: String? = null,
    val doc: String? = null,
) {
    fun toJsonLine(): String {
        val obj = JSONObject()
        obj.put("op", op)
        obj.put("ts", ts)
        obj.put("device", device)
        node?.let { obj.put("node", it) }
        label?.let { obj.put("label", it) }
        from?.let { obj.put("from", it) }
        into?.let { obj.put("into", it) }
        to?.let { obj.put("to", it) }
        rel?.let { obj.put("rel", it) }
        text?.let { obj.put("text", it) }
        id?.let { obj.put("id", it) }
        doc?.let { obj.put("doc", it) }
        return obj.toString()
    }
}

fun parseGraph(json: String): BrainGraph? {
    return try {
        val obj = JSONObject(json)
        val nodesArr = obj.getJSONArray("nodes")
        val edgesArr = obj.getJSONArray("edges")
        val nodes = mutableListOf<GraphNode>()
        for (i in 0 until nodesArr.length()) {
            val n = nodesArr.getJSONObject(i)
            nodes.add(
                GraphNode(
                    id = n.getString("id"),
                    kind = n.getString("kind"),
                    label = n.optString("label", n.getString("id")),
                    weight = n.optDouble("weight", 1.0),
                    pinned = n.optBoolean("pinned", false),
                    hidden = n.optBoolean("hidden", false),
                    annotation = if (n.has("annotation") && !n.isNull("annotation")) n.getString("annotation") else null,
                    entryId = if (n.has("entry_id") && !n.isNull("entry_id")) n.getString("entry_id") else null,
                    ts = if (n.has("ts") && !n.isNull("ts")) n.getString("ts") else null,
                    doc = if (n.has("doc") && !n.isNull("doc")) n.getString("doc").ifBlank { null } else null,
                    group = if (n.has("group") && !n.isNull("group")) n.getString("group").ifBlank { null } else null,
                ),
            )
        }
        val edges = mutableListOf<GraphEdge>()
        for (i in 0 until edgesArr.length()) {
            val e = edgesArr.getJSONObject(i)
            edges.add(
                GraphEdge(
                    from = e.getString("from"),
                    to = e.getString("to"),
                    rel = e.getString("rel"),
                    score = if (e.has("score") && !e.isNull("score")) e.getDouble("score") else null,
                ),
            )
        }
        val groups = mutableMapOf<String, GraphGroup>()
        val groupsObj = obj.optJSONObject("groups")
        if (groupsObj != null) {
            val keys = groupsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val g = groupsObj.optJSONObject(key) ?: continue
                val color = g.optString("color", "").trim()
                if (color.isEmpty()) continue
                groups[key] = GraphGroup(
                    label = g.optString("label", key).ifBlank { key },
                    color = color,
                )
            }
        }
        BrainGraph(
            version = obj.optInt("version", 1),
            generated = obj.optString("generated", ""),
            nodes = nodes,
            edges = edges,
            groups = groups,
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun parseInsight(json: String): DayInsight? {
    return try {
        val obj = JSONObject(json)
        val themes = jsonStringList(obj.optJSONArray("themes"))
        val connections = mutableListOf<String>()
        obj.optJSONArray("connections")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                when (item) {
                    is String -> connections.add(item)
                    is JSONObject -> connections.add(item.optString("text", item.toString()))
                }
            }
        }
        val related = mutableMapOf<String, List<String>>()
        obj.optJSONObject("related_entries")?.let { rel ->
            val keys = rel.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                related[k] = jsonStringList(rel.optJSONArray(k))
            }
        }
        val onThisDay = jsonStringList(obj.optJSONArray("on_this_day"))
        val capsules = mutableListOf<TimeCapsule>()
        obj.optJSONArray("time_capsules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                when (item) {
                    is String -> capsules.add(TimeCapsule(entryId = item))
                    is JSONObject -> capsules.add(
                        TimeCapsule(
                            entryId = item.optString("entry_id", ""),
                            due = if (item.has("due") && !item.isNull("due")) item.getString("due") else null,
                            text = if (item.has("text") && !item.isNull("text")) item.getString("text") else null,
                        ),
                    )
                }
            }
        }
        DayInsight(
            version = obj.optInt("version", 1),
            date = obj.optString("date", ""),
            generated = obj.optString("generated", ""),
            summary = obj.optString("summary", ""),
            moodAvg = if (obj.has("mood_avg") && !obj.isNull("mood_avg")) obj.getDouble("mood_avg") else null,
            themes = themes,
            connections = connections,
            relatedEntries = related,
            onThisDay = onThisDay,
            timeCapsules = capsules.filter { it.entryId.isNotBlank() },
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun parsePrompts(json: String): PromptsFile? {
    return try {
        val obj = JSONObject(json)
        val prompts = mutableListOf<PromptItem>()
        val arr = obj.optJSONArray("prompts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            prompts.add(
                PromptItem(
                    id = p.optString("id", "p$i"),
                    text = p.optString("text", p.optString("prompt", "")),
                    type = if (p.has("type")) p.getString("type") else null,
                ),
            )
        }
        PromptsFile(
            version = obj.optInt("version", 1),
            generated = if (obj.has("generated")) obj.getString("generated") else null,
            prompts = prompts,
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun parseEnrichMonth(json: String): Map<String, Enrichment> {
    return try {
        val obj = JSONObject(json)
        val entries = obj.optJSONObject("entries") ?: return emptyMap()
        val map = mutableMapOf<String, Enrichment>()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val e = entries.getJSONObject(id)
            val entities = mutableListOf<String>()
            e.optJSONArray("entities")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is String -> entities.add(item)
                        is JSONObject -> entities.add(item.optString("name", ""))
                    }
                }
            }
            map[id] = Enrichment(
                autoTags = jsonStringList(e.optJSONArray("auto_tags")),
                summaryLine = e.optString("summary_line", ""),
                entities = entities.filter { it.isNotBlank() },
            )
        }
        map
    } catch (e: Exception) {
        e.printStackTrace()
        emptyMap()
    }
}

fun parseTagsTaxonomy(json: String): TagsTaxonomy? {
    return try {
        val obj = JSONObject(json)
        val tags = mutableListOf<TagTaxonomyItem>()
        val arr = obj.getJSONArray("tags")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            tags.add(
                TagTaxonomyItem(
                    canonical = t.getString("canonical"),
                    aliases = jsonStringList(t.optJSONArray("aliases")),
                    parent = if (t.has("parent") && !t.isNull("parent")) t.getString("parent") else null,
                    count = t.optInt("count", 0),
                ),
            )
        }
        TagsTaxonomy(
            version = obj.optInt("version", 1),
            generated = obj.optString("generated", ""),
            tags = tags,
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun parseCurationOps(jsonl: String): List<CurationOp> {
    if (jsonl.isBlank()) return emptyList()
    return jsonl.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            try {
                val o = JSONObject(line)
                CurationOp(
                    op = o.getString("op"),
                    ts = o.getString("ts"),
                    device = o.optString("device", "phone"),
                    node = o.optionalString("node"),
                    label = o.optionalString("label"),
                    from = o.optionalString("from"),
                    into = o.optionalString("into"),
                    to = o.optionalString("to"),
                    rel = o.optionalString("rel"),
                    text = o.optionalString("text"),
                    id = o.optionalString("id"),
                    doc = o.optionalString("doc"),
                )
            } catch (_: Exception) {
                null
            }
        }
        .toList()
}

/** Apply local curation ops on top of a graph for optimistic UI. */
fun applyCurationOverlay(graph: BrainGraph, ops: List<CurationOp>): BrainGraph {
    val nodes = graph.nodes.associateBy { it.id }.toMutableMap()
    val edges = graph.edges.toMutableList()
    val sorted = ops.sortedBy { it.ts }
    for (op in sorted) {
        when (op.op) {
            "pin" -> op.node?.let { id -> nodes[id]?.let { nodes[id] = it.copy(pinned = true) } }
            "unpin" -> op.node?.let { id -> nodes[id]?.let { nodes[id] = it.copy(pinned = false) } }
            "hide" -> op.node?.let { id -> nodes[id]?.let { nodes[id] = it.copy(hidden = true) } }
            "unhide" -> op.node?.let { id -> nodes[id]?.let { nodes[id] = it.copy(hidden = false) } }
            "rename" -> {
                val id = op.node ?: continue
                val label = op.label ?: continue
                nodes[id]?.let { nodes[id] = it.copy(label = label) }
            }
            "annotate" -> {
                val id = op.node ?: continue
                nodes[id]?.let { nodes[id] = it.copy(annotation = op.text) }
            }
            "merge" -> {
                val from = op.from ?: continue
                val into = op.into ?: continue
                nodes.remove(from)
                edges.replaceAll { e ->
                    when {
                        e.from == from -> e.copy(from = into)
                        e.to == from -> e.copy(to = into)
                        else -> e
                    }
                }
            }
            "link" -> {
                val from = op.from ?: continue
                val to = op.to ?: continue
                val rel = op.rel ?: "manual"
                if (edges.none { it.from == from && it.to == to && it.rel == rel }) {
                    edges.add(GraphEdge(from, to, rel))
                }
            }
            "unlink" -> {
                val from = op.from ?: continue
                val to = op.to ?: continue
                edges.removeAll { it.from == from && it.to == to && (op.rel == null || it.rel == op.rel) }
            }
            "create_concept" -> {
                val id = op.id ?: continue
                val label = op.label ?: id.removePrefix("concept:")
                if (!nodes.containsKey(id)) {
                    nodes[id] = GraphNode(id = id, kind = "concept", label = label, weight = 1.0)
                }
            }
            "set_doc" -> {
                val id = op.node ?: continue
                val doc = op.doc ?: continue
                nodes[id]?.let { nodes[id] = it.copy(doc = doc) }
            }
            "delete_concept" -> {
                val id = op.node ?: continue
                if (!id.startsWith("concept:") && !id.startsWith("project:")) continue
                nodes.remove(id)
                edges.removeAll { it.from == id || it.to == id }
            }
        }
    }
    return graph.copy(nodes = nodes.values.toList(), edges = edges)
}

private fun jsonStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val list = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        list.add(arr.getString(i))
    }
    return list
}

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = getString(key)
    return v.ifEmpty { null }
}
