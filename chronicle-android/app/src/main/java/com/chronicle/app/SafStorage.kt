package com.chronicle.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract

/**
 * True when [uriString] is covered by a persisted SAF grant with read+write.
 * Matches by exact Uri, string form, or tree document id (encoding variants).
 */
fun hasPersistedPermission(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrEmpty()) return false
    return try {
        val uri = Uri.parse(uriString)
        val persistedPermissions = context.contentResolver.persistedUriPermissions
        persistedPermissions.any { perm ->
            perm.isReadPermission &&
                perm.isWritePermission &&
                uriPermissionMatches(perm.uri, uri)
        }
    } catch (_: Exception) {
        false
    }
}

/** Whether a persisted permission Uri refers to the same tree as [saved]. */
fun uriPermissionMatches(persisted: Uri, saved: Uri): Boolean {
    if (persisted == saved) return true
    if (persisted.toString() == saved.toString()) return true
    return try {
        DocumentsContract.getTreeDocumentId(persisted) ==
            DocumentsContract.getTreeDocumentId(saved)
    } catch (_: Exception) {
        false
    }
}

fun getOrCreateSubDirectory(context: Context, treeUri: Uri, folderName: String): Uri? {
    val contentResolver = context.contentResolver
    val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    var existingUri: Uri? = null
    try {
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                val mime = cursor.getString(mimeIndex)
                if (name == folderName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val docId = cursor.getString(idIndex)
                    existingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    break
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    if (existingUri != null) return existingUri

    return try {
        DocumentsContract.createDocument(
            contentResolver,
            rootDocumentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            folderName,
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** Resolve or create nested path segments under the vault tree. */
fun getOrCreatePath(context: Context, treeUri: Uri, vararg segments: String): Uri? {
    var current: Uri? = null
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    var parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    for (segment in segments) {
        current = findChildDir(context, treeUri, parentUri, segment)
            ?: createChildDir(context, parentUri, segment)
            ?: return null
        parentUri = current
    }
    return current
}

/** Session-scoped SAF child lookup cache (document id path → URI). */
private val safChildCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()

private fun safCacheKey(parentUri: Uri, name: String, dir: Boolean): String =
    "${DocumentsContract.getDocumentId(parentUri)}\u0000$name\u0000${if (dir) "d" else "f"}"

/** Drop cached SAF lookups (e.g. after vault switch or failed writes). */
fun clearSafChildCache() {
    safChildCache.clear()
}

fun findChildDir(context: Context, treeUri: Uri, parentUri: Uri, name: String): Uri? {
    val key = safCacheKey(parentUri, name, dir = true)
    safChildCache[key]?.let { return it }
    val parentDocId = DocumentsContract.getDocumentId(parentUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name &&
                    cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                    safChildCache[key] = uri
                    return uri
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

private fun createChildDir(context: Context, parentUri: Uri, name: String): Uri? {
    return try {
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )
        if (created != null) {
            safChildCache[safCacheKey(parentUri, name, dir = true)] = created
        }
        created
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun findChildFile(context: Context, treeUri: Uri, parentUri: Uri, fileName: String): Uri? {
    return findChildFileMeta(context, treeUri, parentUri, fileName)?.uri
}

/** Child file URI plus last-modified millis when the provider exposes it. */
data class ChildFileMeta(val uri: Uri, val lastModifiedMs: Long?)

fun findChildFileMeta(
    context: Context,
    treeUri: Uri,
    parentUri: Uri,
    fileName: String,
): ChildFileMeta? {
    val key = safCacheKey(parentUri, fileName, dir = false)
    val cachedUri = safChildCache[key]
    val parentDocId = DocumentsContract.getDocumentId(parentUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == fileName) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                    safChildCache[key] = uri
                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        null
                    }
                    return ChildFileMeta(uri, modified)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    // Stale cache entry after delete/rename
    if (cachedUri != null) safChildCache.remove(key)
    return null
}

fun getOrCreateFileInDir(
    context: Context,
    treeUri: Uri,
    parentDirUri: Uri,
    fileName: String,
    mimeType: String,
): Uri? {
    findChildFile(context, treeUri, parentDirUri, fileName)?.let { return it }
    return try {
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentDirUri,
            mimeType,
            fileName,
        )
        if (created != null) {
            safChildCache[safCacheKey(parentDirUri, fileName, dir = false)] = created
        }
        created
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun checkFileExistsInDir(context: Context, treeUri: Uri, dirUri: Uri, fileName: String): Boolean {
    return findChildFile(context, treeUri, dirUri, fileName) != null
}

fun listChildFiles(context: Context, treeUri: Uri, dirUri: Uri, predicate: (String) -> Boolean): List<Uri> {
    val parentDocId = DocumentsContract.getDocumentId(dirUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    val results = mutableListOf<Uri>()
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                if (predicate(name)) {
                    results.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex)))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return results
}

/** Display names of matching children (no URI build) — for cheap fingerprint sampling. */
fun listChildFileNames(context: Context, treeUri: Uri, dirUri: Uri, predicate: (String) -> Boolean): List<String> {
    val parentDocId = DocumentsContract.getDocumentId(dirUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    val results = mutableListOf<String>()
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (predicate(name)) results.add(name)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return results
}

fun listChildDirs(context: Context, treeUri: Uri, dirUri: Uri): List<Pair<String, Uri>> {
    val parentDocId = DocumentsContract.getDocumentId(dirUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    val results = mutableListOf<Pair<String, Uri>>()
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val name = cursor.getString(nameIndex)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                    results.add(name to uri)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return results
}

fun readFileContent(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Atomic write: write to `<name>.tmp`, then swap into place via rename.
 * When the target already exists, renames it to `.bak` first so a crash cannot
 * leave the entry deleted. Returns false if any rename fails (and restores `.bak`
 * when possible).
 */
fun atomicWriteText(
    context: Context,
    treeUri: Uri,
    parentDirUri: Uri,
    fileName: String,
    content: String,
    mimeType: String = "application/json",
): Boolean {
    return try {
        val tmpName = "$fileName.tmp"
        val bakName = "$fileName.bak"
        // Remove stale temp if present
        findChildFile(context, treeUri, parentDirUri, tmpName)?.let {
            DocumentsContract.deleteDocument(context.contentResolver, it)
        }
        // A crash between the two swap renames below leaves `.bak` as the only copy:
        // restore it when the target is missing; only delete it when the target exists.
        findChildFile(context, treeUri, parentDirUri, bakName)?.let { staleBak ->
            if (findChildFile(context, treeUri, parentDirUri, fileName) == null) {
                DocumentsContract.renameDocument(context.contentResolver, staleBak, fileName)
            } else {
                DocumentsContract.deleteDocument(context.contentResolver, staleBak)
            }
        }
        val tmpUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentDirUri,
            mimeType,
            tmpName,
        ) ?: return false
        val wrote = context.contentResolver.openOutputStream(tmpUri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } == true
        if (!wrote) {
            DocumentsContract.deleteDocument(context.contentResolver, tmpUri)
            return false
        }

        val existing = findChildFile(context, treeUri, parentDirUri, fileName)
        if (existing == null) {
            val renamed = DocumentsContract.renameDocument(context.contentResolver, tmpUri, fileName)
            if (renamed == null) {
                DocumentsContract.deleteDocument(context.contentResolver, tmpUri)
                return false
            }
            return true
        }

        // Keep prior bytes until the new name is in place (no delete-then-rename gap).
        val bakUri = DocumentsContract.renameDocument(context.contentResolver, existing, bakName)
        if (bakUri == null) {
            DocumentsContract.deleteDocument(context.contentResolver, tmpUri)
            return false
        }
        val finalUri = DocumentsContract.renameDocument(context.contentResolver, tmpUri, fileName)
        if (finalUri == null) {
            DocumentsContract.renameDocument(context.contentResolver, bakUri, fileName)
            return false
        }
        DocumentsContract.deleteDocument(context.contentResolver, bakUri)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/** Parent directory URI for a document under [treeUri], or null if not nested. */
fun parentDocumentUri(treeUri: Uri, documentUri: Uri): Uri? {
    val docId = DocumentsContract.getDocumentId(documentUri)
    val slash = docId.lastIndexOf('/')
    if (slash <= 0) return null
    val parentId = docId.substring(0, slash)
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
}

/** Resolve nested path segments without creating missing directories. */
fun findPath(context: Context, treeUri: Uri, vararg segments: String): Uri? {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    var parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    for (segment in segments) {
        parentUri = findChildDir(context, treeUri, parentUri, segment) ?: return null
    }
    return parentUri
}

fun atomicAppendLine(
    context: Context,
    treeUri: Uri,
    parentDirUri: Uri,
    fileName: String,
    line: String,
): Boolean {
    return try {
        val existing = findChildFile(context, treeUri, parentDirUri, fileName)
        val previous = if (existing != null) readFileContent(context, existing).orEmpty() else ""
        val next = if (previous.isEmpty() || previous.endsWith("\n")) {
            previous + line + "\n"
        } else {
            previous + "\n" + line + "\n"
        }
        atomicWriteText(context, treeUri, parentDirUri, fileName, next, "application/x-ndjson")
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun deleteDocument(context: Context, uri: Uri): Boolean {
    return try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun resolveRelativePath(context: Context, treeUri: Uri, relativePath: String): Uri? {
    val parts = relativePath.split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return null
    val fileName = parts.last()
    val dirs = parts.dropLast(1)
    var parent: Uri
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    for (dir in dirs) {
        parent = findChildDir(context, treeUri, parent, dir) ?: return null
    }
    return findChildFile(context, treeUri, parent, fileName)
}

fun getRotationAngle(context: Context, uri: Uri): Int {
    var rotationAngle = 0
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val exifInterface = ExifInterface(inputStream)
            val orientation = exifInterface.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            rotationAngle = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return rotationAngle
}

/** Decode image (incl. HEIC via system decoder when available), JPEG ≤2560px. */
fun processAndSaveImage(context: Context, srcUri: Uri, destUri: Uri): Boolean {
    return try {
        val rotationAngle = getRotationAngle(context, srcUri)

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(srcUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            // Fallback: copy raw bytes if decoder can't bound (rare)
            return copyRaw(context, srcUri, destUri)
        }

        val maxLongEdge = 2560
        var inSampleSize = 1
        val currentLongEdge = maxOf(srcWidth, srcHeight)
        if (currentLongEdge > maxLongEdge) {
            inSampleSize = Math.round(currentLongEdge.toFloat() / maxLongEdge.toFloat()).coerceAtLeast(1)
        }

        val fullOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val loadedBitmap = context.contentResolver.openInputStream(srcUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, fullOptions)
        } ?: return false

        val currentW = loadedBitmap.width
        val currentH = loadedBitmap.height
        val finalLongEdge = maxOf(currentW, currentH)
        val scale = if (finalLongEdge > maxLongEdge) {
            maxLongEdge.toFloat() / finalLongEdge.toFloat()
        } else {
            1.0f
        }

        val matrix = Matrix()
        if (scale < 1.0f) matrix.postScale(scale, scale)
        if (rotationAngle != 0) matrix.postRotate(rotationAngle.toFloat())

        val finalBitmap = Bitmap.createBitmap(loadedBitmap, 0, 0, currentW, currentH, matrix, true)
        if (finalBitmap != loadedBitmap) loadedBitmap.recycle()

        context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.flush()
        }
        finalBitmap.recycle()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun copyRaw(context: Context, srcUri: Uri, destUri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(srcUri)?.use { input ->
            context.contentResolver.openOutputStream(destUri)?.use { output ->
                input.copyTo(output)
            }
        } != null
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun listAllEntryJsonUris(context: Context, treeUri: Uri): List<Uri> {
    val byName = linkedMapOf<String, Uri>()
    val isEntryJson: (String) -> Boolean = {
        it.endsWith(".json") && !it.endsWith(".tmp") && !it.contains("sync-conflict")
    }

    fun collectFromRoot(entriesRoot: Uri, prefer: Boolean) {
        fun addAll(files: List<Uri>, names: List<String>) {
            for ((uri, name) in files.zip(names)) {
                if (prefer || name !in byName) {
                    byName[name] = uri
                }
            }
        }
        // Flat
        val flatNames = listChildFileNames(context, treeUri, entriesRoot, isEntryJson)
        val flatUris = listChildFiles(context, treeUri, entriesRoot, isEntryJson)
        if (flatNames.size == flatUris.size) {
            addAll(flatUris, flatNames)
        } else {
            for (uri in flatUris) {
                val name = DocumentsContract.getDocumentId(uri).substringAfterLast('/')
                if (prefer || name !in byName) byName[name] = uri
            }
        }
        for ((_, yearUri) in listChildDirs(context, treeUri, entriesRoot)) {
            for ((_, monthUri) in listChildDirs(context, treeUri, yearUri)) {
                val names = listChildFileNames(context, treeUri, monthUri, isEntryJson)
                val uris = listChildFiles(context, treeUri, monthUri, isEntryJson)
                if (names.size == uris.size) {
                    addAll(uris, names)
                } else {
                    for (uri in uris) {
                        val name = DocumentsContract.getDocumentId(uri).substringAfterLast('/')
                        if (prefer || name !in byName) byName[name] = uri
                    }
                }
            }
        }
    }

    // Prefer _capture/entries (shadows legacy same id)
    findPath(context, treeUri, "_capture", "entries")?.let { collectFromRoot(it, prefer = true) }
    findChildDirAtRoot(context, treeUri, "entries")?.let { collectFromRoot(it, prefer = false) }
    return byName.values.toList()
}

/** Find a top-level directory without creating it (read-only browse). */
fun findChildDirAtRoot(context: Context, treeUri: Uri, name: String): Uri? {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    return findChildDir(context, treeUri, rootUri, name)
}

/** Metadata for a markdown file discovered during a SAF walk (no body read). */
data class MarkdownFileMeta(
    val path: String,
    val name: String,
    val lastModifiedMs: Long? = null,
)

/**
 * Recursively walk `.md` files under [dirUri] without reading bodies.
 * [onFile] receives vault-relative path (using [prefix]), display name, optional mtime.
 */
fun walkMarkdownMetadata(
    context: Context,
    treeUri: Uri,
    dirUri: Uri,
    prefix: String,
    onFile: (MarkdownFileMeta) -> Unit,
) {
    val parentDocId = DocumentsContract.getDocumentId(dirUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val mime = cursor.getString(mimeIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkMarkdownMetadata(context, treeUri, childUri, "$prefix/$name", onFile)
                } else if (name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict")) {
                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        null
                    }
                    onFile(MarkdownFileMeta("$prefix/$name", name, modified))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Recursively walk markdown files under [dirUri].
 * [onFile] receives vault-relative path (using [prefix]), display name, and file content.
 */
fun walkMarkdownFiles(
    context: Context,
    treeUri: Uri,
    dirUri: Uri,
    prefix: String,
    onFile: (path: String, name: String, content: String) -> Unit,
) {
    val parentDocId = DocumentsContract.getDocumentId(dirUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val mime = cursor.getString(mimeIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkMarkdownFiles(context, treeUri, childUri, "$prefix/$name", onFile)
                } else if (name.endsWith(".md", ignoreCase = true) && !name.contains("sync-conflict")) {
                    val content = readFileContent(context, childUri) ?: continue
                    onFile("$prefix/$name", name, content)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
