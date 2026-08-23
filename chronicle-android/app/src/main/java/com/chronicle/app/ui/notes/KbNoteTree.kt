package com.chronicle.app.ui.notes

import com.chronicle.app.KbNoteRef
import com.chronicle.app.KnowledgePathMap

/** Collapsible folder / file node for Notes + Knowledge Base lists. */
sealed class KbTreeUiNode {
    data class Folder(
        val path: String,
        val name: String,
        val children: List<KbTreeUiNode>,
    ) : KbTreeUiNode()

    data class File(val note: KbNoteRef) : KbTreeUiNode()
}

/** Flat LazyColumn rows for browse-mode PARA trees. */
sealed class KbVisibleRow {
    data class Folder(
        val path: String,
        val name: String,
        val depth: Int,
        val noteCount: Int,
        val expanded: Boolean,
    ) : KbVisibleRow()

    data class File(
        val note: KbNoteRef,
        val depth: Int,
    ) : KbVisibleRow()
}

/**
 * Build SPA-parity hub rows + area folder trees for a Notes section.
 * Hubs (Home / MOCs) are returned separately; [areaRoots] are collapsible PARA areas.
 * [allNotes] supplies vault-root Home even when [notes] is section-filtered.
 */
data class KbSectionTree(
    val hubs: List<KbNoteRef>,
    val areaRoots: List<KbTreeUiNode.Folder>,
)

fun buildKbSectionTree(
    notes: List<KbNoteRef>,
    section: String,
    allNotes: List<KbNoteRef> = notes,
): KbSectionTree {
    val sectionNotes = notes.filter { KnowledgePathMap.sectionFor(it.path) == section }
    // Home is a vault-root hub on every Notes tab (opens Notes section).
    val home = allNotes.filter { it.path == KnowledgePathMap.HOME_NOTE }
    val mocs = sectionNotes.filter { it.name.startsWith("MOC-", ignoreCase = true) }
    val hubs = (home + mocs).distinctBy { it.path }
        .sortedWith(compareBy<KbNoteRef> { it.path != KnowledgePathMap.HOME_NOTE }.thenBy { it.path.lowercase() })
    val hubPaths = hubs.map { it.path }.toSet()
    val rest = sectionNotes.filter { it.path !in hubPaths }

    val areas = when (section) {
        KnowledgePathMap.SECTION_KB -> listOf(KnowledgePathMap.KB_AREA)
        KnowledgePathMap.SECTION_NOTES -> KnowledgePathMap.NOTES_AREAS
        else -> emptyList()
    }

    val areaRoots = areas.mapNotNull { area ->
        val inArea = rest.filter {
            it.path == area || it.path.startsWith("$area/")
        }
        if (inArea.isEmpty()) return@mapNotNull null
        KbTreeUiNode.Folder(
            path = area,
            name = area,
            children = nestUnderPrefix(inArea, area),
        )
    }
    return KbSectionTree(hubs = hubs, areaRoots = areaRoots)
}

/** Nest note paths under [prefix] into folder/file nodes (sorted). */
internal fun nestUnderPrefix(notes: List<KbNoteRef>, prefix: String): List<KbTreeUiNode> {
    val files = mutableListOf<KbNoteRef>()
    val folders = linkedMapOf<String, MutableList<KbNoteRef>>()

    for (note in notes) {
        if (note.path == prefix) {
            files.add(note)
            continue
        }
        if (!note.path.startsWith("$prefix/")) continue
        val rel = note.path.removePrefix("$prefix/")
        val slash = rel.indexOf('/')
        if (slash < 0) {
            files.add(note)
        } else {
            val folderName = rel.substring(0, slash)
            folders.getOrPut(folderName) { mutableListOf() }.add(note)
        }
    }

    val folderNodes = folders.entries
        .sortedBy { it.key.lowercase() }
        .map { (name, childNotes) ->
            KbTreeUiNode.Folder(
                path = "$prefix/$name",
                name = name,
                children = nestUnderPrefix(childNotes, "$prefix/$name"),
            )
        }
    val fileNodes = files
        .sortedBy { it.path.lowercase() }
        .map { KbTreeUiNode.File(it) }
    return folderNodes + fileNodes
}

/** Recursive descendant file count for a folder node. */
fun countDescendantNotes(node: KbTreeUiNode): Int = when (node) {
    is KbTreeUiNode.File -> 1
    is KbTreeUiNode.Folder -> node.children.sumOf { countDescendantNotes(it) }
}

/**
 * Default expanded folder paths for browse mode.
 * Nested folders start collapsed; sole KB area root [30-Knowledge] auto-expands one level.
 */
fun defaultExpandedKbFolders(
    areaRoots: List<KbTreeUiNode.Folder>,
    section: String,
): Set<String> {
    if (section == KnowledgePathMap.SECTION_KB &&
        areaRoots.size == 1 &&
        areaRoots[0].path == KnowledgePathMap.KB_AREA
    ) {
        return setOf(KnowledgePathMap.KB_AREA)
    }
    return emptySet()
}

/**
 * Depth-first flatten of visible PARA tree rows for LazyColumn virtualization.
 * Emits a folder row always; emits children only when [path] is in [expanded].
 */
fun flattenVisibleKbTree(
    roots: List<KbTreeUiNode.Folder>,
    expanded: Set<String>,
): List<KbVisibleRow> {
    val out = mutableListOf<KbVisibleRow>()
    fun walk(folder: KbTreeUiNode.Folder, depth: Int) {
        val isExpanded = folder.path in expanded
        out += KbVisibleRow.Folder(
            path = folder.path,
            name = folder.name,
            depth = depth,
            noteCount = countDescendantNotes(folder),
            expanded = isExpanded,
        )
        if (!isExpanded) return
        for (child in folder.children) {
            when (child) {
                is KbTreeUiNode.Folder -> walk(child, depth + 1)
                is KbTreeUiNode.File -> out += KbVisibleRow.File(note = child.note, depth = depth + 1)
            }
        }
    }
    for (root in roots) walk(root, 0)
    return out
}

/** Basename without `.md` for compact list titles. */
fun noteDisplayTitle(name: String): String = name.removeSuffix(".md")

/** Parent path breadcrumb for search / hub rows (empty when note is vault-root). */
fun noteBreadcrumb(path: String): String {
    val slash = path.lastIndexOf('/')
    return if (slash <= 0) "" else path.substring(0, slash)
}

/**
 * Section-scoped path/name filter + ranking for search mode.
 * Rank: basename match first, then path-only match, then alphabetical.
 */
fun filterAndRankKbNotes(notes: List<KbNoteRef>, query: String): List<KbNoteRef> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return notes.sortedBy { it.path.lowercase() }
    return notes
        .filter {
            it.path.lowercase().contains(q) || it.name.lowercase().contains(q)
        }
        .sortedWith(
            compareBy<KbNoteRef> { note ->
                val nameHit = note.name.lowercase().contains(q)
                if (nameHit) 0 else 1
            }.thenBy { it.path.lowercase() },
        )
}
