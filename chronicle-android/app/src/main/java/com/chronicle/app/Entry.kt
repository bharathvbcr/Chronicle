package com.chronicle.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

const val DEVICE_CODE_ANDROID = "an"
const val ENTRY_SCHEMA_VERSION = 1

private val ENTRY_ID_PATTERN =
    Regex("""^\d{4}-\d{2}-\d{2}_\d{6}-(an|pc)(_[0-9]+)?$""")

private val ENTRY_KNOWN_KEYS = setOf(
    "version",
    "id",
    "ts",
    "type",
    "text",
    "text_enc",
    "tags",
    "images",
    "audio",
    "mood",
    "processed",
    "filed",
    "filed_content_hash",
    "filed_path",
)

data class Entry(
    val version: Int = ENTRY_SCHEMA_VERSION,
    val id: String,
    val ts: String,
    val type: String,
    val text: String,
    /** E2EE blob (CONTRACT v1.11) — non-null means `text` is ciphertext-free "". */
    val textEnc: JSONObject? = null,
    val tags: List<String>,
    val images: List<String> = emptyList(),
    val audio: List<String> = emptyList(),
    val mood: Int? = null,
    val processed: Boolean = false,
    val filed: Boolean = false,
    val filedContentHash: String? = null,
    val filedPath: String? = null,
    /** Unknown JSON keys preserved on load→save round-trip. */
    val extras: JSONObject = JSONObject(),
)

fun escapeJson(s: String): String {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

fun serializeEntry(entry: Entry): String {
    val obj = JSONObject()
    obj.put("version", entry.version)
    obj.put("id", entry.id)
    obj.put("ts", entry.ts)
    obj.put("type", entry.type)
    if (entry.textEnc != null) {
        // E2EE invariant: an encrypted entry never persists plaintext.
        // Unlocked session with fresh edits → re-seal (mirrors PC save_entry);
        // locked session → keep the stored blob, drop any stray plaintext.
        val sealed = if (entry.text.isNotBlank()) entryTextSealer?.invoke(entry.text) else null
        obj.put("text", "")
        obj.put("text_enc", sealed ?: entry.textEnc)
    } else {
        obj.put("text", entry.text)
    }
    obj.put("tags", JSONArray(entry.tags))
    obj.put("images", JSONArray(entry.images))
    obj.put("audio", JSONArray(entry.audio))
    if (entry.mood != null) {
        obj.put("mood", entry.mood)
    } else {
        obj.put("mood", JSONObject.NULL)
    }
    obj.put("processed", entry.processed)
    if (entry.filed) {
        obj.put("filed", true)
        entry.filedContentHash?.let { obj.put("filed_content_hash", it) }
        entry.filedPath?.let { obj.put("filed_path", it) }
    }
    // Preserve unknown keys (do not overwrite known fields if somehow duplicated)
    val extras = entry.extras
    val keys = extras.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key !in ENTRY_KNOWN_KEYS) {
            obj.put(key, extras.get(key))
        }
    }
    return obj.toString(2)
}

/**
 * Set at app start by MainViewModel: opens a `text_enc` blob when the E2EE
 * session is unlocked, returning plaintext; null/absent → locked (text "").
 * Kept here so every entry reader decrypts transparently through one seam.
 */
var entryTextOpener: ((JSONObject) -> String?)? = null

/**
 * Set at app start by MainViewModel: seals plaintext edits into a fresh
 * `text_enc` blob while the session is unlocked; null → keep stored blob
 * (locked). One seam so every writer preserves the no-plaintext invariant.
 */
var entryTextSealer: ((String) -> JSONObject?)? = null

