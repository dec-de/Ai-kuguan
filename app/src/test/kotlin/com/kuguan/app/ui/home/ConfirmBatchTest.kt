package com.kuguan.app.ui.home

import com.kuguan.app.ui.theme.SemanticAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核对屏的判定规则（纯 Kotlin，不涉及 Compose 渲染）。
 *
 * 这些规则一旦被后续改动带偏，症状是「用户看错动作 / 没注意到存疑字段然后存错数据」，
 * 属于最难在 review 里发现的一类回归，所以锁在这里。
 *
 * 2026-10-18 改版后，模型只剩两件事：
 * - **哪些字段转红**（[ConfirmRow.isFlagged]）—— 与「写没写库」完全无关
 * - **有几条已写库 / 待填 / 刚改过**（批尾那句话的数据来源）
 * 撤销、候选按钮、批级歧义这些状态全部删除，对应用例一并删除。
 */
class ConfirmBatchTest {

    private fun putRow(
        key: String = "r1",
        fields: Set<Field> = emptySet(),
        quantity: Double? = 1.0,
        seq: Long? = null,
        corrected: Boolean = false,
    ) = ConfirmRow(
        key = key,
        action = ConfirmAction.PUT,
        locationCode = "A-07",
        itemName = "七号电池",
        quantity = quantity,
        uncertainFields = fields,
        committedSeq = seq,
        corrected = corrected,
    )

    private fun batchOf(vararg rows: ConfirmRow) = ConfirmBatch(rows = rows.toList())

    // ---------- 绿 = 放入 / 蓝 = 取出 ----------

    @Test
    fun `无歧义的放入是绿色`() {
        assertEquals(SemanticAction.Put, putRow().semanticAction)
    }

    @Test
    fun `无歧义的取出是蓝色`() {
        assertEquals(SemanticAction.Take, putRow().copy(action = ConfirmAction.TAKE).semanticAction)
    }

    // ---------- 红 = 这一行有字段要核实（覆盖全部四个字段）----------

    @Test
    fun `编号存疑时转红`() {
        assertEquals(SemanticAction.NeedsConfirm, putRow(fields = setOf(Field.Location)).semanticAction)
    }

    @Test
    fun `数量存疑时转红`() {
        assertEquals(
            SemanticAction.NeedsConfirm,
            putRow(fields = setOf(Field.Quantity), quantity = null).semanticAction,
        )
    }

    @Test
    fun `物品存疑时转红`() {
        assertEquals(SemanticAction.NeedsConfirm, putRow(fields = setOf(Field.Item)).semanticAction)
    }

    @Test
    fun `动作存疑时转红`() {
        assertEquals(SemanticAction.NeedsConfirm, putRow(fields = setOf(Field.Action)).semanticAction)
    }

    @Test
    fun `多个字段同时存疑时红色优先于动作色`() {
        // 关键：不能因为「用户说了放入」就显示绿色 —— 那会给「已确认」的错觉
        assertEquals(
            SemanticAction.NeedsConfirm,
            putRow(fields = setOf(Field.Location, Field.Quantity), quantity = null).semanticAction,
        )
    }

    // ---------- 转红是**逐字段**的，不是整行一个开关 ----------

    @Test
    fun `存疑只标红对应的那一格`() {
        val row = putRow(fields = setOf(Field.Quantity), quantity = null)
        assertTrue(row.isFlagged(Field.Quantity))
        // 编号和物品名模型是确定的 —— 它们的框必须是正常色，
        // 否则用户会去反复确认本来没错的东西
        assertFalse(row.isFlagged(Field.Location))
        assertFalse(row.isFlagged(Field.Item))
    }

    @Test
    fun `没有存疑字段时任何一格都不转红`() {
        val row = putRow()
        assertFalse(row.needsConfirmation)
        Field.entries.forEach { assertFalse(row.isFlagged(it)) }
    }

    // ---------- 写没写库，与转不转红无关 ----------

