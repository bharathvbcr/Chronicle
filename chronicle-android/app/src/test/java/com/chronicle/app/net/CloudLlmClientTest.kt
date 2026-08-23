package com.chronicle.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLlmClientTest {

    @Test
    fun grokAllowlist_acceptsApiXAiHttps() {
        assertTrue(CloudLlmClient.isAllowedGrokUrl("https://api.x.ai/v1"))
        assertTrue(CloudLlmClient.isAllowedGrokUrl("https://api.x.ai/v1/"))
        assertTrue(CloudLlmClient.isAllowedGrokUrl(CloudLlmClient.DEFAULT_GROK_BASE))
    }

    @Test
    fun grokAllowlist_rejectsOtherHostsAndHttp() {
        assertFalse(CloudLlmClient.isAllowedGrokUrl("https://evil.example/v1"))
        assertFalse(CloudLlmClient.isAllowedGrokUrl("http://api.x.ai/v1"))
        assertFalse(CloudLlmClient.isAllowedGrokUrl("https://api.x.ai.evil.com/v1"))
        assertFalse(CloudLlmClient.isAllowedGrokUrl(""))
    }

    @Test
    fun normalizeHttpsBase_addsScheme() {
        assertEquals(
            "https://api.x.ai/v1",
            CloudLlmClient.normalizeHttpsBase("api.x.ai/v1"),
        )
        assertEquals(
            "https://api.x.ai/v1",
            CloudLlmClient.normalizeHttpsBase("https://api.x.ai/v1/"),
        )
    }

    @Test
    fun ollamaPrivateHostGate() {
        assertTrue(CloudLlmClient.isPrivateOrLoopbackUrl("http://192.168.1.10:11434"))
        assertTrue(CloudLlmClient.isPrivateOrLoopbackUrl("http://10.0.0.5:11434"))
        assertTrue(CloudLlmClient.isPrivateOrLoopbackUrl("http://127.0.0.1:11434"))
        assertFalse(CloudLlmClient.isPrivateOrLoopbackUrl("http://8.8.8.8:11434"))
        assertFalse(CloudLlmClient.isPrivateOrLoopbackUrl("https://api.x.ai/v1"))
    }
}
