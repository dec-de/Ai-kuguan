package com.kuguan.app.domain.confirm

import com.kuguan.app.domain.normalize.LocationCodeNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认闸门的单测 —— 这是「零确认」不变成「乱确认」的最后一道防线。
 *
 * 核心断言只有一句话：**歧义时绝不能放行写库。**
 */
class ConfirmationGateTest {

    private fun evaluate(raw: String) =
        ConfirmationGate.evaluate(LocationCodeNormalizer.parse(raw))

    @Test
    fun `两位读法直接放行`() {
        val decision = evaluate("A 洞拐 里放了两节七号电池")
        assertTrue(decision is ConfirmationGate.Decision.Proceed)
        assertEquals(listOf("A-07"), (decision as ConfirmationGate.Decision.Proceed).codes)
    }

    @Test
    fun `三位前导零完整放行`() {
        val decision = evaluate("A 洞零拐 里放了两节七号电池")
        assertEquals(listOf("A007"), (decision as ConfirmationGate.Decision.Proceed).codes)
    }

    @Test
    fun `纯查询句没有编号`() {
        assertTrue(evaluate("登山杖 在哪") is ConfirmationGate.Decision.NoCodeFound)
    }

    @Test
    fun `不同位数的两个编号一起放行`() {
        // 「A 洞零拐 B 洞拐 两格」→ A 歧义、B 确定。
        // 只写 B 会留下半条记录，比全错更难排查，所以整体拦截。
        val decision = evaluate("A 洞零拐 B 洞拐 两格 都放了 电池")
        assertEquals(listOf("A007", "B-07"), (decision as ConfirmationGate.Decision.Proceed).codes)
    }

    @Test
    fun `无歧义的多编号可以一次写全`() {
        val decision = evaluate("A 零拐 B 洞拐 两格 都放了 电池")
        assertTrue(decision is ConfirmationGate.Decision.Proceed)
        assertEquals(listOf("A-07", "B-07"), (decision as ConfirmationGate.Decision.Proceed).codes)
    }

    @Test
    fun `用户选择合法候选可放行`() {
        assertEquals("A-07", ConfirmationGate.resolveUserChoice("A-07"))
        assertEquals("A-007", ConfirmationGate.resolveUserChoice("A-007"))
    }

    @Test
    fun `用户选择被拒的脏数据`() {
        // UI 不该传出这种值，但闸门要挡住 —— 防线不能假设调用方永远正确
        assertEquals("抽屉7", ConfirmationGate.resolveUserChoice("抽屉7"))
        assertEquals("A-1234", ConfirmationGate.resolveUserChoice("A-1234"))
        assertEquals("A-00", ConfirmationGate.resolveUserChoice("A-00"))
        assertNull(ConfirmationGate.resolveUserChoice(""))
    }
}
