package com.kuguan.app.data.llm

/** 一次抽取请求：system prompt 已由 `IntentPrompt` 渲染好，用户原话单独放。 */
data class LlmRequest(
    val systemPrompt: String,
    val userText: String,
)

/**
 * LLM 调用的结果。
 *
 * **失败不抛异常**：调用方（`IntentGateway`）会尝试备用线路，最终显示失败原因。
 */
sealed interface LlmResult {
    data class Success(
        val content: String,
        val latencyMs: Long,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
    ) : LlmResult

    data class Failure(
        val reason: String,
        val latencyMs: Long,
    ) : LlmResult
}

/**
 * 意图抽取客户端（M2）。
 *
 * 只负责「把 prompt 发出去、把文本拿回来」，**不解析业务 JSON** ——
 * 解析与校验在 `domain/voice/IntentJson`（纯 JVM，可单测）。
 */
interface LlmClient {
    /** false = 没配密钥/地址，调用方不向该线路发请求。 */
    val configured: Boolean

    /** 为什么不可用（给 debug 面板看的人话，**不含密钥**）。 */
    val configError: String?

    suspend fun extract(request: LlmRequest): LlmResult
}
