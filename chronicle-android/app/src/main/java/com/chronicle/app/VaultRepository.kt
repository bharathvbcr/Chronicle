package com.chronicle.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.chronicle.app.brain.BrainGraph
import com.chronicle.app.brain.CurationOp
import com.chronicle.app.brain.DayInsight
import com.chronicle.app.brain.Enrichment
import com.chronicle.app.brain.PromptsFile
import com.chronicle.app.brain.TagsTaxonomy
import com.chronicle.app.brain.applyCurationOverlay
import com.chronicle.app.brain.parseCurationOps
import com.chronicle.app.brain.parseEnrichMonth
import com.chronicle.app.brain.parseGraph
import com.chronicle.app.brain.parseInsight
import com.chronicle.app.brain.parsePrompts
import com.chronicle.app.brain.parseTagsTaxonomy
import com.chronicle.app.health.HEALTH_SCHEMA_VERSION
import com.chronicle.app.health.HealthDay
import com.chronicle.app.health.HealthMonth
import com.chronicle.app.health.deserializeHealthMonth
import com.chronicle.app.health.mergeHealthDays
import com.chronicle.app.health.serializeHealthMonth
import com.chronicle.app.health.validateHealthMonth
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * SAF-backed vault IO for entries, media, and brain reads + curation appends.
 *
 * Phase 4: prefer `_capture/entries` + `_attachments`; dual-read legacy `entries`/`img`/`audio`.
 */
class VaultRepository(private val context: Context, private val treeUri: Uri) {

    fun loadEntries(): List<Entry> {
        val uris = listAllEntryJsonUris(context, treeUri)
        val results = mutableListOf<Entry>()
        for (uri in uris) {
            val content = readFileContent(context, uri) ?: continue
            deserializeEntry(content)?.let { results.add(it) }
        }
        return results.sortedByDescending { it.ts }
    }

    fun entryFileExists(id: String): Boolean = findEntryFileUri(id) != null

    fun findEntryFileUri(id: String): Uri? {
        val (year, month) = entryYearMonth(id)
        // Prefer _capture/entries, then legacy entries/
        findPath(context, treeUri, "_capture", "entries", year, month)?.let { dir ->
            findChildFile(context, treeUri, dir, "$id.json")?.let { return it }
        }
        findPath(context, treeUri, "entries", year, month)?.let { dir ->
            findChildFile(context, treeUri, dir, "$id.json")?.let { return it }
        }
        findChildDirAtRoot(context, treeUri, "entries")?.let { dir ->
            findChildFile(context, treeUri, dir, "$id.json")?.let { return it }
        }
        return null
    }

    fun saveEntry(
        entry: Entry,
        imageUris: List<Uri> = emptyList(),
        audioLocalPaths: List<String> = emptyList(),
    ): Entry? {
        val errors = validateEntryAgainstSchema(entry)
        if (errors.isNotEmpty()) {
            android.util.Log.e("VaultRepository", "Schema errors: $errors")
            return null
        }
        val (year, month) = entryYearMonth(entry.id)
        val needsMedia = imageUris.isNotEmpty() || audioLocalPaths.isNotEmpty()
        val imgDir = if (needsMedia || imageUris.isNotEmpty()) {
            getOrCreatePath(context, treeUri, "_attachments", year, month)
        } else {
            null
        }
        val audioDir = if (audioLocalPaths.isNotEmpty()) {
            getOrCreatePath(context, treeUri, "_attachments", year, month)
        } else {
            null
        }
        if (imageUris.isNotEmpty() && imgDir == null) return null
        if (audioLocalPaths.isNotEmpty() && audioDir == null) return null

        val savedImages = entry.images.toMutableList()
        imageUris.forEachIndexed { _, srcUri ->
            val n = savedImages.size + 1
            val fileName = "${entry.id}_$n.jpg"
            val dest = getOrCreateFileInDir(context, treeUri, imgDir!!, fileName, "image/jpeg")
                ?: return@forEachIndexed
            if (processAndSaveImage(context, srcUri, dest)) {
                savedImages.add(shardPath("_attachments", year, month, fileName))
            }
        }

        val savedAudio = entry.audio.toMutableList()
        audioLocalPaths.forEachIndexed { _, localPath ->
            val n = savedAudio.size + 1
            val fileName = "${entry.id}_$n.m4a"
            val dest = getOrCreateFileInDir(context, treeUri, audioDir!!, fileName, "audio/mp4")
                ?: return@forEachIndexed
            val srcFile = java.io.File(localPath)
            if (srcFile.exists()) {
                context.contentResolver.openOutputStream(dest)?.use { out ->
                    srcFile.inputStream().use { it.copyTo(out) }
                }
                savedAudio.add(shardPath("_attachments", year, month, fileName))
            }
        }

        val finalEntry = entry.copy(images = savedImages, audio = savedAudio)
        val json = serializeEntry(finalEntry)
        // Overwrite existing location (legacy or capture) to avoid duplicate rows.
        findEntryFileUri(finalEntry.id)?.let { existingUri ->
            val parent = parentDocumentUri(treeUri, existingUri) ?: return null
            return if (atomicWriteText(context, treeUri, parent, "${finalEntry.id}.json", json)) {
                finalEntry
            } else {
                null
            }
        }
        val entriesDir =
            getOrCreatePath(context, treeUri, "_capture", "entries", year, month) ?: return null
        if (!atomicWriteText(context, treeUri, entriesDir, "${finalEntry.id}.json", json)) {
            return null
        }
        return finalEntry
    }

