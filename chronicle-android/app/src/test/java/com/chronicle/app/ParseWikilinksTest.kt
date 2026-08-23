package com.chronicle.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseWikilinksTest {
    @Test
    fun parseSimpleAndAliasedWikilinks() {
        val text = "See [[foo]] and [[bar|Label]] and [[path/Note]]."
        assertEquals(listOf("foo", "bar", "path/Note"), parseWikilinks(text))
    }

    @Test
    fun regexCompilesOnJvm() {
        // Guards against ICU-hostile nested character-class syntax that crashes Android.
        assertEquals(emptyList<String>(), parseWikilinks("no links here"))
    }
}
