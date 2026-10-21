package com.kuguan.app.data.voice

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 调试记录不参与业务判断；记录失败不能阻断语音录入。 */
fun interface SpeechDiagnosticRecorder {
    fun record(sessionId: String, stage: String, content: String)
}

object NoSpeechDiagnostics : SpeechDiagnosticRecorder {
    override fun record(sessionId: String, stage: String, content: String) = Unit
}

fun SpeechDiagnosticRecorder.recordSafely(sessionId: String, stage: String, content: String) {
    runCatching { record(sessionId, stage, content) }
}

/** 文件中的一行是完整 JSON；logcat 分段只为避开 Android 单行长度上限。 */
object SpeechDiagnosticFormat {
    fun line(timestampMs: Long, sessionId: String, stage: String, content: String): String =
        buildJsonObject {
            put("timeMs", timestampMs)
            put("sessionId", sessionId)
            put("stage", stage)
            put("content", content)
        }.toString()

    fun chunks(value: String, maxChars: Int = 700): List<String> {
        require(maxChars >= 2)
        if (value.isEmpty()) return listOf("")
        val parts = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + maxChars, value.length)
            if (end < value.length && Character.isHighSurrogate(value[end - 1])) end--
            parts += value.substring(start, end)
            start = end
        }
        return parts
    }
}

/** 仅调试包启用；保留当前与上一份各 2 MiB 的应用私有日志。 */
class FileSpeechDiagnostics(private val filesDir: File) : SpeechDiagnosticRecorder {
    private val current = File(filesDir, "speech-diagnostics.jsonl")
    private val previous = File(filesDir, "speech-diagnostics.previous.jsonl")
    private val lock = Any()

    override fun record(sessionId: String, stage: String, content: String) {
        try {
            val line = SpeechDiagnosticFormat.line(
                System.currentTimeMillis(), sessionId, stage,
                CredentialRedactor.redact(content),
            )
            synchronized(lock) {
                if (current.length() + line.toByteArray(Charsets.UTF_8).size + 1 > MAX_BYTES) {
                    if (previous.exists()) previous.delete()
                    if (current.exists() && !current.renameTo(previous)) {
                        throw IllegalStateException("诊断日志轮转失败")
                    }
                }
                current.appendText(line + "\n", Charsets.UTF_8)
            }
            val parts = SpeechDiagnosticFormat.chunks(line)
            parts.forEachIndexed { index, part ->
                Log.i(TAG, "session=$sessionId stage=$stage part=${index + 1}/${parts.size} $part")
            }
        } catch (error: Exception) {
            Log.w(TAG, "诊断记录失败：${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "SpeechDiag"
        private const val MAX_BYTES = 2L * 1024 * 1024
    }
}
