package com.kuguan.app.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纠错 / 删除的二次校验。
 *
 * 这些用例锁的是「模型指错一条时，App 必须拒绝而不是照做」——
 * 每一条失败路径都对应一种会让库里的数据凭空少掉一行的事故。
 */
class IntentGuardsTest {

    private val op103 = OperationSnapshot(
        seq = 103,
        type = "PUT",
        itemName = "五号电池",
        locationCode = "A-07",
        quantity = 2.0,
        revoked = false,
    )

    private fun correct(
        seq: Long? = 103,
        name: String? = "五号电池",
        code: String? = "A-07",
        qty: Double? = 2.0,
        raw: String = "刚才那个改成两个",
    ) = IntentPlan(
        kind = IntentKind.CORRECT,
        rows = listOf(PlannedRow(name, qty, code)),
        targetSeq = seq,
        note = null,
        source = IntentSource.LLM,
    )

    private fun delete(seq: Long? = 103, claimed: String? = null) = IntentPlan(
        kind = IntentKind.DELETE,
        rows = if (claimed == null) emptyList() else listOf(PlannedRow(claimed, null, null)),
        targetSeq = seq,
        note = null,
        source = IntentSource.LLM,
    )

    @Test
    fun `改数量通过`() {
        assertNull(IntentGuards.checkCorrect(correct(qty = 3.0), op103, "刚才那个数量改成三个"))
    }

    @Test
    fun `改位置通过`() {
        assertNull(IntentGuards.checkCorrect(correct(code = "B-07"), op103, "不是A07，是B07"))
    }

    @Test
    fun `改名通过（原话提到了旧名字）`() {
        assertNull(
            IntentGuards.checkCorrect(
                correct(name = "七号电池"),
                op103,
                "不是五号电池，是七号电池",
            )
        )
    }

    @Test
    fun `改名被拒（原话没提旧名字，说明模型指错记录）`() {
        val reason = IntentGuards.checkCorrect(correct(name = "七号电池"), op103, "改成七号电池")
        assertNotNull(reason)
        assertTrue(reason!!.contains("对不上"))
    }

    @Test
    fun `缺 targetSeq 被拒`() {
        assertNotNull(IntentGuards.checkCorrect(correct(seq = null), op103, "改一下"))
    }

    @Test
    fun `目标不存在被拒`() {
        val reason = IntentGuards.checkCorrect(correct(), null, "改一下")
        assertNotNull(reason)
        assertTrue(reason!!.contains("找不到"))
    }

    @Test
    fun `目标已撤销被拒`() {
        val reason = IntentGuards.checkCorrect(correct(), op103.copy(revoked = true), "改一下")
        assertNotNull(reason)
        assertTrue(reason!!.contains("已经撤销"))
    }

    @Test
    fun `自定义编号可用于纠错`() {
        val reason = IntentGuards.checkCorrect(correct(code = "D-00"), op103, "改到D号")
        assertNull(reason)
    }

    @Test
    fun `编号缺失被拒`() {
        assertNotNull(IntentGuards.checkCorrect(correct(code = null), op103, "改一下"))
    }

    @Test
    fun `数量缺失被拒`() {
        val reason = IntentGuards.checkCorrect(correct(qty = null), op103, "改一下")
        assertNotNull(reason)
        assertTrue(reason!!.contains("数量"))
    }

    @Test
    fun `数量为 0 被拒`() {
        assertNotNull(IntentGuards.checkCorrect(correct(qty = 0.0), op103, "改成零个"))
    }

    @Test
    fun `数量超过上限被拒`() {
        assertNotNull(IntentGuards.checkCorrect(correct(qty = 1000.0), op103, "改成一万个"))
    }

    @Test
    fun `物品名缺失被拒`() {
        assertNotNull(IntentGuards.checkCorrect(correct(name = null), op103, "改一下"))
    }

    @Test
    fun `删除通过`() {
        assertNull(IntentGuards.checkDelete(delete(), op103))
    }

    @Test
    fun `删除缺 seq 被拒`() {
        val reason = IntentGuards.checkDelete(delete(seq = null), op103)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不知道"))
    }

    @Test
    fun `删除已撤销的记录被拒`() {
        assertNotNull(IntentGuards.checkDelete(delete(), op103.copy(revoked = true)))
    }

    @Test
    fun `删除目标不存在被拒`() {
        assertNotNull(IntentGuards.checkDelete(delete(), null))
    }

    @Test
    fun `删除时声称的物品对不上被拒`() {
        val reason = IntentGuards.checkDelete(delete(claimed = "登山杖"), op103)
        assertNotNull(reason)
        assertTrue(reason!!.contains("对不上"))
    }

    @Test
    fun `删除时声称的物品一致则通过`() {
        assertNull(IntentGuards.checkDelete(delete(claimed = "5号电池"), op103))
    }

    @Test
    fun `物品名等价忽略数字化形态`() {
        assertTrue(IntentGuards.sameItemName("7号电池", "七号电池"))
        assertTrue(IntentGuards.sameItemName("五号电池", "5号电池"))
        assertFalse(IntentGuards.sameItemName("螺丝刀", "螺丝"))
        assertFalse(IntentGuards.sameItemName(null, "螺丝刀"))
    }

    @Test
    fun `原话提及判定也忽略数字化形态`() {
        assertTrue(IntentGuards.mentions("不是五号电池，是七号电池", "5号电池"))
        assertTrue(IntentGuards.mentions("不是A07，是B07", "A07"))
        assertFalse(IntentGuards.mentions("改成七号电池", "五号电池"))
    }

    @Test
    fun `数量显示整数不带小数点`() {
        assertEquals("2", IntentGuards.formatQuantity(2.0))
        assertEquals("2.5", IntentGuards.formatQuantity(2.5))
    }

    @Test
    fun `上一条的指代判定`() {
        assertTrue(IntentGuards.refersToLatest("删掉上一条"))
        assertTrue(IntentGuards.refersToLatest("最后一条不算"))
        assertTrue(IntentGuards.refersToLatest("撤销刚才那个"))
        assertFalse(IntentGuards.refersToLatest("删掉登山杖那条"))
    }

    @Test
    fun `数量上限与数据层常量一致`() {
        assertEquals(com.kuguan.app.data.repo.MAX_QUANTITY, IntentGuards.MAX_QUANTITY, 0.0)
    }
}
