package com.kuguan.app.data.voice

import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.data.llm.LlmClient
import com.kuguan.app.data.llm.LlmRequest
import com.kuguan.app.data.llm.LlmResult
import com.kuguan.app.data.repo.FakeDb
import com.kuguan.app.domain.voice.IntentKind
import com.kuguan.app.domain.voice.TestFiles
import com.kuguan.app.domain.voice.IntentSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排层：什么时候出网、失败怎么降级、prompt 里注入了什么。
 *
 * 这些用例全部用 fake client，**不打真实 API** —— 真 API 的数字由
 * `tools/m2-intent-bench.mjs` 负责（那边有 50 条语料和延迟统计）。
 */
class IntentGatewayTest {

    private class FakeLlm(
        private val result: LlmResult,
        override val configured: Boolean = true,
        override val configError: String? = if (configured) null else "LLM 没配密钥",
    ) : LlmClient {
        var calls = 0
        var lastRequest: LlmRequest? = null

        override suspend fun extract(request: LlmRequest): LlmResult {
            calls++
            lastRequest = request
            return result
        }
    }

    private val PROMPT = TestFiles.read(TestFiles.PROMPT)

    private val db = FakeDb()
    private val repo = db.repository()

    private fun gateway(
        llm: LlmClient,
        template: String = PROMPT,
        backup: LlmClient? = null,
        diagnostics: SpeechDiagnosticRecorder = NoSpeechDiagnostics,
    ) = IntentGateway(repo, llm, template, today = { "2026-10-18" },
        fallbackLlm = backup, diagnostics = diagnostics)

    private fun ok(content: String) = LlmResult.Success(content, latencyMs = 850)


