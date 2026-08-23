package com.chronicle.app

/** Prefill for the create-note dialog from a tree folder context. */
data class CreateFolderContext(val area: String, val folder: String)

/**
 * PARA knowledge path map (v1.10 — dual-read cutover complete).
 * Mirrors PC chronicle_pipeline.path_map semantics.
 * Legacy kb/notes is retired; [isLegacyKbPath] remains for detection only.
 */
object KnowledgePathMap {
    val PARA_AREAS: List<String> = listOf(
        "00-Inbox",
        "10-Work",
        "20-Personal",
        "30-Knowledge",
        "90-Archive",
    )

    const val LEGACY_KB_NOTES = "kb/notes"
    const val KB_AREA = "30-Knowledge"
    val NOTES_AREAS: List<String> = PARA_AREAS.filter { it != KB_AREA }
    const val SECTION_KB = "kb"
    const val SECTION_NOTES = "notes"
    const val SECTION_JOURNAL = "journal"

    /** Default when section is unspecified (Notes tab / Inbox). */
    const val DEFAULT_CREATE_AREA = "00-Inbox"
    const val RESUME_POINTS_AREA = "10-Work"

    /** True if basename is a MOC hub row (kept visible, pinned in list). */
    fun isMocBasename(name: String): Boolean = name.startsWith("MOC-", ignoreCase = true)

    /** Vault-root hub note shown at top of Notes lists. */
    const val HOME_NOTE = "Home.md"

    /** Template directory (metadata listing; body loaded on pick). */
    const val TEMPLATES_DIR = "_templates"

    /** Hide from knowledge trees (keep MOCs visible). */
    private val CHROME_BASENAMES = setOf("CLAUDE.md", ".gitkeep", "README.md")

    private val SAFE_REL = Regex("""^[A-Za-z0-9._\- /]+$""")
    private val JOURNAL_DAY_RE = Regex("""^\d{4}-\d{2}-\d{2}\.md$""")

    fun norm(path: String): String {
        var p = path.trim().trimStart('/').replace('\\', '/')
        while ("//" in p) p = p.replace("//", "/")
        return p
    }

    fun isParaPrefix(rel: String): Boolean {
        val p = norm(rel)
        return PARA_AREAS.any { p == it || p.startsWith("$it/") }
    }

    /** True for retired kb/notes paths (cutover/detection only). */
    fun isLegacyKbPath(rel: String): Boolean {
        val p = norm(rel)
        return p == LEGACY_KB_NOTES || p.startsWith("$LEGACY_KB_NOTES/")
    }

    fun isKnowledgePath(rel: String): Boolean =
        norm(rel) == HOME_NOTE || isParaPrefix(rel)

    fun isValidSection(section: String?): Boolean =
        section == null || section == SECTION_KB || section == SECTION_NOTES

    /** UI section for a knowledge path: kb / notes, or null if not knowledge. */
    fun sectionFor(rel: String): String? {
        val p = norm(rel)
        if (p == HOME_NOTE) return SECTION_NOTES
        if (p == KB_AREA || p.startsWith("$KB_AREA/")) return SECTION_KB
        for (area in NOTES_AREAS) {
            if (p == area || p.startsWith("$area/")) return SECTION_NOTES
        }
        return null
    }

    fun defaultCreateArea(section: String): String =
        if (section == SECTION_KB) KB_AREA else DEFAULT_CREATE_AREA

    /**
     * Parent PARA folder for a note path (empty string when at vault root, e.g. Home.md).
     * Mirrors web `parentFolder`.
     */
    fun parentFolder(notePath: String): String {
        val parts = norm(notePath).split('/').filter { it.isNotBlank() }
        if (parts.size <= 1) return ""
        return parts.dropLast(1).joinToString("/")
    }

    /**
     * Split a full PARA folder path into area + relative folder for create dialog prefill.
     * e.g. `10-Work/Projects` → area `10-Work`, folder `Projects`.
     */
    fun splitCreateFolderContext(folderPath: String): CreateFolderContext? {
        val p = norm(folderPath)
        if (p.isBlank()) return null
        for (area in PARA_AREAS) {
            if (p == area) return CreateFolderContext(area, "")
            if (p.startsWith("$area/")) {
                return CreateFolderContext(area, p.removePrefix("$area/"))
            }
        }
        return null
    }

    /** True if basename is vault chrome to hide from app knowledge trees. */
    fun isChromeBasename(name: String): Boolean = name in CHROME_BASENAMES

    fun isChromePath(rel: String): Boolean {
        val name = norm(rel).substringAfterLast('/')
        return isChromeBasename(name)
    }

    fun isJournalDayFile(name: String): Boolean = JOURNAL_DAY_RE.matches(name)

    fun isJournalFencePath(rel: String): Boolean {
        val p = norm(rel)
        return p.startsWith("40-Journal/") && isJournalDayFile(p.substringAfterLast('/'))
    }

    fun isJournalDerivedPath(rel: String): Boolean {
        val p = norm(rel)
        return p.startsWith("_system/derived/") ||
            p == "Upcoming.md" ||
            (p.startsWith("notes/") && p.endsWith(".md", ignoreCase = true))
    }

