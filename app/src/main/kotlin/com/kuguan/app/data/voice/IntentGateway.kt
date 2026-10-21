package com.kuguan.app.data.voice

import com.kuguan.app.data.llm.LlmClient
import com.kuguan.app.data.llm.LlmRequest
import com.kuguan.app.data.llm.LlmResult
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.domain.voice.IntentJson
import com.kuguan.app.domain.voice.IntentPlan
import com.kuguan.app.domain.voice.IntentPrompt
import com.kuguan.app.domain.voice.IntentResolver
import com.kuguan.app.domain.voice.IntentRowPolicy
import com.kuguan.app.domain.voice.IntentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * 一次意图抽取的诊断信息。**只给 debug 面板用**（release 不显示）。
 *
 * 记录 LLM 路由、耗时和失败原因，便于定位识别问题。
 */
data class IntentTrace(
    /** 有没有真的发请求 */
    val attempted: Boolean = false,
    /** LLM 的结果有没有被采用 */
    val hit: Boolean = false,
    val latencyMs: Long = 0,
    val error: String? = null,
    /** 模型原始输出前 80 字（给 debug 面板看；不含密钥） */
    val snippet: String? = null,
    val source: IntentSource = IntentSource.UNRESOLVED,
    val route: String? = null,
) {
    /** 一句话状态：命中或失败。 */
    val label: String
        get() = when {
            !attempted -> "LLM 未调用：${error ?: "无有效输入"}"
            hit -> "LLM ${route ?: "主线路"}命中 ${latencyMs}ms"
            else -> "LLM 失败（${latencyMs}ms）：${error ?: "未采用"}"
        }
}

data class ResolvedIntent(
    val plan: IntentPlan,
    val trace: IntentTrace,
)

/**
 * 「一句话 → [IntentPlan]」的网络编排层（M2）。
 *
 * 每条非空 ASR 文本交给 LLM；主线路失败时尝试官方直连。
 * 两条线路均未给出有效意图时返回无写入行的 UNKNOWN。
 */
