package com.kuguan.app.data.settings

import com.kuguan.app.data.llm.DeepSeekLlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceServiceConfigTest {
    @Test fun `blank optional route is valid`() {
        val config = VoiceServiceConfig(asrKey = "fake-asr", primaryKey = "fake-primary")
        config.validate()
        assertFalse(config.fallbackConfigured)
    }

    @Test fun `partial optional route names its missing fields`() {
        val config = VoiceServiceConfig(fallbackUrl = "https://api.deepseek.com")
        val message = runCatching { config.validate() }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("模型"))
        assertTrue(message.contains("Key"))
        assertFalse(message.contains("地址、"))
    }

    @Test fun `complete optional route is enabled`() {
        val config = VoiceServiceConfig(fallbackUrl = "https://api.deepseek.com",
            fallbackModel = "deepseek-chat", fallbackKey = "fake-key")
        config.validate()
        assertTrue(config.fallbackConfigured)
    }

    @Test fun `credential endpoint accepts only clean https URL`() {
        assertTrue(DeepSeekLlmClient.validBaseUrl("https://api.deepseek.com"))
        assertFalse(DeepSeekLlmClient.validBaseUrl("http://api.deepseek.com"))
        assertFalse(DeepSeekLlmClient.validBaseUrl("https://" + "person:secret@api.deepseek.com"))
        assertFalse(DeepSeekLlmClient.validBaseUrl("https://api.deepseek.com/?next=other"))
    }

    @Test fun `HTTP failures never contain remote body`() {
        assertEquals("LLM Key 无效或已过期（401）", DeepSeekLlmClient.httpFailure(401))
        assertTrue(DeepSeekLlmClient.httpFailure(302).contains("重定向"))
        assertTrue(DeepSeekLlmClient.httpFailure(429).contains("额度"))
    }
}
