package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure dual-read path candidates (PC vault_paths.resolve_media_abs parity). */
class MediaDualReadCandidatesTest {
    @Test
    fun attachmentsFallsBackToImgAndAudio() {
        assertEquals(
            listOf(
                "_attachments/2026/07/photo.jpg",
                "img/2026/07/photo.jpg",
            ),
            mediaDualReadCandidates("_attachments/2026/07/photo.jpg"),
        )
        assertEquals(
            listOf(
                "_attachments/2026/07/note.m4a",
                "img/2026/07/note.m4a",
                "audio/2026/07/note.m4a",
            ),
            mediaDualReadCandidates("_attachments/2026/07/note.m4a"),
        )
    }

    @Test
    fun legacyImgAudioFallsBackToAttachments() {
        assertEquals(
            listOf(
                "img/2026/07/photo.jpg",
                "_attachments/2026/07/photo.jpg",
            ),
            mediaDualReadCandidates("img/2026/07/photo.jpg"),
        )
        assertEquals(
            listOf(
                "audio/2026/07/note.m4a",
                "_attachments/2026/07/note.m4a",
            ),
            mediaDualReadCandidates("audio/2026/07/note.m4a"),
        )
    }

    @Test
    fun rejectsTraversalAndNull() {
        assertTrue(mediaDualReadCandidates("../etc/passwd").isEmpty())
        assertTrue(mediaDualReadCandidates("img/../secret.jpg").isEmpty())
        assertTrue(mediaDualReadCandidates("").isEmpty())
    }

    @Test
    fun normalizesSlashes() {
        assertEquals(
            listOf(
                "img/2026/07/photo.jpg",
                "_attachments/2026/07/photo.jpg",
            ),
            mediaDualReadCandidates("/img/2026/07/photo.jpg"),
        )
    }
}