class IntentGateway(
    private val repository: StorageRepository,
    private val llm: LlmClient,
    private val promptTemplate: String,
    private val today: () -> String = { LocalDate.now().toString() },
    private val fallbackLlm: LlmClient? = null,
    private val diagnostics: SpeechDiagnosticRecorder = NoSpeechDiagnostics,
    private val routeProvider: (() -> Pair<LlmClient, LlmClient?>)? = null,
) {

    private data class Attempt(val plan: IntentPlan?, val reason: String?, val snippet: String?, val latencyMs: Long)

    /** 没配密钥只报一次，避免每句话刷一条日志。 */
    @Volatile
    private var configWarned = false

    /**
     * @param allowNetwork 离线时传 false，返回无写入行的失败结果。
     */
    suspend fun resolve(
        raw: String,
        allowNetwork: Boolean = true,
        sessionId: String = UUID.randomUUID().toString(),
    ): ResolvedIntent {
        // Capture both routes once. An edit during this request cannot mix credential versions.
        val (primaryLlm, secondaryLlm) = routeProvider?.invoke() ?: (llm to fallbackLlm)
        diagnostics.recordSafely(sessionId, "ASR_INPUT", raw)
        fun logged(result: ResolvedIntent): ResolvedIntent {
            val plan = result.plan
            diagnostics.recordSafely(
                sessionId, "PLAN",
                buildString {
                    append("kind=${plan.kind} source=${plan.source} route=${result.trace.route} ")
                    append("error=${result.trace.error} note=${plan.note} rows=${plan.rows.size}")
                    plan.rows.forEachIndexed { index, row ->
                        append("\n[$index] name=${row.itemName} quantity=${row.quantity} ")
                        append("location=${row.locationCode} locationSuspect=${row.locationSuspect} ")
                        append("writable=${IntentRowPolicy.isWritable(row)} ")
                        append("missing=${IntentRowPolicy.missingFields(row)}")
                    }
                },
            )
            return result
        }
        fun unavailable(error: String) = logged(ResolvedIntent(
            plan = IntentPlan.unknown(error),
            trace = IntentTrace(attempted = false, error = error),
        ))

        if (raw.isBlank()) return unavailable("空文本")
        if (!primaryLlm.configured && secondaryLlm?.configured != true) {
            // 只报一次：没配密钥是部署问题，不该每说一句话刷一条日志
            if (!configWarned) {
                configWarned = true
                android.util.Log.w(TAG, primaryLlm.configError ?: "LLM 未配置")
            }
            return unavailable(primaryLlm.configError ?: secondaryLlm?.configError ?: "LLM 未配置")
        }
        if (!allowNetwork) return unavailable("无网络，无法识别语音意图")
        if (promptTemplate.isBlank()) return unavailable("prompt 模板缺失")

        val systemPrompt = IntentPrompt.render(promptTemplate, buildContext())
        if (systemPrompt.isBlank()) return unavailable("prompt 渲染为空")

        val request = LlmRequest(systemPrompt, raw)
        var latencyMs = 0L
        var primaryReason: String? = primaryLlm.configError
        var lastSnippet: String? = null

        if (primaryLlm.configured) {
            val primary = attempt(primaryLlm, request, sessionId, "PRIMARY")
            latencyMs += primary.latencyMs
            if (primary.plan != null) return logged(ResolvedIntent(
                primary.plan,
                IntentTrace(attempted = true, hit = true, latencyMs = latencyMs,
                    snippet = primary.snippet, source = IntentSource.LLM, route = "中转站"),
            ))
            primaryReason = primary.reason
            lastSnippet = primary.snippet
        }

        val backup = secondaryLlm?.takeIf { it.configured }
        if (backup != null) {
            val secondary = attempt(backup, request, sessionId, "FALLBACK")
            latencyMs += secondary.latencyMs
            if (secondary.plan != null) return logged(ResolvedIntent(
                secondary.plan,
                IntentTrace(attempted = true, hit = true, latencyMs = latencyMs,
                    snippet = secondary.snippet, source = IntentSource.LLM, route = "官方直连"),
            ))
            lastSnippet = secondary.snippet ?: lastSnippet
            primaryReason = listOfNotNull(primaryReason?.let { "主线路：$it" },
                secondary.reason?.let { "官方直连：$it" }).joinToString("；")
        }

        val error = primaryReason ?: "意图识别失败，请再说一次"
        return logged(ResolvedIntent(
            plan = IntentPlan.unknown(error),
            trace = IntentTrace(attempted = true, latencyMs = latencyMs, error = error,
                snippet = lastSnippet, source = IntentSource.UNRESOLVED),
        ))
    }

    private suspend fun attempt(client: LlmClient, request: LlmRequest,
                                sessionId: String, route: String): Attempt =
        when (val result = client.extract(request)) {
            is LlmResult.Failure -> {
                diagnostics.recordSafely(sessionId, "LLM_${route}_ERROR",
                    "latencyMs=${result.latencyMs} reason=${result.reason}")
                Attempt(null, result.reason, null, result.latencyMs)
            }
            is LlmResult.Success -> {
                diagnostics.recordSafely(sessionId, "LLM_${route}_RAW", result.content)
                val snippet = result.content.take(80)
                val parsed = IntentJson.parse(result.content)
                val plan = IntentResolver.resolve(parsed)
                if (plan?.source == IntentSource.LLM) {
                    Attempt(plan, null, snippet, result.latencyMs)
                } else {
                    val reason = if (parsed == null) "模型输出不是合法 JSON" else "模型未给出有效意图"
                    diagnostics.recordSafely(sessionId, "LLM_${route}_REJECTED", reason)
                    Attempt(null, reason,
                        snippet, result.latencyMs)
                }
            }
        }

    /**
     * 组装 prompt 上下文：今天 / 已存在物品名 / 全部在用库存 / 最近 3 条活跃操作。
     *
     * 读库失败**不阻断**：上下文缺失只会让模型少一点参考，不该让整句话记不上。
     */
    private suspend fun buildContext(): IntentPrompt.PromptContext = withContext(Dispatchers.IO) {
        val ops = runCatching { repository.recentActiveOperations(IntentPrompt.MAX_RECENT_OPS) }
            .getOrDefault(emptyList())
            .reversed() // DAO 是 seq 降序；prompt 里按时间从旧到新更好读
            .map { op ->
                IntentPrompt.RecentOp(
                    seq = op.seq,
                    type = op.type,
                    itemName = op.itemId?.let { id ->
                        runCatching { repository.fetchItem(id)?.name }.getOrNull()
                    },
                    locationCode = op.locationCode,
                    quantity = op.quantity,
                )
            }

        val names = runCatching {
            repository.observeAllItems().first().map { it.name }
        }.getOrDefault(emptyList())
        val inventory = runCatching { repository.inventoryForPrompt() }
            .getOrDefault(emptyList())

        IntentPrompt.PromptContext(
            today = today(),
            knownItems = names.take(IntentPrompt.MAX_KNOWN_ITEMS),
            recentOps = ops,
            inventory = inventory,
        )
    }

    private companion object {
        const val TAG = "IntentGateway"
    }
}
