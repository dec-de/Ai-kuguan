package com.kuguan.app.data.asr

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * **仅调试用**：把 wav 文件按 100ms 一帧喂给真实的 ASR 客户端，模拟「按住说话」。
 *
 * ## 为什么需要它（这不是给用户用的功能）
 *
 * M1-A 的门禁是「26 条 `m06-audio` 录音回放，编号还原 ≥95%，松手→出字中位 ≤1.2s」。
 * 这个门禁必须在**真机**上量，因为 PC 上的 curl/Node 结果不含手机的网络栈
 * （TLS、DNS、弱网、HyperOS 的后台限制都不一样）。
 *
 * 而「让 Owen 念 26 句」在每次改动后都要重来，成本太高。回放把这件事变成一次点击：
 * 26 条 wav 随 **debug 包**打进 `assets/replay`，点一下「ASR 回放」，logcat 里就是逐条结果。
 *
 * 为什么走 assets 而不是推文件到 `/sdcard`：真机实测 app 读不到 `adb push` 进
 * `Android/data/<pkg>/files/` 的文件（目录属主是 shell，FUSE 合成的权限判定不放行），
 * `listFiles()` 直接返回空。assets 是编译期内嵌，没有这个问题，而且**谁编译谁就有样本**，
 * 不需要额外文档告诉人「先把 26 个 wav 推进去」。
 *
 * ## 它走的是**同一条**生产链路
 *
 * 用的就是 `AsrClient` + 同一个模型 + 同一套参数（mss=300、不带热词），
 * 唯一不同的是音频来源（文件 vs 麦克风）。所以这里量出来的延迟与线上是可比的下界
 * ——线上还多一段「真实开口到说完」的时间。
 *
 * release 包里不会被调用（入口在 `BuildConfig.DEBUG` 分支内）。
 */
object AsrReplay {

    private const val TAG = "AsrReplay"

    /** 100ms @16k/mono/pcm16。与线上推流节奏一致，否则延迟不可比。 */
    private const val FRAME_BYTES = 3200

    private const val FRAME_INTERVAL_MS = 100L

    /**
     * 同一时刻只允许一批回放。
     *
     * 真机实测：连点三次会让三条链路并发打服务端，末尾几条直接
     * 「4000ms 内没握手成功」—— 那不是模型的问题，是自己把自己限流了。
     * 有了这道闸，连点只会得到一句「已经在跑」。
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 回放一批 wav。
     *
     * @param assets App 的 AssetManager
     * @param dir assets 里的子目录（默认 `replay`）
     * @param clientFactory 生产用的客户端工厂
     * @param onLine 每条结果回调（一般是 `Log.i`），便于调用方同时显示在界面上
     */
    suspend fun run(
        assets: AssetManager,
        dir: String = "replay",
        clientFactory: () -> AsrClient,
        onLine: (String) -> Unit,
        /** 非空时把识别文本继续送进「解析 + 写库」，用来验证 M1-B 的完整链路。 */
        onText: (suspend (String) -> Unit)? = null,
    ) {
        if (!running.compareAndSet(false, true)) {
            onLine("REPLAY 已经在跑了，等它结束")
            return
        }
        try {
            runInternal(assets, dir, clientFactory, onLine, onText)
        } finally {
            running.set(false)
        }
    }

    private suspend fun runInternal(
        assets: AssetManager,
        dir: String,
        clientFactory: () -> AsrClient,
        onLine: (String) -> Unit,
        onText: (suspend (String) -> Unit)?,
    ) {
        val files = assets.list(dir)?.filter { it.endsWith(".wav", true) }?.sorted().orEmpty()
        if (files.isEmpty()) {
            onLine("REPLAY assets/$dir 里没有 wav")
            return
        }
        onLine("REPLAY 开始，共 ${files.size} 条")
        var ok = 0
        var fail = 0

        files.forEachIndexed { index, name ->
            val pcm = readPcm(assets, "$dir/$name")
            if (pcm == null) {
                fail++
                onLine("REPLAY ${index + 1}/${files.size} $name 读取失败（不是 16k/mono/pcm16 的 wav）")
                return@forEachIndexed
            }

            val session = try {
                clientFactory().open()
            } catch (t: Throwable) {
                fail++
                onLine("REPLAY ${index + 1}/${files.size} $name 建连失败：${t.message}")
                return@forEachIndexed
            }

            // 按 100ms 节奏喂，和真实推流一致
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + FRAME_BYTES, pcm.size)
                session.push(pcm.copyOfRange(offset, end))
                offset = end
                delay(FRAME_INTERVAL_MS)
            }

            val result = session.finish(4000)
            val line = buildString {
                append("REPLAY ${index + 1}/${files.size} $name")
                append(" 出字=${result.elapsedMs}ms")
                append(" 音频=${pcm.size * 1000 / 32000}ms")
                append(" err=${result.error ?: "-"}")
                append(" text=")
                append(result.bestText.ifBlank { "（空）" })
            }
            if (result.ok) {
                ok++
                // 全链路模式：把文本继续走「L1 + 闸门 + 写库」，和真说话完全同一条路
                if (onText != null && result.bestText.isNotBlank()) {
                    runCatching { onText(result.bestText) }
                        .onFailure { onLine("REPLAY $name 写库异常：${it.message}") }
                }
            } else {
                fail++
            }
            onLine(line)
            // 每条之间歇一下，别把服务端 QPS 打满
            delay(200)
        }

        onLine("REPLAY 结束：成功 $ok 条 / 失败 $fail 条")
        Log.i(TAG, "done ok=$ok fail=$fail")
    }

    /**
     * 取出 wav 的 PCM 数据（跳过 RIFF 头）。
     *
     * 只接受 16kHz 单声道 16bit —— 其他格式直接返回 null。
     * 不做重采样：**采样率错了识别结果一定是错的**，静默转换只会让门禁数据失真。
     */
    private fun readPcm(assets: AssetManager, path: String): ByteArray? {
        val bytes = runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() ?: return null
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            if (id == "fmt ") {
                val sampleRate = le16(bytes, pos + 12)
                val channels = le16(bytes, pos + 10)
                val bits = le16(bytes, pos + 22)
                if (sampleRate != 16000 || channels != 1 || bits != 16) {
                    Log.w(TAG, "$path 格式不符：${sampleRate}Hz/${channels}ch/${bits}bit")
                    return null
                }
            }
            if (id == "data") {
                val end = minOf(pos + 8 + size, bytes.size)
                return bytes.copyOfRange(pos + 8, end)
            }
            pos += 8 + size + (size % 2)
        }
        return null
    }

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        le16(b, at) or (le16(b, at + 2) shl 16)
}
