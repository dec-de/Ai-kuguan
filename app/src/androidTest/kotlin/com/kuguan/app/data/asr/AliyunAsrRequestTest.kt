package com.kuguan.app.data.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AliyunAsrRequestTest {
    @Test fun runTaskOmitsHotwordsAndKeepsAudioParameters() {
        val client = AliyunAsrClient(
            wsUrl = "wss://example.invalid/asr",
            apiKey = "test-only",
            model = "test-model",
        )

        val request = JSONObject(client.runTask("test-task"))
        val payload = request.getJSONObject("payload")
        val parameters = payload.getJSONObject("parameters")

        assertEquals("run-task", request.getJSONObject("header").getString("action"))
        assertEquals("test-model", payload.getString("model"))
        assertEquals("pcm", parameters.getString("format"))
        assertEquals(16000, parameters.getInt("sample_rate"))
        assertEquals(300, parameters.getInt("max_sentence_silence"))
        assertFalse(parameters.has("vocabulary"))
    }
}
