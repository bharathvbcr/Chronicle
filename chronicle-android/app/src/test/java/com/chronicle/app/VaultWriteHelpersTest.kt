package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure helpers around vault write paths (no DocumentsContract rename needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultWriteHelpersTest {
    @Test
    fun parentDocumentUri_stripsLastSegment() {
        val treeUri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AChronicle",
        )
        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "primary:Chronicle/entries/2026/07/id.json",
        )
        val parent = parentDocumentUri(treeUri, fileUri)
        assertEquals(
            "primary:Chronicle/entries/2026/07",
            android.provider.DocumentsContract.getDocumentId(parent),
        )
    }

    @Test
    fun parentDocumentUri_rootHasNoParent() {
        val treeUri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AChronicle",
        )
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "primary:Chronicle",
        )
        assertNull(parentDocumentUri(treeUri, root))
    }

    @Test
    fun syncConflictFilter_predicate() {
        val keep: (String) -> Boolean = {
            it.endsWith(".json") && !it.endsWith(".tmp") && !it.contains("sync-conflict")
        }
        assertTrue(keep("2026-07-11_120000-an.json"))
        assertFalse(keep("2026-07-11_120000-an.json.tmp"))
        assertFalse(keep("2026-07-11_120000-an.sync-conflict-20260711.json"))
        assertFalse(keep("note.sync-conflict-1.json"))
    }
}
