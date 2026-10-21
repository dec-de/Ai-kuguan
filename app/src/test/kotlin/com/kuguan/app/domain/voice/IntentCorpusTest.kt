package com.kuguan.app.domain.voice

import com.kuguan.app.domain.normalize.LocationCodeNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语料自检 + prompt 单一真源校验。
 *
 * 语料是「判断样本」，写错一条等于门禁数字失真。这些用例保证：
 * - 54 条、id 不重复、六个意图都覆盖到；
 * - 每条期望值本身合法（编号形状、数量为正整数）；
 * - prompt 模板里该有的占位符都在，渲染后不残留 `{{...}}`。
 */
class IntentCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val corpus: JsonObject by lazy {
        json.parseToJsonElement(TestFiles.read(TestFiles.CORPUS)).jsonObject
    }
    private val cases get() = corpus["cases"]!!.jsonArray.map { it.jsonObject }
    private val context get() = corpus["context"]!!.jsonObject

    @Test
    fun `语料恰好 54 条`() {
        assertEquals(54, cases.size)
    }

    @Test
    fun `id 唯一`() {
        val ids = cases.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `六个意图全部覆盖`() {
        val intents = cases.map { it["expect"]!!.jsonObject["intent"]!!.jsonPrimitive.content }
        assertEquals(
            setOf("put", "take", "query", "correct", "delete"),
            intents.toSet().filter { it != "unknown" }.toSet(),
        )
    }

    @Test
    fun `每条都有真实文本与一句话说明`() {
        cases.forEach { case ->
            assertTrue(case["text"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(case["note"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `期望的编号全部是合法形态或 null`() {
        cases.flatMap { it["expect"]!!.jsonObject["items"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .forEach { item ->
                val code = item["locationCode"]?.jsonPrimitive?.content
                if (code != null && code != "null") {
                    assertTrue("非法期望编号：$code", LocationCodeNormalizer.looksLikeLocationCode(code))
                }
            }
    }

    @Test
    fun `期望的数量全部是正整数或 null`() {
        cases.flatMap { it["expect"]!!.jsonObject["items"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
            .forEach { item ->
                val qty = item["quantity"]
                if (qty != null && qty !is kotlinx.serialization.json.JsonNull) {
                    val value = qty.jsonPrimitive.content.toDouble()
                    assertTrue("期望数量必须是正整数：$value", value > 0 && value % 1.0 == 0.0)
                }
            }
    }

    @Test
    fun `put 与 take 至少有一行，delete 必须没有行`() {
        cases.forEach { case ->
            val expect = case["expect"]!!.jsonObject
            val intent = expect["intent"]!!.jsonPrimitive.content
            val items = expect["items"]!!.jsonArray
            when (intent) {
                "put", "take" -> assertTrue("$intent 必须有 item：${case["id"]}", items.isNotEmpty())
                "delete" -> assertTrue("delete 的 items 必须为空：${case["id"]}", items.isEmpty())
                "correct" -> assertEquals("correct 必须回填完整状态：${case["id"]}", 1, items.size)
                "query" -> assertTrue("query 必须有至少一个候选：${case["id"]}", items.isNotEmpty())
            }
        }
    }

    @Test
    fun `correct 与 delete 都有 targetSeq`() {
        cases.forEach { case ->
            val expect = case["expect"]!!.jsonObject
            val intent = expect["intent"]!!.jsonPrimitive.content
            if (intent == "correct" || intent == "delete") {
                assertNotNull("${case["id"]} 缺 targetSeq", expect["targetSeq"])
            }
        }
    }

    @Test
    fun `单句八行的样本存在且确实是八行`() {
        val row8 = cases.first { it["id"]!!.jsonPrimitive.content == "m10" }
        assertEquals(8, row8["expect"]!!.jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun `难例与批量场景都有覆盖`() {
        val tags = cases.flatMap { it["tags"]!!.jsonArray.map { t -> t.jsonPrimitive.content } }.toSet()
        assertTrue(tags.containsAll(listOf("multi_item", "multi_location", "correct", "delete", "query", "hard")))
        // 指代 / 否定 两类用文本关键词兜住（没有单独打 tag 也必须存在）
        val texts = cases.joinToString("\n") { it["text"]!!.jsonPrimitive.content }
        assertTrue(texts.contains("那个螺丝刀也放进去"))
        assertTrue(texts.contains("不是A07"))
        assertTrue(texts.contains("A4纸"))
        assertTrue(texts.contains("D号"))
        assertTrue(texts.contains("1.5米"))
        assertTrue(texts.contains("两格都放了"))
    }

    @Test
    fun `上下文是三条最近操作且 seq 唯一`() {
        val ops = context["recentOps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, ops.size)
        assertEquals(3, ops.map { it["seq"]!!.jsonPrimitive.content }.toSet().size)
    }

    @Test
    fun `已知物品不超过 30 个`() {
        assertTrue(context["knownItems"]!!.jsonArray.size <= IntentPrompt.MAX_KNOWN_ITEMS)
    }

    @Test
    fun `prompt 模板包含全部占位符`() {
        val template = TestFiles.read(TestFiles.PROMPT)
        listOf("{{TODAY}}", "{{KNOWN_ITEMS}}", "{{RECENT_OPS}}", "{{LAST_SEQ}}").forEach {
            assertTrue("prompt 缺占位符 $it", template.contains(it))
        }
    }

    @Test
    fun `prompt 模板写明了输出 schema 与关键铁律`() {
        val template = TestFiles.read(TestFiles.PROMPT)
        listOf("intent", "locationCode", "targetSeq", "quantity").forEach {
            assertTrue("prompt 缺字段说明 $it", template.contains(it))
        }
        listOf("绝不猜", "00", "A4纸").forEach {
            assertTrue("prompt 缺硬规则 $it", template.contains(it))
        }
        listOf("ASR 标点", "不可仅凭标点", "没有说新位置", "数量不能沿用").forEach {
            assertTrue("prompt 缺跨分句约束 $it", template.contains(it))
        }
    }

    @Test
    fun `用真实上下文渲染后不残留占位符`() {
        val template = TestFiles.read(TestFiles.PROMPT)
        val ops = context["recentOps"]!!.jsonArray.map { it.jsonObject }.map { op ->
            IntentPrompt.RecentOp(
                seq = op["seq"]!!.jsonPrimitive.int.toLong(),
                type = op["type"]!!.jsonPrimitive.content,
                itemName = op["item"]!!.jsonPrimitive.content,
                locationCode = op["locationCode"]!!.jsonPrimitive.content,
                quantity = op["quantity"]!!.jsonPrimitive.content.toDouble(),
            )
        }
        val rendered = IntentPrompt.render(
            template,
            IntentPrompt.PromptContext(
                today = context["today"]!!.jsonPrimitive.content,
                knownItems = context["knownItems"]!!.jsonArray.map { it.jsonPrimitive.content },
                recentOps = ops,
            ),
        )
        assertTrue(rendered.contains("2026-10-18"))
        assertTrue(rendered.contains("seq=103"))
        assertTrue(rendered.contains("七号电池"))
        assertTrue("渲染后仍有占位符", !rendered.contains("{{"))
        assertTrue("LAST_SEQ 必须是最后一条", rendered.contains("seq = 103"))
    }

}
