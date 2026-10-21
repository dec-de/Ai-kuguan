package com.kuguan.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数量输入框的校验规则。
 *
 * 这批用例锁死的是**能不能写进库**，属于安全边界：
 * 一旦规则被放宽，「0 个电池」或「1.5 个电池」就会进入数据库，
 * 而「取出」逻辑（remaining <= 0 即软删）会因此做出错误判断。
 */
class QuantityInputRuleTest {

    // ---------- sanitize：输入清洗 ----------

    @Test
    fun `剔除非数字字符`() {
        assertEquals("12", QuantityInputRule.sanitize("1a2"))
        assertEquals("12", QuantityInputRule.sanitize("1.2")) // 小数点被滤掉
        assertEquals("12", QuantityInputRule.sanitize("a1b2c"))
    }

    @Test
    fun `全角数字转半角后才能解析`() {
        // 回归用例：Char.isDigit() 对全角「１」返回 true，
        // 所以只做 filter 是不够的 —— 会留下全角字符让 toIntOrNull() 解析失败，
        // 用户表现为「输了数字但确认按钮不出现」。用户用全角键盘就会踩到。
        assertEquals("12", QuantityInputRule.sanitize("１２"))
        assertEquals("12", QuantityInputRule.sanitize("１2")) // 全角1 + 半角2 = 两个数字
        assertEquals("12", QuantityInputRule.sanitize("１２３")) // 三个数字限长两位
        assertTrue(QuantityInputRule.isConfirmable("１２"))
        assertEquals(12.0, QuantityInputRule.parse("１２")!!, 0.0)
    }

    @Test
    fun `限长两位`() {
        assertEquals("99", QuantityInputRule.sanitize("999"))
        assertEquals("12", QuantityInputRule.sanitize("1234"))
    }

    @Test
    fun `负号和小数点不会通过清洗`() {
        // 负数不会被误当成合法数量 —— '-' 和 '.' 在清洗阶段就被丢掉
        assertEquals("5", QuantityInputRule.sanitize("-5"))
        assertEquals("5", QuantityInputRule.sanitize(".5"))
    }

    @Test
    fun `空串清洗后仍为空`() {
        assertEquals("", QuantityInputRule.sanitize(""))
        assertEquals("", QuantityInputRule.sanitize("abc"))
    }

    // ---------- isConfirmable：能不能确认 ----------

    @Test
    fun `一位数和两位数都合法`() {
        assertTrue(QuantityInputRule.isConfirmable("1"))
        assertTrue(QuantityInputRule.isConfirmable("9"))
        assertTrue(QuantityInputRule.isConfirmable("10"))
        assertTrue(QuantityInputRule.isConfirmable("99"))
    }

    @Test
    fun `空输入不能确认`() {
        // 这是最重要的一条：「空着也能提交」绝不允许
        assertFalse(QuantityInputRule.isConfirmable(""))
    }

    @Test
    fun `零不能确认`() {
        assertFalse(QuantityInputRule.isConfirmable("0"))
        assertFalse(QuantityInputRule.isConfirmable("00"))
    }

    @Test
    fun `超出两位不能确认`() {
        assertFalse(QuantityInputRule.isConfirmable("100"))
        assertFalse(QuantityInputRule.isConfirmable("999"))
    }

    @Test
    fun `非数字不能确认`() {
        assertFalse(QuantityInputRule.isConfirmable("abc"))
        assertFalse(QuantityInputRule.isConfirmable("1.5"))
        assertFalse(QuantityInputRule.isConfirmable("-1"))
    }

    // ---------- parse：写库入口 ----------

    @Test
    fun `合法输入解析为数量`() {
        assertEquals(1.0, QuantityInputRule.parse("1")!!, 0.0)
        assertEquals(2.0, QuantityInputRule.parse("2")!!, 0.0)
        assertEquals(99.0, QuantityInputRule.parse("99")!!, 0.0)
    }

    @Test
    fun `非法输入一律返回 null`() {
        // parse 是写库入口的最后一道闸，返回 null 就等于「不写库」
        assertNull(QuantityInputRule.parse(""))
        assertNull(QuantityInputRule.parse("0"))
        assertNull(QuantityInputRule.parse("100"))
        assertNull(QuantityInputRule.parse("abc"))
    }

    @Test
    fun `小数绝不静默进位成整数`() {
        // 这是最重要的一条：若 parse 复用 sanitize，「1.5」会被清洗成「15」写库 ——
        // 那是替用户猜了一个他没说的数字，正是「零确认」原则最不能容忍的错。
        assertNull(QuantityInputRule.parse("1.5"))
        assertNull(QuantityInputRule.parse("0.9"))
        assertNull(QuantityInputRule.parse("2."))
        assertNull(QuantityInputRule.parse("-1"))
    }

    @Test
    fun `全角数字允许归一后写入`() {
        // 全角只是输入形式差异，不是数值差异 —— 归一是安全的
        assertEquals(12.0, QuantityInputRule.parse("１２")!!, 0.0)
        assertEquals(9.0, QuantityInputRule.parse("９")!!, 0.0)
    }

    @Test
    fun `全角小数仍被拒绝`() {
        assertNull(QuantityInputRule.parse("１.５"))
    }

    // ---------- 边界值 ----------

    @Test
    fun `上下边界各自可确认`() {
        assertEquals(1, QuantityInputRule.MIN)
        assertEquals(99, QuantityInputRule.MAX)
        assertTrue(QuantityInputRule.isConfirmable(QuantityInputRule.MIN.toString()))
        assertTrue(QuantityInputRule.isConfirmable(QuantityInputRule.MAX.toString()))
    }

    @Test
    fun `边界外一格就拒绝`() {
        assertFalse(QuantityInputRule.isConfirmable((QuantityInputRule.MIN - 1).toString()))
        assertFalse(QuantityInputRule.isConfirmable((QuantityInputRule.MAX + 1).toString()))
    }

    @Test
    fun `三位数绝不被截断成两位`() {
        // 回归用例：若 strictInt 漏了长度检查，「100」会被 toIntOrNull() 解析后再判范围 ——
        // 一旦某处写成 sanitize().take(2) 之类的组合，「100」就会变「10」写进库，
        // 那就是替用户把 100 猜成了 10。三道门都要拦住它。
        assertNull(QuantityInputRule.parse("100"))
        assertFalse(QuantityInputRule.isConfirmable("100"))
        assertNull(QuantityInputRule.parse("999"))
        assertNull(QuantityInputRule.parse("1000"))
    }
}