    @Test
    fun `已写库但存疑的行仍然是红色`() {
        // 零确认下这一行**已经进库了**，红色说的是「我没把握，你多看一眼」，
        // 不是「没记上」。两者分离正是这一版的核心。
        val row = putRow(fields = setOf(Field.Location), seq = 1L)
        assertTrue(row.committed)
        assertEquals(SemanticAction.NeedsConfirm, row.semanticAction)
    }

    @Test
    fun `存疑的行可以还没写库`() {
        // 数量听不出来时写不了库，此时它同时是「红」和「未提交」的
        val row = putRow(fields = setOf(Field.Quantity), quantity = null)
        assertFalse(row.committed)
        assertEquals(SemanticAction.NeedsConfirm, row.semanticAction)
    }

    // ---------- 批尾那句状态文案的数据来源 ----------

    @Test
    fun `条数按提交状态分类统计`() {
        val batch = batchOf(
            putRow(seq = 1L),
            putRow(key = "r2", seq = 2L, corrected = true),
            putRow(key = "r3", fields = setOf(Field.Quantity), quantity = null),
        )
        assertEquals(2, batch.committedCount)
        assertEquals(1, batch.pendingCount)
        assertEquals(1, batch.correctedCount)
    }

    @Test
    fun `批头条数就是行数`() {
        val batch = batchOf(
            putRow(seq = 1L),
            putRow(key = "r2", seq = 2L).copy(itemName = "五号电池"),
        )
        assertEquals("放入 · 2 项", batch.headerTitle())
    }

    @Test
    fun `动作不一致时批头不写放入或取出`() {
        val batch = batchOf(
            putRow(seq = 1L),
            putRow(key = "r2", seq = 2L).copy(action = ConfirmAction.TAKE),
        )
        assertNull(batch.uniformAction)
        assertEquals("共 2 项", batch.headerTitle())
    }

    // ---------- 批头配色：只反映「整批都没写库」这件事 ----------

    @Test
    fun `行级存疑时批头不转红`() {
        // 批量场景最关键的一条：8 行里 1 行存疑，其余 7 行**已经写进库了**。
        // 批头转红等于告诉用户「这 8 条都没记上」—— 比原来的错更糟。
        val batch = batchOf(
            putRow(seq = 1L),
            putRow(key = "r2", seq = 2L),
            putRow(key = "r3", fields = setOf(Field.Quantity), quantity = null),
        )
        assertEquals(SemanticAction.Put, batch.headerAction)
        assertEquals(SemanticAction.NeedsConfirm, batch.rows[2].semanticAction)
    }

    @Test
    fun `整批都没写库且有存疑时批头转红`() {
        val batch = batchOf(
            putRow(fields = setOf(Field.Location)),
            putRow(key = "r2", fields = setOf(Field.Location)),
        )
        assertEquals(SemanticAction.NeedsConfirm, batch.headerAction)
        assertEquals(0, batch.committedCount)
    }

    @Test
    fun `一行都没提交但也没存疑时批头仍是动作色`() {
        // 例如写库被闸门拒了、但模型有把握 —— 红框会说谎，由 notice 说明原因
        val batch = batchOf(putRow(quantity = 2.0), putRow(key = "r2"))
        assertEquals(SemanticAction.Put, batch.headerAction)
        assertEquals(2, batch.pendingCount)
    }

    // ---------- 「取消这次」只在纯丢弃时出现 ----------

    @Test
    fun `一条都没写库才能取消`() {
        assertTrue(batchOf(putRow(), putRow(key = "r2")).canDiscard)
    }

    @Test
    fun `已经写进库就不能取消`() {
        // 取消是丢弃，但库里那条还在 —— 混用会让人以为「取消」也能把数据拿掉
        assertFalse(batchOf(putRow(seq = 1L)).canDiscard)
    }

    @Test
    fun `整句没拆出条目时是可取消的空批次`() {
        val batch = ConfirmBatch(rows = emptyList(), unparsed = true)
        assertEquals(SemanticAction.NeedsConfirm, batch.headerAction)
        assertTrue(batch.canDiscard)
    }
}
