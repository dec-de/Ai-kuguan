package com.kuguan.app.data.voice

import com.kuguan.app.data.asr.AsrClient
import com.kuguan.app.data.asr.AsrException
import com.kuguan.app.data.asr.AsrSession
import com.kuguan.app.data.audio.PcmRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 录音器抽象：让 [VoiceController] 能在 JVM 单测里跑（真实实现是 [PcmRecorder]）。 */
interface Recorder {
    fun start(onFrame: (ByteArray) -> Unit): Boolean
    /** @return 已录字节数 */
    fun stop(): Int
}

/** 语音链路状态（开发计划 4.3 的 IDLE/RECORDING/PROCESSING 三态 + 错误态）。 */
enum class VoiceState {
    /** 等待按下 */
    IDLE,

    /** 正在录音并实时推流 */
    RECORDING,

    /** 已松手，等识别结果 */
    PROCESSING,

    /** 上一次失败，等下一下按。message 在 [VoiceTrace.error] 里 */
    ERROR,
}

/**
 * 一次语音录制的诊断信息 —— **A9 调试面板**直接展示这些数字。
 *
 * 为什么要在界面上显示：2.0s 门禁是端到端指标，出问题时必须一眼看出
 * 是「ASR 慢」「建连慢」还是「我说得太短」，否则只能靠猜。
 */
data class VoiceTrace(
    val sessionId: String? = null,
    val text: String? = null,
    val error: String? = null,
    /** 录音时长（按下到松手） */
    val durationMs: Long = 0,
    /** 按下 → WebSocket 握手成功（含 TLS + run-task） */
    val connectMs: Long = 0,
    /** 松手 → 拿到终态文本。**M1-A 门禁量的是这个** */
    val finishMs: Long = 0,
    /** 按下 → 出字，端到端 */
    val totalMs: Long = 0,
    val bytes: Int = 0,
) {
    /** ASR 单项是否在预算内（M1-A：中位 ≤1.2s） */
    val asrWithinBudget: Boolean get() = error == null && finishMs in 1..ASR_BUDGET_MS

    companion object {
        const val ASR_BUDGET_MS = 1200L
    }
}

/**
 * 语音录制 → 流式识别 的编排（M1-A 的 A5）。
 *
 * ## 它只做三件事
 *
 * 1. **按下**：开录音 + **同时**建 WebSocket。两件事并行是关键 ——
 *    握手要 200~400ms，如果等握完再录，前 300ms 说的话就丢了。
 * 2. **录音中**：音频帧到达时，连接就绪就直接推，没就绪就先排队（最多 30 秒）。
 * 3. **松手**：停录音 → 判是不是误触 → `finish-task` → 等终态文本。
 *
 * ## 两条兜底
 *
 * - **最短 300ms**：点一下就松手不发请求。误触发一次请求既慢又会污染核对屏。
 * - **2.5s 硬超时**：M0.6 里有一条嘈杂样本 ASR 用了 2972ms，
 *    没有超时兜底的话用户会对着「处理中」一直等。超时也给一句人话。
 */
