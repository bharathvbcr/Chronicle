package com.chronicle.app

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UriPermissionMatchTest {

    @Test
    fun matchesExactUri() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AChronicle")
        assertTrue(uriPermissionMatches(uri, uri))
    }

    @Test
    fun matchesSameTreeDocumentIdWithDifferentEncoding() {
        val a = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AChronicle")
        val b = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AChronicle")
        assertTrue(uriPermissionMatches(a, b))
    }

    @Test
    fun rejectsDifferentTrees() {
        val a = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AChronicle")
        val b = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AOther")
        assertFalse(uriPermissionMatches(a, b))
    }
}
