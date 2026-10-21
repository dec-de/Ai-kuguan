package com.kuguan.app.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 输出的解析与归一。
 *
 * 这批用例全部对应**真实会发生的模型错误**（M0.5 / M2 实测）：
 * `A-00`、`A007`、数量写成字符串、`1.5`、套一层 content、```json 围栏。
 */
class IntentJsonTest {

    @Test
    fun `标准 put 输出`() {
        val parsed = IntentJson.parse(
            """{"intent":"put","items":[{"name":"7号电池","quantity":2,"locationCode":"A-07"}],"targetSeq":null,"note":null}"""
        )!!
        assertEquals(IntentKind.PUT, parsed.kind)
        assertEquals(1, parsed.rows.size)
        assertEquals("7号电池", parsed.rows[0].itemName)
        assertEquals(2.0, parsed.rows[0].quantity!!, 0.0)
        assertEquals("A-07", parsed.rows[0].locationCode)
        assertFalse(parsed.rows[0].locationSuspect)
        assertNull(parsed.targetSeq)
    }

    @Test
    fun `多行输出保持顺序`() {
        val parsed = IntentJson.parse(
            """
            {"intent":"put","items":[
              {"name":"七号电池","quantity":2,"locationCode":"A-07"},
              {"name":"五号电池","quantity":3,"locationCode":"A-07"},
              {"name":"手电筒","quantity":null,"locationCode":"A-07"}
            ],"targetSeq":null,"note":null}
            """.trimIndent()
        )!!
        assertEquals(3, parsed.rows.size)
        assertEquals(listOf("七号电池", "五号电池", "手电筒"), parsed.rows.map { it.itemName })
        assertNull(parsed.rows[2].quantity)
    }

    @Test
    fun `编号 A07 归一成 A-07`() {
        assertEquals("A-07", IntentJson.canonicalCode("A07")!!.value)
    }

    @Test
    fun `编号 a-7 补零成 A-07`() {
        val code = IntentJson.canonicalCode("a-7")!!
        assertEquals("A-07", code.value)
        assertFalse(code.suspect)
    }

    @Test
    fun `编号 A 07 去空格`() {
        assertEquals("A-07", IntentJson.canonicalCode("A 07")!!.value)
    }

    @Test
    fun `三位编号 A007 保留原值且可写`() {
        val code = IntentJson.canonicalCode("A007")!!
        assertEquals("A007", code.value)
        assertFalse(code.suspect)
    }

    @Test
    fun `D-00 绝不静默修正`() {
        val code = IntentJson.canonicalCode("D-00")!!
        assertEquals("D-00", code.value)
        assertFalse(code.suspect)
    }

    @Test
    fun `中文读法 A洞拐 走 L1 归一`() {
        val code = IntentJson.canonicalCode("A洞拐")!!
        assertEquals("A-07", code.value)
        assertFalse(code.suspect)
    }

    @Test
    fun `中文读法 三位保留全部数字`() {
        val code = IntentJson.canonicalCode("A洞零拐")!!
        assertEquals("A007", code.value)
        assertFalse(code.suspect)
    }

    @Test
    fun `A113 与自由位置原样保留`() {
        for (raw in listOf("A113", "A-113", "左边工具柜", "E号")) {
            val code = IntentJson.canonicalCode(raw)!!
            assertEquals(raw, code.value)
            assertFalse(code.suspect)
        }
    }

    @Test
    fun `编号为空返回 null`() {
        assertNull(IntentJson.canonicalCode(null))
        assertNull(IntentJson.canonicalCode("   "))
    }

    @Test
    fun `数量写成字符串也能吃`() {
        val parsed = IntentJson.parse(
            """{"intent":"put","items":[{"name":"电池","quantity":"3","locationCode":"A-07"}]}"""
        )!!
        assertEquals(3.0, parsed.rows[0].quantity!!, 0.0)
    }

    @Test
    fun `小数数量 1_5 归 null 而不是 15`() {
        assertNull(IntentJson.parseQuantity(kotlinx.serialization.json.JsonPrimitive(1.5)))
        assertNull(IntentJson.parseQuantity(kotlinx.serialization.json.JsonPrimitive("1.5")))
    }

    @Test
    fun `数量 0 与负数归 null`() {
        assertNull(IntentJson.parseQuantity(kotlinx.serialization.json.JsonPrimitive(0)))
        assertNull(IntentJson.parseQuantity(kotlinx.serialization.json.JsonPrimitive(-2)))
    }

    @Test
    fun `数量缺省为 null`() {
        assertNull(IntentJson.parseQuantity(null))
        assertNull(IntentJson.parseQuantity(kotlinx.serialization.json.JsonPrimitive("abc")))
    }

    @Test
    fun `去掉 Markdown 围栏`() {
        val parsed = IntentJson.parse(
            """
            ```json
            {"intent":"query","items":[{"name":"登山杖"}],"targetSeq":null,"note":null}
            ```
            """.trimIndent()
        )!!
        assertEquals(IntentKind.QUERY, parsed.kind)
        assertEquals("登山杖", parsed.rows[0].itemName)
    }

    @Test
    fun `剥掉中转站套的 content 外壳`() {
        val parsed = IntentJson.parse(
            """{"type":"json_object","content":{"intent":"delete","items":[],"targetSeq":103}}"""
        )!!
        assertEquals(IntentKind.DELETE, parsed.kind)
        assertEquals(103L, parsed.targetSeq)
    }

    @Test
    fun `乱码输出返回 null 让调用方降级`() {
        assertNull(IntentJson.parse("抱歉，我不太明白你的意思"))
        assertNull(IntentJson.parse(""))
        assertNull(IntentJson.parse(null))
    }

    @Test
    fun `未知 intent 落到 UNKNOWN`() {
        assertEquals(IntentKind.UNKNOWN, IntentJson.parse("""{"intent":"dance","items":[]}""")!!.kind)
    }

    @Test
    fun `intent 大小写与别名`() {
        assertEquals(IntentKind.PUT, IntentJson.parse("""{"intent":"PUT","items":[]}""")!!.kind)
        assertEquals(IntentKind.QUERY, IntentJson.parse("""{"intent":"search","items":[]}""")!!.kind)
    }

    @Test
    fun `targetSeq 字符串形式`() {
        assertEquals(102L, IntentJson.parse("""{"intent":"delete","targetSeq":"102"}""")!!.targetSeq)
    }

    @Test
    fun `空物品名归 null`() {
        val parsed = IntentJson.parse("""{"intent":"put","items":[{"name":"   ","quantity":2,"locationCode":"A-07"}]}""")!!
        assertNull(parsed.rows[0].itemName)
    }

    @Test
    fun `note 截断到 40 字`() {
        val long = "很".repeat(80)
        val parsed = IntentJson.parse("""{"intent":"put","items":[],"note":"$long"}""")!!
        assertEquals(40, parsed.note!!.length)
    }
}
