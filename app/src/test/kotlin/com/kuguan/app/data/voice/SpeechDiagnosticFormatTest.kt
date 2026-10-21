package com.kuguan.app.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDiagnosticFormatTest {
    @Test
    fun `长中文与特殊字符完整保存并可分段还原`() {
        val raw = "A01 放了\n笔记本电脑 \"测试\" 😀 哑铃片。".repeat(150)
        val line = SpeechDiagnosticFormat.line(1234, "session-1", "LLM_FALLBACK_RAW", raw)
        val parsed = Json.parseToJsonElement(line).jsonObject
        assertEquals("session-1", parsed.getValue("sessionId").jsonPrimitive.content)
        assertEquals("LLM_FALLBACK_RAW", parsed.getValue("stage").jsonPrimitive.content)
        assertEquals(raw, parsed.getValue("content").jsonPrimitive.content)
        assertFalse(line.contains('\n'))

        val chunks = SpeechDiagnosticFormat.chunks(line)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 700 })
        assertEquals(line, chunks.joinToString(""))
        assertTrue(chunks.dropLast(1).all { !Character.isHighSurrogate(it.last()) })
    }
}
