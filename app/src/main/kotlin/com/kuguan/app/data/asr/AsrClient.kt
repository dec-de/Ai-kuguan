package com.kuguan.app.data.asr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 语音识别客户端接口。
 *
 * 只有**一个实现**：[AliyunAsrClient]。2026-10-18 定案「不留腾讯 fallback」，
 * 所以这里刻意**不**做供应商枚举，也不做适配层 —— 多一层抽象只会在只有一个实现时增加阅读成本。
 * 保留接口是为了让测试能塞假实现（真机跑不到网络时也能测状态机）。
 *
 * ## 为什么是「流式会话」而不是「一段音频进、一句文字出」
 *
 * 实时识别要在说话的同时推流。等录完再传，等于把音频时长再加到延迟上：
 * 2 秒的话多 2 秒，2.0s 门禁直接爆掉。所以接口长成 open → push → finish。
 */
interface AsrClient {

    /**
     * 建连并跑完 `run-task` 握手。
     *
     * @throws AsrException 握手失败（密钥错、模型名错、网络不通）
     */
    suspend fun open(): AsrSession
}

/** 一次识别的会话。用完必须 [AsrSession.finish] 或 [AsrSession.cancel]。 */
interface AsrSession {

    /** 推一段 16k/mono/pcm16 裸数据。可在任意线程调用。 */
    fun push(pcm: ByteArray)

    /**
     * 结束推流并等最终结果。
     *
     * @param timeoutMs 服务端迟迟不回时的兜底，超时返回 `error` 而不是抛异常 ——
     *   这个 App 的调用点都在 UI 手势之后，抛异常只能变成「按了没反应」。
     */
    suspend fun finish(timeoutMs: Long = 3000L): AsrResult

    /** 放弃这次识别（误触、权限被撤、Activity 销毁）。不发 finish-task，直接断。 */
    fun cancel()
}

/**
 * 识别结果。
 *
 * @param text 最终文本（多句拼接；分句以 `sentence_end=true` 为准）
 * @param partial 最后一段还没 end 的文本。服务端偶尔不回 end，丢掉会让「明明说了却没反应」
 * @param elapsedMs 从 [AsrSession.finish] 调用到拿到终态文本的耗时 —— M1-A 门禁量的是这个
 */
data class AsrResult(
    val text: String,
    val partial: String = "",
    val elapsedMs: Long = 0,
    val error: String? = null,
    /** 服务端标记 sentence_end 的原始分句及时间，便于追查断句。 */
    val sentences: List<AsrSentence> = emptyList(),
) {
    /** 真正拿到的完整文本：终态优先，没终态就退回最后一段临时结果。 */
    val bestText: String get() = text.ifBlank { partial }

    /** 是否可用（有文本且没有错误）。 */
    val ok: Boolean get() = error == null && bestText.isNotBlank()
}

data class AsrSentence(val text: String, val endTimeMs: Long)

/** ASR 侧的失败。message 直接给用户看，所以写成人话。 */
class AsrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 阿里百炼 `qwen-audio-3.1-asr-flash-message` 的 WebSocket 实现。
 *
 * 协议是**三段式**（见 `tools/m06-asr-bench.js`，那是本实现的对照基线）：
 * ```
 * → run-task      {task_group:audio, task:asr, function:recognition, parameters:{...}}
 * ← task-started  之后才能推二进制音频
 * → (binary frames)
 * → finish-task   松手时发
 * ← result-generated × N  {sentence:{text, sentence_end, end_time}}
 * ← task-finished 终态
 * ```
 *
 * ## 关键参数（都有实测依据，别随手改）
 *
 * - `max_sentence_silence = 300`：官方默认 **1300ms**，是「松手后半天不出字」的元凶。
 *   实测调到 200ms 后 1409→829ms 且文本不变，这里取 300ms 留一点冗余。
 * - 不发送 `vocabulary`。K80 同一录音的复测中，高权重库存词会凭空进入识别文本。
 * - 不设 `input.context`（实测 20 条一个字没变）。
 */
