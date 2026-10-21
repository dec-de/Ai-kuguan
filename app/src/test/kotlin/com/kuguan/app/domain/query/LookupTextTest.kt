package com.kuguan.app.domain.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反查文案的回归测试。
 *
 * 门禁要求「多位置一次报全」，所以最重要的两条断言是：
 * 两个位置**都出现**、合计只出现一次且等于各位置之和。
 * 「查不到」的三种含义也必须能区分开 —— 混成一句会误导用户。
 */
class LookupTextTest {

    private fun item(vararg locations: Pair<String, Double>) = ItemSnapshot(
        id = "item-1",
        name = "七号电池",
        category = "电池",
        locations = locations.map { LocationQty(it.first, it.second) },
    )

    @Test
    fun `多位置一次报全并给出派生合计`() {
        val text = LookupText.render(
            LookupResult.Found(item("A-07" to 2.0, "B-05" to 1.0))
        )
        assertTrue(text, text.contains("A-07 · 2 个"))
        assertTrue(text, text.contains("B-05 · 1 个"))
        assertTrue(text, text.contains("合计 3 个"))
    }

    @Test
    fun `三个位置也全部出现`() {
        val text = LookupText.render(
            LookupResult.Found(item("A-07" to 1.0, "B-05" to 2.0, "C-03" to 3.0))
        )
        listOf("A-07", "B-05", "C-03").forEach {
            assertTrue("缺位置 $it：$text", text.contains(it))
        }
        assertTrue(text, text.contains("合计 6 个"))
    }

    @Test
    fun `单个位置不啰嗦合计`() {
        val text = LookupText.render(LookupResult.Found(item("A-07" to 2.0)))
        assertTrue(text, text.contains("A-07 · 2 个"))
        assertFalse(text, text.contains("合计"))
    }

    @Test
    fun `有小数的数量不被截断`() {
        val text = LookupText.render(
            LookupResult.Found(item("A-07" to 1.5, "B-05" to 0.5))
        )
        assertTrue(text, text.contains("A-07 · 1.5 个"))
        assertTrue(text, text.contains("合计 2 个"))
    }

    @Test
    fun `有档案但位置全空说取走了而不是没有这个物品`() {
        val text = LookupText.render(LookupResult.NoLocations(item()))
        assertTrue(text, text.contains("不在任何位置"))
        assertFalse(text, text.contains("库里没有"))
    }

    @Test
    fun `库里没有且无近似`() {
        val text = LookupText.render(LookupResult.NotFound("登山杖", emptyList()))
        assertEquals("库里没有「登山杖」", text)
    }

    @Test
    fun `库里没有但有近似时列出名字`() {
        val text = LookupText.render(
            LookupResult.NotFound(
                "电池",
                listOf(ItemBrief("1", "七号电池"), ItemBrief("2", "五号电池")),
            )
        )
        assertTrue(text, text.contains("最接近的有"))
        assertTrue(text, text.contains("七号电池"))
        assertTrue(text, text.contains("五号电池"))
    }

    @Test
    fun `没给物品名时请用户再说一次`() {
        assertEquals("要查什么？再说一次物品名", LookupText.render(LookupResult.NoName))
    }

    @Test
    fun `数量格式化整数不带小数点`() {
        assertEquals("2", LookupText.quantity(2.0))
        assertEquals("2.5", LookupText.quantity(2.5))
        assertEquals("0.5", LookupText.quantity(0.5))
    }
}
