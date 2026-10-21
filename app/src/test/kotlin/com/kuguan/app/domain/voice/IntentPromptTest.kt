package com.kuguan.app.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** prompt 组装的边界：上限、空值、去重。 */
class IntentPromptTest {

    private val template = """
        今天：{{TODAY}}
        物品：{{KNOWN_ITEMS}}
        操作：
        {{RECENT_OPS}}
        最新：{{LAST_SEQ}}
        库存：
        {{INVENTORY}}
    """.trimIndent()

    private fun op(seq: Long, name: String = "电池") = IntentPrompt.RecentOp(
        seq = seq,
        type = "PUT",
        itemName = name,
        locationCode = "A-07",
        quantity = 2.0,
    )

    @Test
    fun `三个占位符都被替换`() {
        val out = IntentPrompt.render(
            template,
            IntentPrompt.PromptContext("2026-10-18", listOf("七号电池"), listOf(op(101))),
        )
        assertTrue(out.contains("2026-10-18"))
        assertTrue(out.contains("七号电池"))
        assertTrue(out.contains("seq=101"))
        assertTrue(out.contains("最新：101"))
        assertFalse(out.contains("{{"))
    }

    @Test
    fun `空模板返回空串`() {
        assertEquals("", IntentPrompt.render("", IntentPrompt.PromptContext("2026-10-18", emptyList(), emptyList())))
        assertEquals("", IntentPrompt.render("   ", IntentPrompt.PromptContext("2026-10-18", emptyList(), emptyList())))
    }

    @Test
    fun `已知物品上限 30 且去重`() {
        val names = (1..40).map { "物品$it" } + listOf("物品1", "  ")
        val out = IntentPrompt.render(template, IntentPrompt.PromptContext("2026-10-18", names, emptyList()))
        assertTrue(out.contains("物品1、"))
        assertTrue(out.contains("物品30"))
        assertFalse(out.contains("物品31"))
    }

    @Test
    fun `没有已知物品时写空`() {
        val out = IntentPrompt.render(template, IntentPrompt.PromptContext("2026-10-18", emptyList(), emptyList()))
        assertTrue(out.contains("（空）"))
    }

    @Test
    fun `最近操作只取最后三条且按给定顺序`() {
        val ops = listOf(op(1), op(2), op(3), op(4), op(5))
        val out = IntentPrompt.render(template, IntentPrompt.PromptContext("2026-10-18", emptyList(), ops))
        assertFalse(out.contains("seq=1 "))
        assertFalse(out.contains("seq=2 "))
        assertTrue(out.contains("seq=3"))
        assertTrue(out.contains("seq=5"))
        assertTrue(out.contains("最新：5"))
    }

    @Test
    fun `没有操作时写没有且 LAST_SEQ 为 null`() {
        val out = IntentPrompt.render(template, IntentPrompt.PromptContext("2026-10-18", emptyList(), emptyList()))
        assertTrue(out.contains("（没有最近操作）"))
        assertTrue(out.contains("最新：null"))
    }

    @Test
    fun `操作摘要格式稳定`() {
        assertEquals(
            "seq=103 PUT 物品=五号电池 位置=A-07 数量=2",
            IntentPrompt.formatOp(
                IntentPrompt.RecentOp(103, "PUT", "五号电池", "A-07", 2.0)
            ),
        )
    }

    @Test
    fun `操作缺字段用短横线`() {
        assertEquals(
            "seq=7 TAKE 物品=- 位置=- 数量=-",
            IntentPrompt.formatOp(IntentPrompt.RecentOp(7, "TAKE", null, null, null)),
        )
    }

    @Test
    fun `完整库存不受已知物品三十条上限影响且安全转义`() {
        val inventory = (1..35).map { index ->
            IntentPrompt.InventoryItem("物品$index", emptyList(), null, emptyList())
        } + IntentPrompt.InventoryItem(
            "带\"引号的电池", listOf("备用电池"), "工具",
            listOf(IntentPrompt.InventoryLocation("A-07", 2.0, 1_760_000_000_000L)),
        )
        val out = IntentPrompt.render(template, IntentPrompt.PromptContext(
            "2026-10-19", emptyList(), emptyList(), inventory,
        ))
        assertTrue(out.contains("物品35"))
        assertTrue(out.contains("带\\\"引号的电池"))
        assertTrue(out.contains("备用电池"))
        assertTrue(out.contains("A-07"))
        assertTrue(out.contains("lastPutAtUtc"))
        assertFalse(out.contains("{{INVENTORY}}"))
    }
}
