package com.kuguan.app.data.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeechDiagnosticsDeviceTest {
    @Test
    fun `应用私有文件保存完整原文与同一会话编号`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "speech-diag-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val logger = FileSpeechDiagnostics(dir)
            val raw = "A01 两个笔记本电脑、两个哑铃片 😀".repeat(100)
            logger.record("test-session", "ASR_RESULT", raw)
            logger.record("test-session", "LLM_FALLBACK_RAW", """{"items":[]}""")

            val lines = File(dir, "speech-diagnostics.jsonl").readLines()
            assertEquals(2, lines.size)
            val first = Json.parseToJsonElement(lines[0]).jsonObject
            val second = Json.parseToJsonElement(lines[1]).jsonObject
            assertEquals(raw, first.getValue("content").jsonPrimitive.content)
            assertEquals("test-session", first.getValue("sessionId").jsonPrimitive.content)
            assertEquals("LLM_FALLBACK_RAW", second.getValue("stage").jsonPrimitive.content)
            assertTrue(second.getValue("content").jsonPrimitive.content.contains("items"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
