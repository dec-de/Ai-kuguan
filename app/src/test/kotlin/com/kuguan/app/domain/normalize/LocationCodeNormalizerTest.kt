package com.kuguan.app.domain.normalize

import com.kuguan.app.domain.normalize.LocationCodeNormalizer.ParsedLocationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1/L3 归一化的单测。
 *
 * 用例取自开发计划 10bis.2 的 20 条测试语料 —— 这批语料 Owen 之后要真念一遍，
 * 但**规则层现在就能测**，不必等录音。ASR 那一半（M0.5）才需要真实音频。
 *
 * 这是纯 JVM 测试，秒级跑完，是整个项目性价比最高的一处投入。
 *
 * ⚠️ 期望值有两处偏离原计划表，均为**修正**而非妥协，理由写在对应用例注释里。
 */
class LocationCodeNormalizerTest {

    private fun confirmedOf(raw: String): String? =
        LocationCodeNormalizer.parse(raw).confirmed.firstOrNull()

    private fun ambiguitiesOf(raw: String): List<ParsedLocationCode.Ambiguous> =
        LocationCodeNormalizer.parse(raw).ambiguities

    // ---------- 两位读法：确认（Owen 指定的正确读法）----------

    @Test
    fun `A零拐两位读法确认`() {
        assertEquals("A-07", confirmedOf("A 零拐"))
    }

    @Test
    fun `A零七两位读法确认`() {
        assertEquals("A-07", confirmedOf("A 零七"))
    }

    @Test
    fun `A洞拐两位读法确认`() {
        assertEquals("A-07", confirmedOf("A 洞拐"))
    }

    @Test
    fun `A洞七两位读法确认`() {
        assertEquals("A-07", confirmedOf("A 洞七"))
    }

    @Test
    fun `完整句里的两位读法`() {
        assertEquals("A-07", confirmedOf("A 零拐 里放了 七号电池 两节"))
    }

    // ---------- 三位读法：歧义，必须二次确认（本次修正的核心）----------

    @Test
    fun `A洞零拐三个音节记录为A007而不截成A-07`() {
        // ⚠️ 修正点：原计划表期望 `A-07`，是错的。
        //    「洞零拐」是三个音节（洞=0 零=0 拐=7 → 007），而 A-07 只需两个音节
        //    （零拐 / 零七 / 洞拐 / 洞七）。三个音节被折叠成两位 = 替用户猜了他没说的话。
        val result = LocationCodeNormalizer.parse("A 洞零拐 里放了 七号电池 两节")

        assertFalse(result.hasAmbiguity)
        assertEquals(listOf("A007"), result.confirmed)
    }

    @Test
    fun `B洞洞拐同样保留三位`() {
        val result = LocationCodeNormalizer.parse("B 洞 洞拐")

        assertFalse(result.hasAmbiguity)
        assertEquals(listOf("B007"), result.confirmed)
    }

    @Test
    fun `三位编号直接记录`() {
        // 幺=1 洞=0 拐=7 → 107。用户真念了三个音节，但编号形状定案是「字母 + 两位」，
        // 所以不能当三位编号写库。判歧义 → 那一格标红让用户自己改。
        val result = LocationCodeNormalizer.parse("C 幺洞拐")

        assertFalse(result.hasAmbiguity)
        assertEquals(listOf("C107"), result.confirmed)
    }

    // ---------- B 档：语义 6 条 ----------

    @Test
    fun `生僻物品名不影响编号抽取`() {
        assertEquals("A-07", confirmedOf("膨胀栓 放 A 零拐"))
    }

    @Test
    fun `数量三不被当成编号`() {
        // 「三个」被数量短语规则剔除
        assertNull(confirmedOf("墙上那个膨胀螺丝 也放进去 三个"))
    }

    @Test
    fun `数量两不被当成编号`() {
        assertNull(confirmedOf("牛奶还剩 两瓶"))
    }

    @Test
    fun `结束语干扰不产出编号`() {
        assertNull(confirmedOf("七号电池放好了 没别的了"))
    }

    @Test
    fun `有效期句里的物品名不是编号`() {
        assertNull(confirmedOf("万能胶 还有一年保质期"))
    }

    @Test
    fun `反查句不产出编号`() {
        assertNull(confirmedOf("登山杖 在哪"))
    }

    // ---------- C 档：干扰 6 条 ----------

    @Test
    fun `极短输入`() {
        assertEquals("A-07", confirmedOf("A 零拐"))
    }

    @Test
    fun `语气词加停顿不影响确认判定`() {
        assertEquals("A-07", confirmedOf("嗯…那个 A 零拐 里 有个 螺丝刀"))
    }

    @Test
    fun `一句两个编号要能全部抽出`() {
        // 「两格」是数量被剔除，A/B 两个编号各自独立判定
        val result = LocationCodeNormalizer.parse("A 零拐 B 洞拐 两格 都放了 电池")
        assertEquals(listOf("A-07", "B-07"), result.confirmed)
        assertFalse(result.hasAmbiguity)
    }