fun deserializeEntry(jsonStr: String): Entry? {
    return try {
        val obj = JSONObject(jsonStr)
        val id = obj.getString("id")
        val ts = obj.getString("ts")
        val type = obj.getString("type")
        val text = obj.optString("text", "")
        val version = obj.optInt("version", ENTRY_SCHEMA_VERSION)
        val textEnc =
            if (obj.has("text_enc") && !obj.isNull("text_enc")) {
                obj.optJSONObject("text_enc")
            } else {
                null
            }
        // Decrypt-on-read: unlocked session yields plaintext; locked yields "".
        val resolvedText = if (textEnc != null) {
            entryTextOpener?.invoke(textEnc) ?: ""
        } else {
            text
        }
        val processed = obj.optBoolean("processed", false)
        val filed = obj.optBoolean("filed", false)
        val filedContentHash =
            if (obj.has("filed_content_hash") && !obj.isNull("filed_content_hash")) {
                obj.getString("filed_content_hash")
            } else {
                null
            }
        val filedPath =
            if (obj.has("filed_path") && !obj.isNull("filed_path")) {
                obj.getString("filed_path")
            } else {
                null
            }
        val mood = if (obj.isNull("mood")) null else obj.getInt("mood")

        val tagsList = mutableListOf<String>()
        val tagsArr = obj.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                tagsList.add(tagsArr.getString(i))
            }
        }

        val imagesList = mutableListOf<String>()
        val imagesArr = obj.optJSONArray("images")
        if (imagesArr != null) {
            for (i in 0 until imagesArr.length()) {
                imagesList.add(imagesArr.getString(i))
            }
        }

        val audioList = mutableListOf<String>()
        val audioArr = obj.optJSONArray("audio")
        if (audioArr != null) {
            for (i in 0 until audioArr.length()) {
                audioList.add(audioArr.getString(i))
            }
        }

        val extras = JSONObject()
        val allKeys = obj.keys()
        while (allKeys.hasNext()) {
            val key = allKeys.next()
            if (key !in ENTRY_KNOWN_KEYS) {
                extras.put(key, obj.get(key))
            }
        }

        Entry(
            version = version,
            id = id,
            ts = ts,
            type = type,
            text = resolvedText,
            textEnc = textEnc,
            tags = tagsList,
            images = imagesList,
            audio = audioList,
            mood = mood,
            processed = processed,
            filed = filed,
            filedContentHash = filedContentHash,
            filedPath = filedPath,
            extras = extras,
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** Generate collision-safe id: yyyy-MM-dd_HHmmss-an (+ _N). */
fun generateEntryId(
    now: ZonedDateTime = ZonedDateTime.now(),
    device: String = DEVICE_CODE_ANDROID,
    exists: (String) -> Boolean = { false },
): String {
    val base = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + "-$device"
    if (!exists(base)) return base
    var n = 2
    while (exists("${base}_$n")) {
        n++
    }
    return "${base}_$n"
}

fun entryYearMonth(idOrTs: String): Pair<String, String> {
    // Prefer id prefix yyyy-MM-dd_...
    val datePart = when {
        idOrTs.length >= 10 && idOrTs[4] == '-' && idOrTs[7] == '-' -> idOrTs.take(10)
        else -> {
            try {
                ZonedDateTime.parse(idOrTs).toLocalDate().toString()
            } catch (_: Exception) {
                ZonedDateTime.now().toLocalDate().toString()
            }
        }
    }
    val year = datePart.take(4)
    val month = datePart.substring(5, 7)
    return year to month
}

fun entryDayDate(entry: Entry): String {
    return try {
        ZonedDateTime.parse(entry.ts).toLocalDate().toString()
    } catch (_: Exception) {
        if (entry.id.length >= 10) entry.id.take(10) else ZonedDateTime.now().toLocalDate().toString()
    }
}

fun shardPath(root: String, year: String, month: String, fileName: String): String =
    "$root/$year/$month/$fileName"

/**
 * Dual-read candidates for journal media paths (mirrors PC `vault_paths.resolve_media_abs`).
 * Order: stored path first, then `_attachments` ↔ `img`/`audio` fallback.
 */
fun mediaDualReadCandidates(relativePath: String): List<String> {
    var cleaned = relativePath.trim().trimStart('/').replace('\\', '/')
    while ("//" in cleaned) cleaned = cleaned.replace("//", "/")
    if (cleaned.isEmpty() || ".." in cleaned.split("/") || "\u0000" in cleaned) {
        return emptyList()
    }
    val candidates = mutableListOf(cleaned)
    when {
        cleaned.startsWith("img/") ->
            candidates.add("_attachments/${cleaned.removePrefix("img/")}")
        cleaned.startsWith("audio/") ->
            candidates.add("_attachments/${cleaned.removePrefix("audio/")}")
        cleaned.startsWith("_attachments/") -> {
            val suffix = cleaned.removePrefix("_attachments/")
            candidates.add("img/$suffix")
            if (suffix.endsWith(".m4a", ignoreCase = true)) {
                candidates.add("audio/$suffix")
            }
        }
    }
    return candidates
}

/**
 * Manual validation matching contract/entry.schema.json required fields and patterns.
 * Used by unit tests (and optionally before write).
 */
fun validateEntryAgainstSchema(entry: Entry): List<String> {
    val errors = mutableListOf<String>()
    if (entry.version != 1) errors.add("version must be 1")
    if (!ENTRY_ID_PATTERN.matches(entry.id)) {
        errors.add("id does not match yyyy-MM-dd_HHmmss-<dev> pattern: ${entry.id}")
    }
    if (entry.ts.isBlank()) errors.add("ts is blank")
    if (entry.type !in setOf("log", "idea", "dream", "reflection")) {
        errors.add("type must be log|idea|dream|reflection")
    }
    entry.images.forEach { path ->
        if (!path.matches(Regex("""^(_attachments|img)/\d{4}/\d{2}/.+$"""))) {
            errors.add("invalid image path: $path")
        }
    }
    entry.audio.forEach { path ->
        if (!path.matches(Regex("""^(_attachments|audio)/\d{4}/\d{2}/.+\.m4a$"""))) {
            errors.add("invalid audio path: $path")
        }
    }
    entry.mood?.let { m ->
        if (m !in 1..5) errors.add("mood must be 1..5")
    }
    if (entry.text.isBlank() && entry.textEnc == null) {
        // Blank text is only legal alongside an E2EE blob (voice-note entries
        // also carry blank text until transcription — blob or audio covers it).
        if (entry.audio.isEmpty()) errors.add("blank text requires text_enc or audio")
    }
    entry.textEnc?.let { blob ->
        if (blob.optInt("v", -1) != 1 || blob.optString("nonce").isBlank() || blob.optString("ct").isBlank()) {
            errors.add("text_enc must be {v:1, nonce, ct}")
        }
    }
    // Ensure serialized JSON has required keys
    try {
        val obj = JSONObject(serializeEntry(entry))
        listOf("version", "id", "ts", "type", "text", "tags", "images", "processed").forEach { key ->
            if (!obj.has(key)) errors.add("missing required key: $key")
        }
        if (obj.optJSONArray("tags") == null) errors.add("tags must be array")
        if (obj.optJSONArray("images") == null) errors.add("images must be array")
    } catch (e: Exception) {
        errors.add("serialize failed: ${e.message}")
    }
    return errors
}

fun entryJsonHasNoLegacyFields(json: String): Boolean {
    val obj = JSONObject(json)
    return !obj.has("city") && !obj.has("weather")
}

fun typePlaceholder(type: String): String = when (type) {
    "dream" -> "What did you dream?"
    "idea" -> "Capture the idea…"
    "reflection" -> "What's on your mind?"
    else -> "What's happening?"
}