    /**
     * Read a top-level JSON object key from vault config.json, or null when
     * the file/key is absent or malformed. Used to adopt PC-created e2ee
     * params (CONTRACT v1.11) instead of minting a divergent phone key.
     */
    fun readConfigJsonObject(key: String): org.json.JSONObject? {
        return try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val configFile = findChildFile(context, treeUri, rootUri, "config.json")
                ?: return null
            val parsed = org.json.JSONObject(readFileContent(context, configFile) ?: return null)
            parsed.optJSONObject(key)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Create-only write of a top-level key into vault config.json (used for the
     * e2ee block, CONTRACT v1.11). PC owns config.json — an existing key is
     * never overwritten; returns false when the block already exists.
     */
    fun updateConfigJsonCreateOnly(key: String, block: org.json.JSONObject): Boolean {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val configFile = findChildFile(context, treeUri, rootUri, "config.json")
        val obj = if (configFile != null) {
            val raw = readFileContent(context, configFile) ?: return false
            val parsed = try {
                org.json.JSONObject(raw)
            } catch (_: Exception) {
                return false
            }
            if (parsed.has(key)) return false
            parsed.put(key, block)
            parsed
        } else {
            org.json.JSONObject().put(key, block)
        }
        return atomicWriteText(
            context,
            treeUri,
            rootUri,
            "config.json",
            obj.toString(2),
        )
    }

    fun updateEntry(entry: Entry): Boolean {
        if (entry.processed) return false
        val errors = validateEntryAgainstSchema(entry)
        if (errors.isNotEmpty()) return false
        val json = serializeEntry(entry)
        findEntryFileUri(entry.id)?.let { existing ->
            val parent = parentDocumentUri(treeUri, existing) ?: return false
            return atomicWriteText(context, treeUri, parent, "${entry.id}.json", json)
        }
        val (year, month) = entryYearMonth(entry.id)
        val entriesDir =
            getOrCreatePath(context, treeUri, "_capture", "entries", year, month) ?: return false
        return atomicWriteText(context, treeUri, entriesDir, "${entry.id}.json", json)
    }

    fun deleteEntry(entry: Entry): Boolean {
        if (entry.processed) return false
        val uri = findEntryFileUri(entry.id) ?: return false
        return deleteDocument(context, uri)
    }

    fun loadGraph(): BrainGraph? {
        val brain = findChildDirAtRoot(context, treeUri, "brain") ?: return null
        val graphUri = findChildFile(context, treeUri, brain, "graph.json") ?: return null
        val content = readFileContent(context, graphUri) ?: return null
        return parseGraph(content)
    }

    /**
     * Cheap change signal for foreground polling: graph.json last-modified plus a
     * sample of recent-month entry files (count + newest display name).
     */
    fun vaultFingerprint(): VaultFingerprint {
        val graphLastModifiedMs = findChildDirAtRoot(context, treeUri, "brain")?.let { brain ->
            findChildFileMeta(context, treeUri, brain, "graph.json")?.lastModifiedMs
        }
        val (count, newest, maxMod) = sampleRecentEntrySignal()
        val knowledgeDirPresent = findChildDirAtRoot(context, treeUri, "30-Knowledge") != null
        val journalMdCount = findChildDirAtRoot(context, treeUri, "40-Journal")?.let { journal ->
            listChildFileNames(context, treeUri, journal) {
                it.endsWith(".md") && !it.equals("CLAUDE.md", ignoreCase = true) &&
                    !it.startsWith("MOC-") && !it.contains("sync-conflict")
            }.size
        } ?: 0
        return VaultFingerprint(
            graphLastModifiedMs = graphLastModifiedMs,
            recentEntryFileCount = count,
            newestEntryFileName = newest,
            recentEntryMaxModifiedMs = maxMod,
            knowledgeDirPresent = knowledgeDirPresent,
            journalMdCount = journalMdCount,
        )
    }

    private fun sampleRecentEntrySignal(): Triple<Int, String?, Long?> {
        val now = LocalDate.now()
        val months = listOf(now, now.minusMonths(1))
        val isEntryJson: (String) -> Boolean = {
            it.endsWith(".json") && !it.endsWith(".tmp") && !it.contains("sync-conflict")
        }
        var totalCount = 0
        var newestName: String? = null
        var maxMod: Long? = null
        for (monthDate in months) {
            val year = monthDate.year.toString()
            val month = "%02d".format(monthDate.monthValue)
            for (segments in listOf(
                arrayOf("_capture", "entries", year, month),
                arrayOf("entries", year, month),
            )) {
                val monthDir = findPath(context, treeUri, *segments) ?: continue
                val names = listChildFileNames(context, treeUri, monthDir, isEntryJson)
                if (names.isEmpty()) continue
                totalCount += names.size
                val monthNewest = names.maxOrNull()
                if (newestName == null || (monthNewest != null && monthNewest > newestName!!)) {
                    newestName = monthNewest
                }
                for (name in names) {
                    val mod = findChildFileMeta(context, treeUri, monthDir, name)?.lastModifiedMs
                    if (mod != null && (maxMod == null || mod > maxMod!!)) {
                        maxMod = mod
                    }
                }
            }
        }
        if (totalCount > 0) return Triple(totalCount, newestName, maxMod)
        val entriesRoot = findChildDirAtRoot(context, treeUri, "entries") ?: return Triple(0, null, null)
        val names = listChildFileNames(context, treeUri, entriesRoot, isEntryJson)
        for (name in names) {
            val mod = findChildFileMeta(context, treeUri, entriesRoot, name)?.lastModifiedMs
            if (mod != null && (maxMod == null || mod > maxMod!!)) {
                maxMod = mod
            }
        }
        return Triple(names.size, names.maxOrNull(), maxMod)
    }

    fun loadGraphArchive(year: String): BrainGraph? {
        val archiveDir = findPath(context, treeUri, "brain", "graph-archive") ?: return null
        val uri = findChildFile(context, treeUri, archiveDir, "$year.json") ?: return null
        val content = readFileContent(context, uri) ?: return null
        return parseGraph(content)
    }

    fun loadTodayInsight(date: LocalDate = LocalDate.now()): DayInsight? {
        val y = date.year.toString()
        val file = date.toString() + ".json"
        val dir = findPath(context, treeUri, "brain", "insights", y) ?: return null
        val uri = findChildFile(context, treeUri, dir, file) ?: return null
        val content = readFileContent(context, uri) ?: return null
        return parseInsight(content)
    }

    fun loadInsightForDate(date: String): DayInsight? {
        return try {
            loadTodayInsight(LocalDate.parse(date))
        } catch (_: Exception) {
            null
        }
    }

    fun loadPrompts(): PromptsFile? {
        val brain = findChildDirAtRoot(context, treeUri, "brain") ?: return null
        val uri = findChildFile(context, treeUri, brain, "prompts.json") ?: return null
        val content = readFileContent(context, uri) ?: return null
        return parsePrompts(content)
    }

    fun loadTagsTaxonomy(): TagsTaxonomy? {
        val brain = findChildDirAtRoot(context, treeUri, "brain") ?: return null
        val uri = findChildFile(context, treeUri, brain, "tags.json") ?: return null
        val content = readFileContent(context, uri) ?: return null
        return parseTagsTaxonomy(content)
    }

    fun loadEnrichmentForMonth(yearMonth: String): Map<String, Enrichment> {
        // yearMonth = yyyy-MM
        val enrichDir = findPath(context, treeUri, "brain", "enrich") ?: return emptyMap()
        val uri = findChildFile(context, treeUri, enrichDir, "$yearMonth.json") ?: return emptyMap()
        val content = readFileContent(context, uri) ?: return emptyMap()
        return parseEnrichMonth(content)
    }

    fun loadPhoneCurationOps(): List<CurationOp> {
        val opsDir = findPath(context, treeUri, "curation", "ops") ?: return emptyList()
        val uri = findChildFile(context, treeUri, opsDir, "phone.jsonl") ?: return emptyList()
        val content = readFileContent(context, uri) ?: return emptyList()
        return parseCurationOps(content)
    }

    fun appendCurationOp(op: CurationOp): Boolean {
        val opsDir = getOrCreatePath(context, treeUri, "curation", "ops") ?: return false
        return atomicAppendLine(context, treeUri, opsDir, "phone.jsonl", op.toJsonLine())
    }

    fun loadGraphWithOverlay(): BrainGraph? {
        val base = loadGraph() ?: return null
        val ops = loadPhoneCurationOps()
        return applyCurationOverlay(base, ops)
    }

    /**
     * Resolve a journal media path under the vault, dual-reading
     * `_attachments/` ↔ legacy `img/` / `audio/` like PC `vault_paths.resolve_media_abs`.
     */
    fun resolveMediaUri(relativePath: String): Uri? {
        for (candidate in mediaDualReadCandidates(relativePath)) {
            resolveRelativePath(context, treeUri, candidate)?.let { return it }
        }
        return null
    }

    /**
     * Walk journal + derived notes for .md files (read-only).
     * Categories are Journal (`journal`) or Derived (`derived`) for UI grouping.
     * Metadata-only listing — use [loadNoteBody] when opening a note.
     */
    fun loadNotes(): List<NoteRef> {
        val results = mutableListOf<NoteRef>()
        findChildDirAtRoot(context, treeUri, "40-Journal")?.let { journalRoot ->
            walkMarkdownMetadata(context, treeUri, journalRoot, prefix = "40-Journal") { meta ->
                if (!KnowledgePathMap.isJournalDayFile(meta.name)) return@walkMarkdownMetadata
                results.add(
                    NoteRef(
                        path = meta.path,
                        name = meta.name,
                        category = "journal",
                        text = kbNoteListingPreview(meta.name),
                    ),
                )
            }
        }
        resolveRelativePath(context, treeUri, "Upcoming.md")?.let {
            results.add(
                NoteRef(
                    path = "Upcoming.md",
                    name = "Upcoming.md",
                    category = "derived",
                    text = kbNoteListingPreview("Upcoming.md"),
                ),
            )
        }
        findPath(context, treeUri, "_system", "derived")?.let { derivedRoot ->
            walkMarkdownMetadata(context, treeUri, derivedRoot, prefix = "_system/derived") { meta ->
                results.add(
                    NoteRef(
                        path = meta.path,
                        name = meta.name,
                        category = "derived",
                        text = kbNoteListingPreview(meta.name),
                    ),
                )
            }
        }
        findChildDirAtRoot(context, treeUri, "notes")?.let { notesRoot ->
            walkMarkdownMetadata(context, treeUri, notesRoot, prefix = "notes") { meta ->
                results.add(
                    NoteRef(
                        path = meta.path,
                        name = meta.name,
                        category = "derived",
                        text = kbNoteListingPreview(meta.name),
                    ),
                )
            }
        }
        return results.sortedByDescending { it.path }
    }

    /** Load full markdown body for a journal/derived note path. */
    fun loadNoteBody(path: String): String? {
        val rel = KnowledgePathMap.norm(path)
        val uri = resolveRelativePath(context, treeUri, rel) ?: return null
        return readFileContent(context, uri)
    }

    /**
     * PARA knowledge areas (metadata-only listing).
     * [KbNoteRef.path] is vault-relative. Legacy kb/notes is not listed (cutover).
     */
    fun listKbNotes(): List<KbNoteRef> {
        val results = mutableListOf<KbNoteRef>()
        val seenKeys = mutableSetOf<String>()

        fun addMeta(meta: MarkdownFileMeta) {
            if (KnowledgePathMap.isChromeBasename(meta.name)) return
            seenKeys.add(KnowledgePathMap.shadowKey(meta.path))
            results.add(
                KbNoteRef(
                    path = meta.path,
                    name = meta.name,
                    category = KnowledgePathMap.categoryFor(meta.path),
                    text = kbNoteListingPreview(meta.name),
                ),
            )
        }

        resolveRelativePath(context, treeUri, KnowledgePathMap.HOME_NOTE)?.let {
            addMeta(MarkdownFileMeta(KnowledgePathMap.HOME_NOTE, "Home.md", null))
        }

        for (area in KnowledgePathMap.PARA_AREAS) {
            val areaDir = findChildDirAtRoot(context, treeUri, area) ?: continue
            walkMarkdownMetadata(context, treeUri, areaDir, prefix = area, onFile = ::addMeta)
        }
        return sortKbNotesWithHubs(results)
    }

    /** Load full markdown body for a knowledge note (PARA resolve). */
    fun loadKbNoteBody(path: String): String? {
        val uri = resolveKbNoteReadUri(path) ?: return null
        return readFileContent(context, uri)
    }

    /** List template files under `_templates/` (metadata only). */
    fun listKbTemplates(): List<MarkdownFileMeta> {
        val templatesDir = findChildDirAtRoot(context, treeUri, KnowledgePathMap.TEMPLATES_DIR)
            ?: return emptyList()
        val results = mutableListOf<MarkdownFileMeta>()
        walkMarkdownMetadata(context, treeUri, templatesDir, prefix = KnowledgePathMap.TEMPLATES_DIR) { meta ->
            results.add(meta)
        }
        return results.sortedBy { it.name.lowercase() }
    }

    /** Load a template body by vault-relative path. */
    fun loadTemplateBody(path: String): String? {
        val uri = resolveRelativePath(context, treeUri, KnowledgePathMap.norm(path)) ?: return null
        return readFileContent(context, uri)
    }

    /** First existing dual-read URI for [path], or null. */
    fun resolveKbNoteReadUri(path: String): Uri? {
        for (candidate in KnowledgePathMap.candidateReadRels(path)) {
            resolveRelativePath(context, treeUri, candidate)?.let { return it }
        }
        return null
    }

    /**
     * Create or overwrite a knowledge markdown note (PARA preferred).
     * [path] is vault-relative or legacy-relative (see [KnowledgePathMap]).
     * Updates write to the existing dual-read location when present.
     */
    fun saveKbNote(path: String, text: String): Boolean {
        val stamped = NoteFrontmatter.bumpUpdated(text)
        val matchedRel = KnowledgePathMap.candidateReadRels(path).firstOrNull { cand ->
            resolveRelativePath(context, treeUri, cand) != null
        }
        val destRel = matchedRel
            ?: KnowledgePathMap.preferredWriteRel(path, create = true)
            ?: return false
        val (dirs, fileName) = KnowledgePathMap.splitVaultRel(destRel) ?: return false
        val parentDir = vaultRootDirUri(dirs) ?: return false
        return atomicWriteText(
            context,
            treeUri,
            parentDir,
            fileName,
            stamped,
            mimeType = "text/markdown",
        )
    }

    /**
     * Create a new knowledge note. Defaults by [section] (kb → 30-Knowledge, notes → 00-Inbox).
     * Dual-read collisions return [CreateKbNoteResult.AlreadyExists] (409 parity with PC).
     */
    fun createKbNote(
        name: String,
        text: String = "",
        folder: String? = null,
        area: String? = null,
        section: String? = null,
    ): CreateKbNoteResult {
        val destRel = KnowledgePathMap.createPath(name, area = area, folder = folder, section = section)
            ?: return CreateKbNoteResult.InvalidPath
        // Dual-read existence check — do not invent -n.md when any candidate exists
        if (resolveKbNoteReadUri(destRel) != null) {
            return CreateKbNoteResult.AlreadyExists(destRel)
        }
        val seed = text.ifBlank {
            val title = name.trim().removeSuffix(".md").removeSuffix(".MD").ifBlank { "Note" }
            "# $title\n\n"
        }
        val title = name.trim().removeSuffix(".md").removeSuffix(".MD").ifBlank { "Note" }
        val stamped = NoteFrontmatter.ensureCreateFrontmatter(seed, title = title)
        return if (saveKbNote(destRel, stamped)) {
            CreateKbNoteResult.Success(destRel)
        } else {
            CreateKbNoteResult.WriteFailed
        }
    }

    /** Delete a knowledge note at [path] (dual-read resolve). */
    fun deleteKbNote(path: String): Boolean {
        val uri = resolveKbNoteReadUri(path) ?: return false
        return deleteDocument(context, uri)
    }

    /**
     * Move a knowledge note to [toPath] (PARA only). Reads primary (PARA-preferring),
     * writes destination, deletes sources, quarantines leftover legacy peers.
     */
    fun moveKbNote(fromPath: String, toPath: String): Boolean {
        val toRel = KnowledgePathMap.validateKnowledgeRel(toPath) ?: return false
        if (!KnowledgePathMap.isParaPrefix(toRel)) return false
        if (KnowledgePathMap.isChromePath(toRel)) return false

        val peers = KnowledgePathMap.candidateReadRels(fromPath)
        if (peers.isEmpty()) return false
        val primaryRel = peers.firstOrNull { KnowledgePathMap.isParaPrefix(it) } ?: peers.first()
        val srcUri = resolveKbNoteReadUri(primaryRel) ?: return false
        val content = readFileContent(context, srcUri) ?: return false

        if (primaryRel != toRel && resolveKbNoteReadUri(toRel) != null) return false

        val (dirs, fileName) = KnowledgePathMap.splitVaultRel(toRel) ?: return false
        val parentDir = vaultRootDirUri(dirs) ?: return false
        if (!atomicWriteText(context, treeUri, parentDir, fileName, content, mimeType = "text/markdown")) {
            return false
        }

        for (peer in peers) {
            if (peer == toRel) continue
            resolveRelativePath(context, treeUri, peer)?.let { deleteDocument(context, it) }
        }
        quarantineLegacyKbPeers(fromPath, skipRels = peers.toSet() + toRel)
        return true
    }

    /** Archive a knowledge note under `90-Archive/<original subpath>`. */
    fun archiveKbNote(path: String): Boolean {
        val rel = KnowledgePathMap.validateKnowledgeRel(path) ?: return false
        val suffix = paraSuffix(rel)
        val dest = if (KnowledgePathMap.isParaPrefix(rel) && rel.startsWith("90-Archive/")) {
            if (suffix.startsWith("_legacy-kb/")) rel else "90-Archive/$suffix"
        } else {
            "90-Archive/$suffix"
        }
        return moveKbNote(path, dest)
    }

    private fun paraSuffix(rel: String): String {
        val p = KnowledgePathMap.norm(rel)
        if (KnowledgePathMap.isLegacyKbPath(p)) {
            return p.removePrefix("${KnowledgePathMap.LEGACY_KB_NOTES}/")
        }
        for (area in KnowledgePathMap.PARA_AREAS) {
            if (p == area) return p.substringAfterLast('/')
            if (p.startsWith("$area/")) return p.removePrefix("$area/")
        }
        return p.substringAfterLast('/')
    }

    private fun quarantineLegacyKbPeers(primaryRel: String, skipRels: Set<String>) {
        for (peer in KnowledgePathMap.candidateReadRels(primaryRel)) {
            if (peer in skipRels) continue
            if (!KnowledgePathMap.isLegacyKbPath(peer)) continue
            val uri = resolveRelativePath(context, treeUri, peer) ?: continue
            val content = readFileContent(context, uri) ?: continue
            val suffix = peer.removePrefix("${KnowledgePathMap.LEGACY_KB_NOTES}/")
            val destRel = "90-Archive/_legacy-kb/$suffix"
            val split = KnowledgePathMap.splitVaultRel(destRel) ?: continue
            val parent = getOrCreatePath(context, treeUri, *split.first.toTypedArray()) ?: continue
            if (!checkFileExistsInDir(context, treeUri, parent, split.second)) {
                atomicWriteText(context, treeUri, parent, split.second, content, mimeType = "text/markdown")
            }
            deleteDocument(context, uri)
        }
    }

    private fun vaultRootDirUri(dirs: List<String>): Uri? {
        if (dirs.isEmpty()) {
            val rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        }
        return getOrCreatePath(context, treeUri, *dirs.toTypedArray())
    }

    /**
     * Walk ResumePoints under `10-Work/ResumePoints/` (preferred). Phone is read-only.
     */
    fun loadResumePoints(): List<ResumePointRef> {
        val results = mutableListOf<ResumePointRef>()
        val seenNames = mutableSetOf<String>()

        fun collect(prefix: String, dir: Uri) {
            walkMarkdownFiles(context, treeUri, dir, prefix = prefix) { path, name, content ->
                val key = name.lowercase()
                if (key in seenNames) return@walkMarkdownFiles
                seenNames.add(key)
                results.add(
                    ResumePointRef(
                        path = path,
                        name = name,
                        text = content,
                    ),
                )
            }
        }

        findPath(context, treeUri, "10-Work", "ResumePoints")?.let { dir ->
            collect("10-Work/ResumePoints", dir)
        }
        return results.sortedBy { it.name.lowercase() }
    }

    /**
     * Load `health/yyyy/MM.json` for [yearMonth] (`yyyy-MM`).
     * Returns an empty map when the file is missing or unreadable.
     */
    fun loadHealthMonth(yearMonth: String): Map<String, HealthDay> {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return emptyMap()
        val year = parts[0]
        val month = parts[1]
        val healthRoot = findChildDirAtRoot(context, treeUri, "health") ?: return emptyMap()
        val yearDir = findChildDir(context, treeUri, healthRoot, year) ?: return emptyMap()
        val uri = findChildFile(context, treeUri, yearDir, "$month.json") ?: return emptyMap()
        val content = readFileContent(context, uri) ?: return emptyMap()
        return deserializeHealthMonth(content)?.days.orEmpty()
    }

    /**
     * Merge [days] into `health/yyyy/MM.json` (incoming dates overwrite) and write atomically.
     * [yearMonth] is `yyyy-MM`. Returns false on write failure.
     */
    fun saveHealthMonth(
        yearMonth: String,
        days: Map<String, HealthDay>,
    ): Boolean {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return false
        val year = parts[0]
        val month = parts[1]
        val healthDir = getOrCreatePath(context, treeUri, "health", year) ?: return false
        val existing = loadHealthMonth(yearMonth)
        val merged = mergeHealthDays(existing, days)
        val file = HealthMonth(
            version = HEALTH_SCHEMA_VERSION,
            month = yearMonth,
            days = merged,
        )
        val errors = validateHealthMonth(file)
        if (errors.isNotEmpty()) {
            android.util.Log.e("VaultRepository", "Health schema errors: $errors")
            return false
        }
        val json = serializeHealthMonth(file)
        return atomicWriteText(context, treeUri, healthDir, "$month.json", json)
    }

    companion object {
        fun nowTs(): String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        fun createId(context: Context, treeUri: Uri): String {
            val repo = VaultRepository(context, treeUri)
            return generateEntryId(exists = { repo.entryFileExists(it) })
        }
    }
}
