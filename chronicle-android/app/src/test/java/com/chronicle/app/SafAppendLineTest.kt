package com.chronicle.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * curation/ops/phone.jsonl is append-only (CONTRACT). Appending is a
 * read-modify-write over SAF, so a read that fails on a file that EXISTS used
 * to be treated as an empty file — and the rewrite replaced the device's whole
 * curation history with one line, which then synced to every peer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafAppendLineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A tree whose ops dir lists phone.jsonl but cannot open it; records every mutation. */
    class UnreadableLogProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val cursor = MatrixCursor(cols)
            cursor.addRow(
                cols.map { col ->
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "$OPS_DOC_ID/$LOG_NAME"
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> LOG_NAME
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/x-ndjson"
                        else -> null
                    }
                }.toTypedArray(),
            )
            return cursor
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode.contains('w')) mutations.add("open-write $uri")
            throw FileNotFoundException("transient provider failure for $uri")
        }

        // createDocument / renameDocument / deleteDocument all arrive here.
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            mutations.add(method)
            return null
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null.also { mutations.add("insert") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            0.also { mutations.add("delete") }
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0.also { mutations.add("update") }

        companion object {
            val mutations = mutableListOf<String>()
        }
    }

    @Before
    fun setUp() {
        clearSafChildCache()
        UnreadableLogProvider.mutations.clear()
        Robolectric.setupContentProvider(UnreadableLogProvider::class.java, AUTHORITY)
    }

    @Test
    fun appendAbortsAndLeavesTheLogUntouched_whenTheExistingFileCannotBeRead() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC_ID)
        val opsDir = DocumentsContract.buildDocumentUriUsingTree(treeUri, OPS_DOC_ID)

        val ok = atomicAppendLine(context, treeUri, opsDir, LOG_NAME, """{"op":"pin","id":"n1"}""")

        assertFalse("append reported success over an unreadable log", ok)
        assertEquals(
            "append touched an existing log it could not read",
            emptyList<String>(),
            UnreadableLogProvider.mutations,
        )
    }

    @Test(expected = java.io.IOException::class)
    fun strictRead_reportsUnreadableInsteadOfMissing() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC_ID)
        val log = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$OPS_DOC_ID/$LOG_NAME")
        readFileContentOrThrow(context, log)
    }

    private companion object {
        const val AUTHORITY = "com.chronicle.test.unreadable"
        const val ROOT_DOC_ID = "root"
        const val OPS_DOC_ID = "root/curation/ops"
        const val LOG_NAME = "phone.jsonl"
    }
}