    /** Hard create-scope: path must belong to [section] when section is set. */
    fun pathAllowedForSection(rel: String, section: String?): Boolean {
        if (section == null) return true
        if (!isValidSection(section)) return false
        return sectionFor(rel) == section
    }

    /**
     * Normalize API/UI path to vault-relative knowledge path.
     * Accepts PARA, bare relative (→ Inbox / Work ResumePoints), or legacy
     * `kb/notes/...` (returned as-is for detection; validate rejects).
     */
    fun normalizeApiPath(path: String): String? {
        val p = norm(path)
        if (p.isBlank()) return null
        if (p == LEGACY_KB_NOTES) return null
        if (p.startsWith("$LEGACY_KB_NOTES/")) return p
        if (isParaPrefix(p)) return p
        if (p.startsWith("ResumePoints/")) return "$RESUME_POINTS_AREA/$p"
        return "$DEFAULT_CREATE_AREA/$p"
    }

    fun validateKnowledgeRel(rel: String): String? {
        val p = norm(rel)
        if (p == HOME_NOTE) return p
        val normalized = normalizeApiPath(rel) ?: return null
        if (isLegacyKbPath(normalized)) return null
        if (".." in normalized || "\u0000" in normalized) return null
        if (!SAFE_REL.matches(normalized)) return null
        if (!normalized.endsWith(".md", ignoreCase = true)) return null
        if (!isKnowledgePath(normalized)) return null
        return normalized
    }

    /**
     * Ordered vault-relative candidates for reading (PARA only).
     * Exact path first. No legacy kb/notes peers.
     */
    fun candidateReadRels(rel: String): List<String> {
        val p = norm(rel)
        if (p == HOME_NOTE) return listOf(HOME_NOTE)
        val normalized = normalizeApiPath(rel) ?: return emptyList()
        if (isLegacyKbPath(normalized)) return emptyList()
        return listOf(normalized)
    }

    /**
     * Canonical write path. Legacy/bare paths map to PARA
     * (Inbox or Work/ResumePoints). PARA paths stay as-is.
     */
    fun preferredWriteRel(rel: String, create: Boolean = false): String? {
        val p = normalizeApiPath(rel) ?: return null
        if (isLegacyKbPath(p)) {
            val suffix = p.removePrefix("$LEGACY_KB_NOTES/")
            if (suffix.startsWith("ResumePoints/")) {
                return "$RESUME_POINTS_AREA/$suffix"
            }
            return "$DEFAULT_CREATE_AREA/$suffix"
        }
        // create flag retained for API parity with PC path_map
        if (create) {
            return p
        }
        return p
    }

    /** Split vault-relative knowledge path into dir segments + filename. */
    fun splitVaultRel(rel: String): Pair<List<String>, String>? {
        val validated = validateKnowledgeRel(rel) ?: return null
        if (validated == HOME_NOTE) return emptyList<String>() to HOME_NOTE
        val parts = validated.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        if (parts.any { it == "." || it == ".." }) return null
        val fileName = parts.last()
        return parts.dropLast(1) to fileName
    }

    /** Area label for UI grouping (PARA area). */
    fun categoryFor(vaultRel: String): String {
        val p = norm(vaultRel)
        if (p == HOME_NOTE) return "Hub"
        for (area in PARA_AREAS) {
            if (p == area || p.startsWith("$area/")) return area
        }
        return "other"
    }

    /**
     * Dedup key for PARA paths (legacy shadow keys unused after cutover).
     */
    fun shadowKey(vaultRel: String): String {
        val p = norm(vaultRel)
        for (area in PARA_AREAS) {
            if (p.startsWith("$area/")) return "para:" + p.removePrefix("$area/")
        }
        return p
    }

    @Suppress("UNUSED_PARAMETER")
    fun legacyShadowKeysForPara(vaultRel: String): Set<String> {
        // Retained for call-site compatibility; always empty after cutover.
        return emptySet()
    }

    /**
     * Build create path under [area] or section default.
     * When [section] is set, [area] must belong to that section (hard scope).
     */
    fun createPath(
        name: String,
        area: String? = null,
        folder: String? = null,
        section: String? = null,
    ): String? {
        if (section != null && !isValidSection(section)) return null
        val allowedAreas = when (section) {
            SECTION_KB -> listOf(KB_AREA)
            SECTION_NOTES -> NOTES_AREAS
            else -> PARA_AREAS
        }
        // Explicit area outside the section is a hard reject (do not remap)
        if (section != null && area != null && area !in allowedAreas) return null
        val destArea = when {
            area != null && area in allowedAreas -> area
            section != null -> defaultCreateArea(section)
            area != null && area in PARA_AREAS -> area
            else -> DEFAULT_CREATE_AREA
        }
        val file = kbNoteFileName(name, folder)
        val full = "$destArea/$file"
        val validated = validateKnowledgeRel(full) ?: return null
        if (!pathAllowedForSection(validated, section)) return null
        return validated
    }
}