class VoiceController(
    private val asrClientFactory: (String) -> AsrClient,
    private val recorderFactory: () -> Recorder = { PcmRecorder() },
    private val diagnostics: SpeechDiagnosticRecorder = NoSpeechDiagnostics,
    private val audioCaptureFactory: (String) -> SpeechAudioCapture = { NoSpeechAudioCapture },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recorder: Recorder? = null
    private var audioCapture: SpeechAudioCapture = NoSpeechAudioCapture
    private var session: AsrSession? = null
    private var connectJob: Job? = null

    /** 连接没就绪时先排队的音频帧（保护性上限 30 秒）。 */
    private val pending = ArrayDeque<ByteArray>()
    private val pendingLock = Any()

    /** 已推给服务端的帧数（每帧 100ms）。用来自证「音频真的发出去了」。 */
    @Volatile
    private var pushedFrames = 0

    /** 握手期间排队、建连后补推的帧数。接近 0 说明握手够快，等于 6~7 说明拖了 600~700ms。 */
    @Volatile
    private var flushedFrames = 0

    private var pressAt = 0L
    private var connectedAt = 0L
    private var connectError: String? = null
    @Volatile private var cancelled = false
    @Volatile private var sessionId = ""

    /**
     * 按下。返回 false 表示**根本没能开始**（上一个还在处理 / 麦克风打不开），
     * 此时 [state] 已经是 ERROR，调用方不用再额外提示。
     */
    fun start(): Boolean {
        if (_state.value == VoiceState.RECORDING || _state.value == VoiceState.PROCESSING) {
            return false
        }

        cancelled = false
        sessionId = UUID.randomUUID().toString()
        audioCapture = runCatching { audioCaptureFactory(sessionId) }
            .getOrElse {
                diagnostics.recordSafely(sessionId, "AUDIO_ERROR", "open=${it.javaClass.simpleName}")
                NoSpeechAudioCapture
            }
        connectError = null
        connectedAt = 0L
        pressAt = System.currentTimeMillis()
        pushedFrames = 0
        flushedFrames = 0
        synchronized(pendingLock) { pending.clear() }

        val rec = recorderFactory()
        val capture = audioCapture
        val captureSessionId = sessionId
        var audioWriteFailed = false
        val ok = rec.start { frame ->
            if (!cancelled && captureSessionId == sessionId) {
                runCatching { capture.append(frame) }.onFailure { error ->
                    if (!audioWriteFailed) {
                        audioWriteFailed = true
                        diagnostics.recordSafely(captureSessionId, "AUDIO_ERROR", "write=${error.javaClass.simpleName}")
                    }
                }
                onFrame(frame, captureSessionId)
            }
        }
        if (!ok) {
            finishAudio()
            diagnostics.recordSafely(sessionId, "AUDIO_ERROR", "recorder_start_failed")
            _state.value = VoiceState.ERROR
            lastTrace = VoiceTrace(sessionId = sessionId, error = "麦克风打不开，看看是不是被别的 App 占着")
            return false
        }

        recorder = rec
        _state.value = VoiceState.RECORDING

        // 建连和录音并行：握手 200~400ms 正好被用户开口的时间吃掉
        connectJob = scope.launch {
            try {
                val s = asrClientFactory(captureSessionId).open()
                synchronized(pendingLock) {
                    if (cancelled || captureSessionId != sessionId) {
                        s.cancel()
                    } else {
                        session = s
                        connectedAt = System.currentTimeMillis()
                        // 把握手期间攒下的帧按顺序补推
                        while (pending.isNotEmpty()) {
                            s.push(pending.removeFirst())
                            pushedFrames++
                            flushedFrames++
                        }
                    }
                }
            } catch (t: Throwable) {
                if (!cancelled && captureSessionId == sessionId) {
                    connectError = (t as? AsrException)?.message ?: "连不上识别服务"
                }
            }
        }

        return true
    }

    private fun onFrame(frame: ByteArray, frameSessionId: String) {
        // ⚠️ 必须在**锁内**再读一次 session：先判空再进锁的写法有一个漏帧窗口 ——
        // 正好在「建连协程刷完队列并置 session」之后、「本帧入队」之前，
        // 这一帧会进队列但再也没人刷它（之后不再有 flush），用户少听 100ms。
        synchronized(pendingLock) {
            if (cancelled || frameSessionId != sessionId) return
            val s = session
            if (s != null) {
                s.push(frame)
                pushedFrames++
            } else if (pending.size < MAX_PENDING_FRAMES) {
                // 30 秒上限：每帧 100ms → 300 帧。防止服务端一直不回握手时把内存吃光
                pending.addLast(frame)
            }
        }
    }

    private fun finishAudio() {
        runCatching { audioCapture.finish() }
        audioCapture = NoSpeechAudioCapture
    }

    private fun abortAudio() {
        runCatching { audioCapture.abort() }
            .onFailure { diagnostics.recordSafely(sessionId, "AUDIO_ERROR", "abort=${it.javaClass.simpleName}") }
        audioCapture = NoSpeechAudioCapture
    }

    /** 上一次的完整诊断。UI 在 [VoiceState.PROCESSING] → 完成之间读它。 */
    @Volatile
    var lastTrace: VoiceTrace = VoiceTrace()
        private set

    /**
     * 松手。挂起直到拿到结果或超时。
     *
     * 必须在主线程安全的协程里调用（内部会切到 IO）。
     */
    suspend fun stop(): VoiceTrace = withContext(Dispatchers.IO) {
        val rec = recorder
        if (rec == null) {
            _state.value = VoiceState.IDLE
            return@withContext VoiceTrace(error = "没在录音").also { lastTrace = it }
        }

        val bytes = rec.stop()
        recorder = null
        finishAudio()
        val durationMs = bytes.toLong() * 1000L / BYTES_PER_SECOND
        diagnostics.recordSafely(sessionId, "AUDIO_META", "bytes=$bytes durationMs=$durationMs format=wav/pcm16/16000/mono")

        // 误触：点一下就松。**先判再等连接** —— 反过来的话，一次误触要白等 4 秒握手超时。
        if (bytes < MIN_BYTES) {
            // cancelled 必须在 cancel 之前置位：握手协程可能还没回来，
            // 回来后看到 cancelled 才会把 socket 一起关掉，否则连接会漏。
            cancelled = true
            session?.cancel()
            session = null
            _state.value = VoiceState.IDLE
            diagnostics.recordSafely(sessionId, "ASR_SKIPPED", "recording_too_short")
            return@withContext VoiceTrace(
                sessionId = sessionId,
                error = "太短了，按住多说一会儿",
                durationMs = durationMs,
                bytes = bytes,
            ).also { lastTrace = it }
        }

        // 握手可能还在路上，等它一下（它自己有 4s 超时，这里只是别让音频白等）
        connectJob?.join()
        connectJob = null

        val s = session
        if (s == null) {
            _state.value = VoiceState.ERROR
            diagnostics.recordSafely(sessionId, "ASR_ERROR", connectError ?: "connection_unavailable")
            return@withContext VoiceTrace(
                sessionId = sessionId,
                error = connectError ?: "连不上识别服务",
                durationMs = durationMs,
                bytes = bytes,
                totalMs = System.currentTimeMillis() - pressAt,
            ).also { lastTrace = it }
        }

        _state.value = VoiceState.PROCESSING
        val result = s.finish(RESULT_TIMEOUT_MS)
        session = null

        val trace = VoiceTrace(
            sessionId = sessionId,
            text = result.bestText,
            error = result.error,
            durationMs = durationMs,
            connectMs = if (connectedAt > 0) connectedAt - pressAt else 0,
            finishMs = result.elapsedMs,
            totalMs = System.currentTimeMillis() - pressAt,
            bytes = bytes,
        )
        _state.value = if (trace.error == null) VoiceState.IDLE else VoiceState.ERROR
        lastTrace = trace
        diagnostics.recordSafely(sessionId, "ASR_RESULT", trace.text.orEmpty())
        diagnostics.recordSafely(
            sessionId, "ASR_SENTENCES",
            buildString {
                append("count=${result.sentences.size} partial=${result.partial}")
                result.sentences.forEachIndexed { index, sentence ->
                    append("\n[$index] endTimeMs=${sentence.endTimeMs} text=${sentence.text}")
                }
            },
        )
        diagnostics.recordSafely(
            sessionId, "ASR_META",
            "durationMs=${trace.durationMs} connectMs=${trace.connectMs} finishMs=${trace.finishMs} " +
                "totalMs=${trace.totalMs} frames=$pushedFrames flushed=$flushedFrames error=${trace.error}",
        )
        Log.i(
            TAG,
            "ASR 完成 session=$sessionId 录=${trace.durationMs}ms 连=${trace.connectMs}ms 出字=${trace.finishMs}ms " +
                "端到端=${trace.totalMs}ms 帧=${pushedFrames}(补推$flushedFrames) " +
                "err=${trace.error}",
        )
        trace
    }

    /** 放弃这一次（页面销毁、用户切走）。 */
    fun cancel() {
        val wasRecording = _state.value == VoiceState.RECORDING
        cancelled = true
        recorder?.stop()
        recorder = null
        abortAudio()
        connectJob?.cancel()
        connectJob = null
        synchronized(pendingLock) {
            pending.clear()
            session?.cancel()
            session = null
        }
        _state.value = VoiceState.IDLE
        if (wasRecording) diagnostics.recordSafely(sessionId, "VOICE_CANCELLED", "gesture_or_navigation")
    }

    /** 真正销毁：取消协程作用域。 */
    fun release() {
        cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "VoiceController"

        /** 16kHz × 16bit × 单声道 = 32000 字节/秒 */
        private const val BYTES_PER_SECOND = 32_000

        /** 最短 300ms（含少量容差）。防误触。 */
        private const val MIN_BYTES = 32_000 * 300 / 1000

        /** 松手后的硬超时（M0.6 有 2972ms 的样本，不能无限等）。 */
        const val RESULT_TIMEOUT_MS = 2500L

        private const val MAX_PENDING_FRAMES = 300
    }
}
