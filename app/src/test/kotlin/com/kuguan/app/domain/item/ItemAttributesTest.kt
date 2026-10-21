package com.kuguan.app.domain.item

import com.kuguan.app.domain.item.ItemAttributes.Expiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页可编辑字段的纯领域规则。
 *
 * 这里锁死两件事：
 * 1. 非法日期**绝不**被静默修正（`2026-02-30` 不能变成 `2026-02-28`）；
 * 2. 别名接受「七号 / 7号」两种数字形态，且去掉与名称重复的项。
 */
class ItemAttributesTest {

    @Test
    fun `空有效期是合法值表示清空`() {
        assertEquals(Expiry.Valid(null), ItemAttributes.parseExpiry(""))
        assertEquals(Expiry.Valid(null), ItemAttributes.parseExpiry("   "))
        assertEquals(Expiry.Valid(null), ItemAttributes.parseExpiry(null))
    }

    @Test
    fun `合法日期原样保留`() {
        assertEquals(Expiry.Valid("2026-10-18"), ItemAttributes.parseExpiry("2026-10-18"))
        assertEquals(Expiry.Valid("2024-02-29"), ItemAttributes.parseExpiry("2024-02-29"))
    }

    @Test
    fun `斜杠格式不合法`() {
        assertEquals(Expiry.Invalid, ItemAttributes.parseExpiry("2026/10/18"))
    }

    @Test
    fun `不存在的日期不合法且不被修正`() {
        assertEquals(Expiry.Invalid, ItemAttributes.parseExpiry("2026-02-30"))
        assertEquals(Expiry.Invalid, ItemAttributes.parseExpiry("2026-13-01"))
        assertEquals(Expiry.Invalid, ItemAttributes.parseExpiry("2025-02-29"))
    }

    @Test
    fun `缺位日期不合法`() {
        assertEquals(Expiry.Invalid, ItemAttributes.parseExpiry("2026-1-1"))
    }

    @Test
    fun `别名按中英文逗号顿号分号与空白拆分`() {
        assertEquals(
            listOf("墙塞", "膨胀管", "胶塞", "塞子"),
            ItemAttributes.parseAliases("墙塞，膨胀管、胶塞; 塞子"),
        )
    }

    @Test
    fun `别名归一去掉空项重复项和与名称相同的项`() {
        val result = ItemAttributes.normalizeAliases(
            aliases = listOf(" 电池 ", "", "电池", "7号电池", "7号电池"),
            name = "电池",
        )
        assertEquals(listOf("7号电池"), result)
    }

    @Test
    fun `名称变体七与7双向转换`() {
        assertTrue("七号电池" in ItemAttributes.variants("7号电池"))
        assertTrue("7号电池" in ItemAttributes.variants("七号电池"))
        assertEquals(
            ItemAttributes.variants("七号电池").toSet(),
            ItemAttributes.variants("7号电池").toSet(),
        )
    }

    @Test
    fun `名称变体不重复且保留原名`() {
        assertEquals(listOf("登山杖"), ItemAttributes.variants("登山杖"))
    }

    @Test
    fun `别名JSON编解码往返一致`() {
        val aliases = listOf("墙塞", "膨胀管")
        val encoded = ItemAttributes.encodeAliases(aliases)
        assertEquals(aliases, ItemAttributes.decodeAliases(encoded))
        assertEquals(emptyList<String>(), ItemAttributes.decodeAliases("[]"))
    }

    @Test
    fun `别名特殊字符JSON编解码往返一致`() {
        val aliases = listOf("A,B", "螺丝\"刀", "路径\\盒", "带%_号")
        assertEquals(aliases, ItemAttributes.decodeAliases(ItemAttributes.encodeAliases(aliases)))
    }
}
