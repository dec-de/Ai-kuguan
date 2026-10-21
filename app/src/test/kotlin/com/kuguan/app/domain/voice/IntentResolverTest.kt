package com.kuguan.app.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentResolverTest {
    @Test fun `模型结果原样映射并保留 USB延长线完整名称`() {
        val result = IntentResolver.resolve(IntentJson.LlmIntent(
            IntentKind.PUT,
            listOf(PlannedRow("USB延长线", 2.0, "A113")),
            null,
            null,
        ))
        assertEquals(IntentSource.LLM, result.source)
        assertEquals("USB延长线", result.rows.single().itemName)
        assertEquals("A113", result.rows.single().locationCode)
    }

    @Test fun `模型漏库位时不从原话补位`() {
        val result = IntentResolver.resolve(IntentJson.LlmIntent(
            IntentKind.PUT,
            listOf(PlannedRow("USB延长线", 1.0, null)),
            null,
            null,
        ))
        assertNull(result.rows.single().locationCode)
    }

    @Test fun `无效模型结果没有待写入行`() {
        val results = listOf(
            IntentResolver.resolve(null),
            IntentResolver.resolve(IntentJson.LlmIntent(IntentKind.UNKNOWN, emptyList(), null, "没听清")),
            IntentResolver.resolve(IntentJson.LlmIntent(IntentKind.PUT, emptyList(), null, null)),
            IntentResolver.resolve(IntentJson.LlmIntent(IntentKind.TAKE, emptyList(), null, null)),
        )
        assertTrue(results.all { it.kind == IntentKind.UNKNOWN && it.rows.isEmpty() })
        assertTrue(results.all { it.source == IntentSource.UNRESOLVED })
    }

    @Test fun `纠错目标序号由模型原样透传`() {
        val result = IntentResolver.resolve(IntentJson.LlmIntent(
            IntentKind.CORRECT,
            listOf(PlannedRow("五号电池", 3.0, "A-07")),
            103,
            null,
        ))
        assertEquals(103L, result.targetSeq)
        assertEquals(IntentKind.CORRECT, result.kind)
    }
}