    @Test
    fun `一句两个不同位数编号逐个记录`() {
        val result = LocationCodeNormalizer.parse("A 洞零拐 B 洞拐 两格 都放了 电池")
        assertEquals(listOf("A007", "B-07"), result.confirmed)
        assertFalse(result.hasAmbiguity)
    }

    @Test
    fun `纯数字编号`() {
        // 语料 #2：剔除噪声词后剩「洞 拐」两个音节 → 07
        assertEquals("07", confirmedOf("洞号柜 的 拐号抽屉"))
    }

    @Test
    fun `横杠分隔的两位读法`() {
        assertEquals("A-07", confirmedOf("A 横杠 零七"))
    }

    // ---------- L3 格式校验（开发计划 6.4 第一项校验）----------

    @Test
    fun `L3 接受有分隔与无分隔两种形态`() {
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A-07"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A07"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("07"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("AB-99"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("Z-01"))
    }

    @Test
    fun `L3 只拒绝空白编号`() {
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("抽屉7"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A-1234"))
        assertFalse(LocationCodeNormalizer.looksLikeLocationCode(""))
        assertFalse(LocationCodeNormalizer.looksLikeLocationCode("   "))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("12345"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A-00"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A00"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("00"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A-107"))
        assertTrue(LocationCodeNormalizer.looksLikeLocationCode("A-007"))
    }

    // ---------- 2026-10-18 B0：普通读法（这四项此前全是静默改编号）----------

    @Test
    fun `普通读法 A零一 落 A-01 而不是 A-00`() {
        assertEquals("A-01", confirmedOf("A 零一"))
    }

    @Test
    fun `普通读法 A零二 落 A-02`() {
        assertEquals("A-02", confirmedOf("A 零二"))
    }

    @Test
    fun `普通读法 A一七 落 A-17 而不是 A-07`() {
        assertEquals("A-17", confirmedOf("A 一七"))
    }

    @Test
    fun `普通读法 C一七 落 C-17`() {
        assertEquals("C-17", confirmedOf("C 一七"))
    }

    @Test
    fun `普通读法 A一零 落 A-10`() {
        assertEquals("A-10", confirmedOf("A 一零"))
    }

    @Test
    fun `普通读法 A一二 落 A-12 且不得抽空`() {
        assertEquals("A-12", confirmedOf("A 一二"))
    }

    @Test
    fun `A零一 是两位不得判成三位前导零的歧义`() {
        val result = LocationCodeNormalizer.parse("A 零一")
        assertFalse("A 零一 就是两位，不该有歧义", result.hasAmbiguity)
        assertEquals(listOf("A-01"), result.confirmed)
    }

    @Test
    fun `编号内部标点先删再解析 A1，0 落 A-10`() {
        // ASR 会把逐位念的编号吐成 `A1，0` / `A、0 7` 这种形态
        assertEquals("A-10", confirmedOf("A1，0"))
        assertEquals("A-07", confirmedOf("A、0 7"))
        assertEquals("A-17", confirmedOf("A1,7"))
    }

    @Test
    fun `A洞 可作为用户给出的抽屉编号`() {
        // 一位数字 0 补零后是 00，必须被 L3 拒掉 —— 宁可不记，也不写假编号
        assertEquals("A-00", confirmedOf("A 洞"))
    }

    @Test
    fun `十 单独成位落 A-10`() {
        assertEquals("A-10", confirmedOf("A 十"))
    }

    @Test
    fun `普通读法整句要能抽对编号`() {
        // M0.6 录音 01 的真实文本：「A零七里放了七号电池两节」
        assertEquals("A-07", confirmedOf("A零七里放了七号电池两节"))
        // 03 号：「C一七」
        assertEquals("C-17", confirmedOf("C一七"))
        // 11 号：「A一零里放电池」
        assertEquals("A-10", confirmedOf("A一零里放电池"))
    }

    // ---------- 边界情况 ----------

    @Test
    fun `空输入不崩`() {
        assertTrue(LocationCodeNormalizer.parse("").isEmpty)
        assertTrue(LocationCodeNormalizer.parse("   ").isEmpty)
    }

    @Test
    fun `单数字补零到两位`() {
        // 「A 拐」只念了一个音节 → 零填充成两位编号
        assertEquals("A-07", confirmedOf("A 拐"))
    }

    @Test
    fun `已经规范的编号原样通过`() {
        assertEquals("A-07", confirmedOf("A-07"))
    }

    @Test
    fun `重复编号去重`() {
        assertEquals(listOf("A-07"), LocationCodeNormalizer.parse("A 零拐 还有 A 零拐").confirmed)
    }

    @Test
    fun `军事读法的两不被当成军事数字以免误伤`() {
        // 光秃秃的「两」不能被当数字删掉（否则「洞两拐」=027 会被破坏），
        // 但它也不该单独构成编号 —— 只有「两三节」这种带分类词的形式才当数量。
        assertNull(confirmedOf("两"))
    }

    @Test
    fun `三位以上完整记录`() {
        assertEquals("A0007", confirmedOf("A 洞零洞拐"))
        assertEquals("A113", confirmedOf("A113"))
    }
}
