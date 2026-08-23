package com.chronicle.app

import java.time.LocalDate

/**
 * Minimal YAML frontmatter helpers for knowledge notes.
 * Auto-bumps `updated` on save; ensureCreateFrontmatter on create
 * (mirrors PC conventions: created/updated/type/title).
 */
object NoteFrontmatter {
    private val FRONTMATTER_BLOCK =
        Regex("""^---\r?\n([\s\S]*?)\r?\n---\r?\n?""")

    private val DEFAULT_KEY_ORDER =
        listOf("title", "created", "updated", "type", "tags", "aliases")

    /** ISO date for frontmatter fields. */
    fun todayDate(): String = LocalDate.now().toString()

    /** Body after YAML frontmatter (Timeline rollups); unchanged if no FM block. */
    fun stripFrontmatter(content: String): String {
        val match = FRONTMATTER_BLOCK.find(content) ?: return content
        return content.substring(match.range.last + 1).trimStart('\n', '\r')
    }

    /** Replace or insert `updated: YYYY-MM-DD` in an existing frontmatter block. */
    fun bumpUpdated(content: String, date: String = todayDate()): String {
        val match = FRONTMATTER_BLOCK.find(content) ?: return content
        val bodyStart = match.range.last + 1
        val fmText = match.groupValues[1]
        val updatedLine = Regex("""^updated:\s*.+$""", RegexOption.MULTILINE)
        val newFm = if (updatedLine.containsMatchIn(fmText)) {
            fmText.replace(updatedLine, "updated: $date")
        } else {
            fmText.trimEnd() + "\nupdated: $date"
        }
        return content.substring(0, match.range.first) +
            "---\n$newFm\n---\n" +
            content.substring(bodyStart)
    }

    /** Expand `{{title}}` / `{{date}}` placeholders in a template body. */
    fun applyTemplatePlaceholders(template: String, title: String, date: String = todayDate()): String =
        template
            .replace("{{title}}", title)
            .replace("{{date}}", date)

    /**
     * Fill missing create-time frontmatter per conventions.
     * Existing keys preserved; `updated` always stamped to [date].
     */
    fun ensureCreateFrontmatter(
        content: String,
        title: String? = null,
        type: String = "note",
        date: String = todayDate(),
    ): String {
        val match = FRONTMATTER_BLOCK.find(content)
        val fm = linkedMapOf<String, String>()
        val body: String
        if (match != null) {
            for (line in match.groupValues[1].lines()) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                fm[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            body = content.substring(match.range.last + 1)
        } else {
            body = content
        }

        fun setKey(key: String, value: String, overwrite: Boolean) {
            val existing = fm.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            if (existing != null) {
                if (!overwrite && fm[existing]!!.isNotBlank()) return
                if (existing != key) fm.remove(existing)
            }
            fm[key] = value
        }

        val cleaned = title?.trim().orEmpty()
        if (cleaned.isNotEmpty()) setKey("title", cleaned, overwrite = false)
        setKey("created", date, overwrite = false)
        setKey("updated", date, overwrite = true)
        setKey("type", type, overwrite = false)
        setKey("tags", "[]", overwrite = false)

        val ordered = linkedMapOf<String, String>()
        for (k in DEFAULT_KEY_ORDER) {
            fm[k]?.let { ordered[k] = it }
        }
        for ((k, v) in fm) {
            if (k !in ordered) ordered[k] = v
        }
        val fmBlock = ordered.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        var nextBody = body
        if (nextBody.isNotEmpty() && !nextBody.startsWith("\n")) {
            nextBody = "\n$nextBody"
        }
        if (nextBody.isEmpty()) nextBody = "\n"
        return "---\n$fmBlock\n---$nextBody"
    }
}