    @Test
    fun `查询请求携带完整库存且接受多个候选名称`() = runTest {
        repo.put(itemName = "电池", locationCode = "A189", quantity = 1.0)
        repo.put(itemName = "7号电池", locationCode = "A-07", quantity = 4.0)
        val llm = FakeLlm(ok("""{"intent":"query","items":[{"name":"电池","quantity":null,"locationCode":null},{"name":"7号电池","quantity":null,"locationCode":null}]}"""))

        val resolved = gateway(llm).resolve("电池在哪？")

        assertEquals(listOf("电池", "7号电池"), resolved.plan.rows.map { it.itemName })
        val prompt = llm.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("A189"))
        assertTrue(prompt.contains("A-07"))
        assertTrue(prompt.contains("lastPutAtUtc"))
    }

    @Test
    fun `无密钥不发请求且没有待写入行`() = runTest {
        val llm = FakeLlm(ok("{}"), configured = false)
        val resolved = gateway(llm).resolve("往A07放两节七号电池、三节五号电池。")

        assertEquals(0, llm.calls)
        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertTrue(resolved.plan.rows.isEmpty())
        assertFalse(resolved.trace.attempted)
        assertTrue(resolved.trace.error!!.contains("密钥"))
    }

    @Test
    fun `离线不发请求且没有待写入行`() = runTest {
        val llm = FakeLlm(ok("{}"))
        val resolved = gateway(llm).resolve("往A07放两节七号电池、三节五号电池。", allowNetwork = false)

        assertEquals(0, llm.calls)
        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertTrue(resolved.plan.rows.isEmpty())
        assertTrue(resolved.trace.error!!.contains("无网络"))
    }

    @Test
    fun `prompt 模板缺失时降级而不是发空 prompt`() = runTest {
        val llm = FakeLlm(ok("{}"))
        val resolved = gateway(llm, template = "").resolve("往A07放两节七号电池、三节五号电池。")

        assertEquals(0, llm.calls)
        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertTrue(resolved.plan.rows.isEmpty())
        assertTrue(resolved.trace.error!!.contains("prompt"))
    }

    @Test
    fun `简单句也请求模型并保留完整 USB延长线名称`() = runTest {
        val llm = FakeLlm(ok("""{"intent":"put","items":[{"name":"USB延长线","quantity":1,"locationCode":"A07"}]}"""))
        val resolved = gateway(llm).resolve("A07里放了USB延长线一条。")

        assertEquals(1, llm.calls)
        assertEquals(IntentSource.LLM, resolved.plan.source)
        assertNull(resolved.trace.error)
        assertEquals("USB延长线", resolved.plan.rows.single().itemName)
        assertEquals("A-07", resolved.plan.rows[0].locationCode)
    }

    @Test
    fun `模型漏库位时不从 ASR 原文补位`() = runTest {
        val llm = FakeLlm(ok("""{"intent":"put","items":[{"name":"USB延长线","quantity":1,"locationCode":null}]}"""))
        val resolved = gateway(llm).resolve("A07里放了USB延长线一条。")

        assertEquals(1, llm.calls)
        assertEquals("USB延长线", resolved.plan.rows.single().itemName)
        assertNull(resolved.plan.rows.single().locationCode)
    }

    @Test
    fun `模型未拆出物品时不生成待写入行`() = runTest {
        val llm = FakeLlm(ok("""{"intent":"put","items":[]}"""))
        val resolved = gateway(llm).resolve("A07里放了USB延长线一条。")

        assertEquals(1, llm.calls)
        assertEquals(IntentKind.UNKNOWN, resolved.plan.kind)
        assertTrue(resolved.plan.rows.isEmpty())
    }

    @Test
    fun `单独库位号也请求模型且 unknown 不生成待写入行`() = runTest {
        val llm = FakeLlm(ok("""{"intent":"unknown","items":[]}"""))
        val resolved = gateway(llm).resolve("A-07。")

        assertEquals(1, llm.calls)
        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertTrue(resolved.trace.attempted)
        assertTrue(resolved.plan.rows.isEmpty())
    }

    @Test
    fun `批量句走 LLM 并采用结果`() = runTest {
        val llm = FakeLlm(
            ok(
                """{"intent":"put","items":[
                    {"name":"七号电池","quantity":2,"locationCode":"A-07"},
                    {"name":"五号电池","quantity":3,"locationCode":"A-07"}
                ],"targetSeq":null,"note":null}"""
            )
        )
        val resolved = gateway(llm).resolve("往A07放两节七号电池、三节五号电池。")

        assertEquals(1, llm.calls)
        assertEquals(IntentSource.LLM, resolved.plan.source)
        assertEquals(IntentKind.PUT, resolved.plan.kind)
        assertEquals(2, resolved.plan.rows.size)
        assertTrue(resolved.trace.hit)
        assertEquals(850, resolved.trace.latencyMs)
    }

    @Test
    fun `LLM 失败时保留原因且不生成待写入行`() = runTest {
        val llm = FakeLlm(LlmResult.Failure("LLM 超时（>2200ms）", 2200))
        val resolved = gateway(llm).resolve("往A07放两节七号电池、三节五号电池。")

        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertFalse(resolved.trace.hit)
        assertTrue(resolved.trace.attempted)
        assertTrue(resolved.trace.error!!.contains("超时"))
        assertEquals(2200, resolved.trace.latencyMs)
        assertEquals(IntentKind.UNKNOWN, resolved.plan.kind)
        assertTrue(resolved.plan.rows.isEmpty())
    }

    @Test
    fun `LLM 返回非 JSON 时不生成待写入行`() = runTest {
        val llm = FakeLlm(ok("好的，我明白了"))
        val resolved = gateway(llm).resolve("往A07放两节七号电池、三节五号电池。")

        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertTrue(resolved.plan.rows.isEmpty())
        assertNotNull(resolved.trace.error)
        assertEquals("好的，我明白了", resolved.trace.snippet)
        assertEquals(IntentKind.UNKNOWN, resolved.plan.kind)
    }

    @Test
    fun `LLM 返回 unknown 时不生成待写入行`() = runTest {
        val llm = FakeLlm(ok("""{"intent":"unknown","items":[],"targetSeq":null,"note":"没听清"}"""))
        val resolved = gateway(llm).resolve("往A07放两节七号电池、三节五号电池。")

        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertFalse(resolved.trace.hit)
        assertEquals(IntentKind.UNKNOWN, resolved.plan.kind)
    }

    @Test
    fun `主线路成功时不调用官方直连`() = runTest {
        val primary = FakeLlm(ok("""{"intent":"put","items":[{"name":"电池","quantity":2,"locationCode":"A07"}]}"""))
        val backup = FakeLlm(LlmResult.Failure("不应调用", 0))
        val resolved = gateway(primary, backup = backup).resolve("往A07放两节七号电池、三节五号电池。")
        assertEquals(1, primary.calls)
        assertEquals(0, backup.calls)
        assertEquals("中转站", resolved.trace.route)
    }

    @Test
    fun `主线路超时后官方直连接管完整批量计划`() = runTest {
        val primary = FakeLlm(LlmResult.Failure("LLM 超时", 2200))
        val backup = FakeLlm(ok("""{"intent":"put","items":[{"name":"七号电池","quantity":2,"locationCode":"A07"},{"name":"五号电池","quantity":3,"locationCode":"A07"}]}"""))
        val resolved = gateway(primary, backup = backup).resolve("往A07放两节七号电池、三节五号电池。")
        assertEquals(1, primary.calls)
        assertEquals(1, backup.calls)
        assertEquals(2, resolved.plan.rows.size)
        assertEquals(IntentSource.LLM, resolved.plan.source)
        assertEquals("官方直连", resolved.trace.route)
        assertEquals(3050, resolved.trace.latencyMs)
        assertNull(resolved.trace.error)
    }

    @Test
    fun `主线路无效 JSON 时调用官方直连`() = runTest {
        val primary = FakeLlm(ok("<!doctype html>"))
        val backup = FakeLlm(ok("""{"intent":"put","items":[{"name":"电池","quantity":2,"locationCode":"A07"}]}"""))
        val resolved = gateway(primary, backup = backup).resolve("往A07放两节七号电池、三节五号电池。")
        assertEquals(1, backup.calls)
        assertEquals("官方直连", resolved.trace.route)
    }

    @Test
    fun `主线路返回 unknown 时调用官方直连`() = runTest {
        val primary = FakeLlm(ok("""{"intent":"unknown","items":[]}"""))
        val backup = FakeLlm(ok("""{"intent":"put","items":[{"name":"电池","quantity":2,"locationCode":"A07"}]}"""))
        val resolved = gateway(primary, backup = backup).resolve("往A07放两节七号电池、三节五号电池。")
        assertEquals(1, backup.calls)
        assertEquals("官方直连", resolved.trace.route)
    }

    @Test
    fun `主线路未配置时直接调用官方直连`() = runTest {
        val primary = FakeLlm(ok("{}"), configured = false)
        val backup = FakeLlm(ok("""{"intent":"put","items":[{"name":"电池","quantity":2,"locationCode":"A07"}]}"""))
        val resolved = gateway(primary, backup = backup).resolve("往A07放两节七号电池、三节五号电池。")
        assertEquals(0, primary.calls)
        assertEquals(1, backup.calls)
        assertEquals("官方直连", resolved.trace.route)
    }

    @Test
    fun `两条线路均失败时复杂批量不产生待写入行`() = runTest {
        val primary = FakeLlm(LlmResult.Failure("超时", 2200))
        val backup = FakeLlm(LlmResult.Failure("HTTP 500", 300))
        val resolved = gateway(primary, backup = backup).resolve("往A07放两节七号电池、三节五号电池。")
        assertEquals(IntentKind.UNKNOWN, resolved.plan.kind)
        assertTrue(resolved.plan.rows.isEmpty())
        assertTrue(resolved.trace.error!!.contains("官方直连"))
        assertEquals(2500, resolved.trace.latencyMs)
    }

    @Test
    fun `prompt 注入了今天 已知物品 最近操作`() = runTest {
        db.itemDao.rows["i1"] = ItemEntity(
            id = "i1",
            name = "七号电池",
            aliases = "[]",
            category = null,
            expiryDate = null,
            lastUsedAt = null,
            deletedAt = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        db.operationDao.rows["o7"] = OperationEntity(
            id = "o7",
            seq = 7,
            type = OperationType.PUT,
            itemId = "i1",
            locationCode = "A-07",
            quantity = 2.0,
            createdAt = 1L,
        )

        val llm = FakeLlm(ok("""{"intent":"put","items":[{"name":"七号电池","quantity":2,"locationCode":"A-07"}]}"""))
        gateway(llm).resolve("往A07放两节七号电池、三节五号电池。")

        val prompt = llm.lastRequest!!.systemPrompt
        assertTrue(prompt.contains("2026-10-18"))
        assertTrue(prompt.contains("七号电池"))
        assertTrue(prompt.contains("seq=7"))
        assertTrue(prompt.contains("A-07"))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun `空文本不调用 LLM`() = runTest {
        val llm = FakeLlm(ok("{}"))
        val resolved = gateway(llm).resolve("   ")

        assertEquals(0, llm.calls)
        assertEquals(IntentSource.UNRESOLVED, resolved.plan.source)
        assertTrue(resolved.plan.rows.isEmpty())
    }

    @Test
    fun `诊断记录含同一会话的完整模型输出与逐行待填原因`() = runTest {
        val events = mutableListOf<Triple<String, String, String>>()
        val diagnostics = SpeechDiagnosticRecorder { id, stage, content ->
            events += Triple(id, stage, content)
        }
        val raw = "A01里面放了两节5号电池，一个电动螺丝刀，一个指甲钳和两个。笔记本电脑和。两个哑铃片。"
        val model = """{"intent":"put","items":[{"name":"5号电池","quantity":2,"locationCode":"A-01"},{"name":"笔记本电脑","quantity":null,"locationCode":"A-01"},{"name":"哑铃片","quantity":null,"locationCode":"A-01"}],"note":"原始输出全部保留"}"""
        val resolved = gateway(FakeLlm(ok(model)), diagnostics = diagnostics)
            .resolve(raw, sessionId = "speech-123")

        assertEquals(3, resolved.plan.rows.size)
        assertTrue(events.all { it.first == "speech-123" })
        assertEquals(raw, events.single { it.second == "ASR_INPUT" }.third)
        assertEquals(model, events.single { it.second == "LLM_PRIMARY_RAW" }.third)
        val plan = events.single { it.second == "PLAN" }.third
        assertTrue(plan.contains("name=笔记本电脑 quantity=null location=A-01"))
        assertTrue(plan.contains("name=哑铃片 quantity=null location=A-01"))
        assertTrue(plan.contains("writable=false missing=[QUANTITY]"))
    }

    @Test
    fun `主线路失败与备用输出都被记录`() = runTest {
        val events = mutableListOf<String>()
        val diagnostics = SpeechDiagnosticRecorder { _, stage, content ->
            events += "$stage:$content"
        }
        val backupRaw = """{"intent":"put","items":[{"name":"电池","quantity":2,"locationCode":"A07"}]}"""
        gateway(FakeLlm(LlmResult.Failure("超时", 2200)),
            backup = FakeLlm(ok(backupRaw)), diagnostics = diagnostics)
            .resolve("往A07放两节电池、螺丝刀。", sessionId = "s2")
        assertTrue(events.any { it.startsWith("LLM_PRIMARY_ERROR:") && it.contains("超时") })
        assertTrue(events.contains("LLM_FALLBACK_RAW:$backupRaw"))
        assertTrue(events.any { it.startsWith("PLAN:") && it.contains("route=官方直连") })
    }

    @Test
    fun `诊断记录失败不影响解析结果`() = runTest {
        val broken = SpeechDiagnosticRecorder { _, _, _ -> error("磁盘不可写") }
        val resolved = gateway(FakeLlm(ok("""{"intent":"put","items":[{"name":"电池","quantity":2,"locationCode":"A07"}]}""")),
            diagnostics = broken).resolve("往A07放两节电池、螺丝刀。")
        assertEquals(IntentKind.PUT, resolved.plan.kind)
        assertEquals("电池", resolved.plan.rows.single().itemName)
    }

    @Test
    fun `一次解析固定主备线路快照而下次请求读取新版`() = runTest {
        val oldBackup = FakeLlm(ok("""{"intent":"put","items":[{"name":"旧备用","quantity":1,"locationCode":"A07"}]}"""))
        val newPrimary = FakeLlm(ok("""{"intent":"put","items":[{"name":"新主线","quantity":1,"locationCode":"A07"}]}"""))
        var routes: Pair<LlmClient, LlmClient?>? = null
        val oldPrimary = object : LlmClient {
            override val configured = true
            override val configError: String? = null
            override suspend fun extract(request: LlmRequest): LlmResult {
                routes = newPrimary to null // Simulate saving a new config while request is in flight.
                return LlmResult.Failure("超时", 2200)
            }
        }
        routes = oldPrimary to oldBackup
        val gateway = IntentGateway(repo, oldPrimary, PROMPT,
            routeProvider = { routes!! })

        val first = gateway.resolve("往A07放一个物品")
        val second = gateway.resolve("往A07放一个物品")

        assertEquals("旧备用", first.plan.rows.single().itemName)
        assertEquals("新主线", second.plan.rows.single().itemName)
        assertEquals(1, oldBackup.calls)
        assertEquals(1, newPrimary.calls)
    }

}
