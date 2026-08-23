package com.chronicle.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServeClientHealthTest {

    @Test
    fun parseHealthOk_requiresJsonOkTrue() {
        assertTrue(ServeClient.parseHealthOk("""{"ok":true}"""))
        assertTrue(ServeClient.parseHealthOk("""{"ok":true,"chronicle":{"ok":true}}"""))
        assertFalse(ServeClient.parseHealthOk("""{"ok":false}"""))
        assertFalse(ServeClient.parseHealthOk("""{"ok":false,"error":"down"}"""))
        assertFalse(ServeClient.parseHealthOk("""{"chronicle":{"ok":true}}"""))
        assertFalse(ServeClient.parseHealthOk("""true"""))
        assertFalse(ServeClient.parseHealthOk("""<html>ok true</html>"""))
        assertFalse(ServeClient.parseHealthOk(""))
        assertFalse(ServeClient.parseHealthOk("not-json"))
    }
}