class AliyunAsrClient(
    private val wsUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxSentenceSilence: Int = 300,
    private val client: OkHttpClient = defaultClient(),
    /** Full service events for case review; failures must not affect recognition. */
    private val rawEventSink: (String) -> Unit = {},
) : AsrClient {

    override suspend fun open(): AsrSession {
        if (apiKey.isBlank() || model.isBlank())
            throw AsrException("语音识别尚未配置，请到配置 → 语音与 AI 填写 Key 和模型")
        val uri = runCatching { URI(wsUrl) }.getOrNull()
        if (uri?.scheme != "wss" || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
            throw AsrException("语音识别地址须为有效的 wss:// 地址")

        val session = AliyunSession(UUID.randomUUID().toString())
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $apiKey")
            .build()

        session.attach(client.newWebSocket(request, session.listener))

        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) { session.awaitStarted() }
        } catch (t: TimeoutCancellationException) {
            session.cancel()
            throw AsrException("连不上识别服务（${HANDSHAKE_TIMEOUT_MS}ms 内没握手成功）")
        } catch (t: Throwable) {
            session.cancel()
            throw if (t is AsrException) t else AsrException("连接识别服务失败，请检查网络与地址", t)
        }

        return session
    }

    internal fun runTask(taskId: String): String {
        val parameters = JSONObject()
            .put("format", "pcm")
            .put("sample_rate", 16000)
            .put("max_sentence_silence", maxSentenceSilence)
            // 近场手持（20~30cm）。官方默认 far_field_meeting_16k 是会议室远场模型，
            // 手持场景用它会过度降噪，把编号里的高频辅音一起吃掉。
            // vad_model **不设**，走官方默认 `far_field_meeting_16k`。
            //
            // 2026-10-18 真机 A/B（K70，26 条 m06 录音回放，同一条链路）：
            //   near_meeting_16k ：25/26（#26 嘈杂样本只吐出「七」，编号全丢）
            //   默认 far_field   ：第二轮 26/26，两轮合计 51/52（唯一失手是 #03 一次整句误认）
            // 延迟中位两者都是 ~185ms，没有差别。
            // 更重要的理由：M0.6 的 208 次离线基线用的就是默认值，跟基线同参，
            // 真机数据才能和离线数据直接比；换模型会引入一个说不清的系统性差异。
        return JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("action", "run-task")
                    .put("task_id", taskId)
                    .put("streaming", "duplex"),
            )
            .put(
                "payload",
                JSONObject()
                    .put("task_group", "audio")
                    .put("task", "asr")
                    .put("function", "recognition")
                    .put("model", model)
                    .put("parameters", parameters)
                    .put("input", JSONObject()),
            )
            .toString()
    }

    /**
     * 一个会话 = 一次 WebSocket = 一次按下到松手。
     *
     * 握手事件和识别结果**必须由同一个 listener 收**：OkHttp 每个 WebSocket 只挂一个 listener，
     * 换 listener 这种操作不存在。所以会话对象自己持有全部状态，listener 只是它的一个字段。
     */
    private inner class AliyunSession(private val taskId: String) : AsrSession {

        private val started = CompletableDeferred<Unit>()
        private val finished = CompletableDeferred<AsrResult>()
        private val text = StringBuilder()
        private val sentences = mutableListOf<AsrSentence>()

        @Volatile
        private var partial = ""

        @Volatile
        private var ws: WebSocket? = null

        /** `finish()` 被调用的时刻，用来算「松手到出字」的耗时。 */
        @Volatile
        private var finishAt = 0L

        private val elapsedFromFinish: Long
            get() = if (finishAt == 0L) 0L else System.currentTimeMillis() - finishAt

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                webSocket.send(runTask(taskId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { rawEventSink(text) }
                val event = try {
                    JSONObject(text)
                } catch (t: Throwable) {
                    return
                }
                when (event.optJSONObject("header")?.optString("event")) {
                    "task-started" -> if (!started.isCompleted) started.complete(Unit)

                    "task-failed" -> {
                        // Server text is untrusted and may echo credentials. Keep it out of UI/log/ZIP.
                        val detail = "请检查 Key、模型及服务权限"
                        if (!started.isCompleted) {
                            started.completeExceptionally(AsrException("识别服务拒绝了请求：$detail"))
                        }
                        complete(
                            AsrResult(
                                text = this@AliyunSession.text.toString(),
                                partial = partial,
                                elapsedMs = elapsedFromFinish,
                                error = "识别服务报错：$detail",
                                sentences = sentences.toList(),
                            )
                        )
                    }

                    "result-generated" -> {
                        val sentence = event.optJSONObject("payload")
                            ?.optJSONObject("output")
                            ?.optJSONObject("sentence")
                        val t = sentence?.optString("text").orEmpty()
                        if (t.isBlank()) return
                        if (sentence?.optBoolean("sentence_end") == true) {
                            this@AliyunSession.text.append(t)
                            sentences += AsrSentence(t, sentence.optLong("end_time", -1L))
                            partial = ""
                        } else {
                            partial = t
                        }
                    }

                    "task-finished" -> complete(
                        AsrResult(
                            text = this@AliyunSession.text.toString(),
                            partial = partial,
                            elapsedMs = elapsedFromFinish,
                            sentences = sentences.toList(),
                        )
                    )
                }
            }

            // 服务端不会发二进制，覆写掉以免「无法解析的帧」被当成错误
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = when {
                    response?.code == 401 -> "密钥无效或被拒（401）"
                    response?.code == 403 -> "密钥没有该模型的权限（403）"
                    response != null -> "连接失败（${response.code}）"
                    else -> "识别服务网络中断，请检查网络或地址"
                }
                if (!started.isCompleted) started.completeExceptionally(AsrException(reason, t))
                complete(
                    AsrResult(
                        text = this@AliyunSession.text.toString(),
                        partial = partial,
                        elapsedMs = elapsedFromFinish,
                        error = reason,
                        sentences = sentences.toList(),
                    )
                )
            }
        }

        fun attach(socket: WebSocket) {
            ws = socket
        }

        suspend fun awaitStarted() {
            started.await()
        }

        private fun complete(result: AsrResult) {
            if (!finished.isCompleted) finished.complete(result)
        }

        override fun push(pcm: ByteArray) {
            if (finished.isCompleted || pcm.isEmpty()) return
            ws?.send(pcm.toByteString())
        }

        override suspend fun finish(timeoutMs: Long): AsrResult {
            if (finishAt == 0L) finishAt = System.currentTimeMillis()
            ws?.send(
                JSONObject()
                    .put(
                        "header",
                        JSONObject()
                            .put("action", "finish-task")
                            .put("task_id", taskId)
                            .put("streaming", "duplex"),
                    )
                    .put("payload", JSONObject().put("input", JSONObject()))
                    .toString()
            )

            return try {
                withTimeout(timeoutMs) { finished.await() }
            } catch (t: TimeoutCancellationException) {
                cancel()
                AsrResult(
                    text = text.toString(),
                    partial = partial,
                    elapsedMs = elapsedFromFinish,
                    error = "等结果超时（${timeoutMs}ms）",
                    sentences = sentences.toList(),
                )
            }
        }

        override fun cancel() {
            complete(AsrResult(text = text.toString(), partial = partial,
                error = "已取消", sentences = sentences.toList()))
            try {
                ws?.cancel()
            } catch (ignore: Throwable) {
            }
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 4000L

        /** 共享连接池：每次按下都新建客户端会让 TLS 握手白白多花 100~200ms。 */
        private var sharedClient: OkHttpClient? = null

        fun defaultClient(): OkHttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接，读超时交给业务层
                    .writeTimeout(4, TimeUnit.SECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build()
                    .also { sharedClient = it }
            }
    }
}
