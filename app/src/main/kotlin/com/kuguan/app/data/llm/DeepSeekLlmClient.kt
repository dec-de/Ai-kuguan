package com.kuguan.app.data.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DeepSeek 兼容接口实现（中转站或官方直连）—— OkHttp 阻塞调用包在 [Dispatchers.IO] 里。
 *
 * ## 三条硬约束
 *
 * 1. **`reasoning_effort:"none"`**：[DeepSeekWire.requestBody] 里写死。
 *    官方默认开思考模式，实测 2.73s，击穿 2.0s 端到端预算。
 * 2. **单次 10000ms 硬超时**：`callTimeout` 覆盖「连接 + 写 + 读」全程。
 *    超时 → [LlmResult.Failure] → 调用方尝试备用线路或显示失败原因。
 * 3. **同一时刻只允许一个在飞请求**：用户可能连按两次「按住说话」，
 *    第二个请求排队等第一个没有意义（前一句的解析结果已经过期），直接返回失败。
 *
 * ## 密钥
 *
 * 只在 `Authorization` 头里出现。日志只打印状态码、耗时、响应长度 ——
 * **任何分支都不打印 key、不打印完整 body**（中转站能看到全部物品名，但 logcat 不该有）。
 *
 * ## 连接复用
 *
 * 本类是进程内单例（[com.kuguan.app.di.AppContainer] 持有），OkHttp 的连接池随它存活。
 * 第二次调用通常省掉 TLS 握手（~200ms）。
 */
class DeepSeekLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val http: OkHttpClient = defaultClient(timeoutMs),
    private val provider: String = "",
) : LlmClient {

    private val inFlight = AtomicBoolean(false)

    override val configured: Boolean
        get() = configError == null

    override val configError: String?
        get() = when {
            apiKey.isBlank() -> "LLM 尚未配置 Key，请到配置 → 语音与 AI 填写"
            baseUrl.isBlank() -> "LLM 尚未配置地址，请到配置 → 语音与 AI 填写"
            model.isBlank() -> "LLM 尚未配置模型，请到配置 → 语音与 AI 填写"
            !validBaseUrl(baseUrl) -> "LLM 地址须为有效的 HTTPS 地址"
            else -> null
        }

    private val endpoint: String = DeepSeekWire.endpoint(baseUrl)

    /**
     * 建立连接（TCP+TLS）的单独上限。
     *
     * 原来写死 600ms，真机实测有一次 620ms 就失败 —— 移动网络冷启动握手经常要 700~900ms。
     * 上调到 1000ms：既能给 TLS 握手留余量，也不会让断连一直占用完整总超时。
     */
    private val connectTimeoutMs: Long = minOf(timeoutMs, 1000L)

    override suspend fun extract(request: LlmRequest): LlmResult = withContext(Dispatchers.IO) {
        configError?.let { return@withContext LlmResult.Failure(it, 0L) }
        if (request.systemPrompt.isBlank()) {
            return@withContext LlmResult.Failure("prompt 模板为空（assets/prompt/intent-v1.md 没打进包）", 0L)
        }
        if (request.userText.isBlank()) {
            return@withContext LlmResult.Failure("空文本，不调用", 0L)
        }

        // 并发保护：连按时第二次直接返回失败，不排队
        if (!inFlight.compareAndSet(false, true)) {
            return@withContext LlmResult.Failure("上一个请求还没回来（并发保护），请再说一次", 0L)
        }

        val startedAt = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000

        try {
            val body = DeepSeekWire.requestBody(model, request.systemPrompt, request.userText, provider = provider)
            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("Authorization", "Bearer $apiKey")
                .build()

            http.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val ms = elapsedMs()
                when (val parsed = DeepSeekWire.parseResponse(response.code, responseBody)) {
                    is DeepSeekWire.Parsed.Ok -> {
                        // 只记长度与耗时，不记内容（可能含用户物品名）
                        Log.i(TAG, "抽取成功 http=${response.code} ${ms}ms 输出=${parsed.content.length}字")
                        LlmResult.Success(
                            content = parsed.content,
                            latencyMs = ms,
                            promptTokens = parsed.promptTokens,
                            completionTokens = parsed.completionTokens,
                        )
                    }

                    is DeepSeekWire.Parsed.Err -> {
                        val reason = if (response.code !in 200..299) httpFailure(response.code)
                            else "LLM 响应格式异常，请检查服务地址或模型"
                        Log.w(TAG, "抽取失败 http=${response.code} ${ms}ms")
                        LlmResult.Failure(reason, ms)
                    }
                }
            }
        } catch (e: InterruptedIOException) {
            // 这里有两种超时，必须分开报：
            // - connectTimeout 抛 SocketTimeoutException("connect timed out")，也是 InterruptedIOException；
            // - callTimeout 抛 InterruptedIOException("timeout")。
            // 混成一句总超时会让人误以为 relay 慢，实际可能是连不上（真机 620ms 那次就是）。
            val ms = elapsedMs()
            val connectTimedOut = e.message?.contains("connect", ignoreCase = true) == true
            val reason = if (connectTimedOut) {
                "LLM 连接超时（>${connectTimeoutMs}ms）"
            } else {
                "LLM 超时（>${timeoutMs}ms）"
            }
            Log.w(TAG, "$reason 实际 ${ms}ms")
            LlmResult.Failure(reason, ms)
        } catch (e: IOException) {
            val ms = elapsedMs()
            Log.w(TAG, "抽取网络错误 ${ms}ms：${e.javaClass.simpleName}")
            LlmResult.Failure("LLM 网络错误：${e.javaClass.simpleName}", ms)
        } catch (e: Exception) {
            // 兜底：任何意外都不能让语音链路崩掉
            val ms = elapsedMs()
            Log.w(TAG, "抽取异常 ${ms}ms：${e.javaClass.simpleName}")
            LlmResult.Failure("LLM 调用异常：${e.javaClass.simpleName}", ms)
        } finally {
            inFlight.set(false)
        }
    }

    companion object {
        private const val TAG = "LlmClient"

        /**
         * 将单次请求总超时设为 10 秒，给较慢的模型响应留出时间。
         * 网络尾延迟可能继续超过上限，届时返回明确的失败原因。
         */
        const val DEFAULT_TIMEOUT_MS = 10000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun validBaseUrl(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            return url.scheme == "https" && url.host.isNotBlank() &&
                url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null
        }

        fun httpFailure(code: Int): String = when (code) {
            301, 302, 303, 307, 308 -> "LLM 服务发生重定向，已拒绝发送凭据到其他地址"
            401 -> "LLM Key 无效或已过期（401）"
            403 -> "LLM 没有该模型的访问权限（403）"
            429 -> "LLM 请求过多或额度不足（429）"
            400, 404, 422 -> "LLM 请求被拒绝，请检查模型和服务地址（$code）"
            else -> "LLM 服务返回错误（$code）"
        }

        /** 进程内复用的 OkHttp 客户端（连接池 + 线程池都随它复用）。 */
        fun defaultClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(timeoutMs, 1000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            // 失败立刻降级，避免重试进一步拖慢核对屏
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
    }
}
