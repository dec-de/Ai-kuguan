package com.kuguan.app.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DeepSeek（中转站）的**协议细节**，纯函数、无网络、无 Android。
 *
 * 把它和 [DeepSeekLlmClient] 分开，是为了让「请求体到底带没带
 * `reasoning_effort:none`」这件事能被单测锁死 —— 这个参数一旦掉了，
 * 中位延迟会从 0.9s 变成 2.7s（思考模式），直接击穿 2.0s 端到端预算。
 */
object DeepSeekWire {

    /** 单次最多生成多少 token。8 行批量约 300 token，留一倍余量。 */
    const val MAX_TOKENS = 700

    /**
     * 拼端点。
     *
     * ⚠️ 路径必须是 `${BASE}/v1/chat/completions`。少了 `/v1` 会命中中转站的管理页
     * 并返回 **HTTP 200 + HTML**（不是 404）—— 只看状态码会以为调用成功。
     * 这里顺手容忍 base 已经带 `/v1` 的配置。
     */
    fun endpoint(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/v1")) base = base.removeSuffix("/v1")
        return "$base/v1/chat/completions"
    }

    fun requestBody(
        model: String,
        systemPrompt: String,
        userText: String,
        maxTokens: Int = MAX_TOKENS,
        provider: String = "",
    ): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", userText)
            })
        })
        put("temperature", 0)
        put("max_tokens", maxTokens)
        // 同时使用 DeepSeek 的显式开关和兼容参数；中转服务须支持或透传它们。
        put("reasoning_effort", "none")
        putJsonObject("thinking") { put("type", "disabled") }
        if (provider.isNotBlank()) putJsonObject("provider") {
            put("only", buildJsonArray { add(JsonPrimitive(provider.trim())) })
        }
        if (provider.isNotBlank()) putJsonObject("reasoning") { put("effort", "none") }
        putJsonObject("response_format") { put("type", "json_object") }
    }.toString()

    sealed interface Parsed {
        data class Ok(
            val content: String,
            val promptTokens: Int?,
            val completionTokens: Int?,
        ) : Parsed

        data class Err(val message: String) : Parsed
    }

    /**
     * 解析 HTTP 响应。**非 2xx 也当失败**，即使 body 看起来像 JSON ——
     * 中转站出错时可能用 200 回一段 HTML 或一段错误 JSON。
     */
    fun parseResponse(httpCode: Int, body: String): Parsed {
        if (httpCode !in 200..299) {
            return Parsed.Err("HTTP $httpCode：${body.take(120).replace('\n', ' ')}")
        }
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Parsed.Err("响应不是 JSON（可能是中转站管理页 HTML）")

        val choices = root["choices"] as? JsonArray
            ?: return Parsed.Err("响应结构异常：缺少 choices 数组")
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: return Parsed.Err("响应缺少 choices[0].message")
        val content = (message["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return Parsed.Err("响应缺少 choices[0].message.content")

        val usage = root["usage"] as? JsonObject
        return Parsed.Ok(
            content = content,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }
}
