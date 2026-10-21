package com.kuguan.app.data.voice

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RetryableSpeech(val sessionId: String, val text: String)

/** One private WAV and one JSONL file per speech session, retained until the user clears them. */
class SpeechCaseStore(
    private val filesDir: File,
    private val sensitiveValues: () -> Set<String> = { emptySet() },
) : SpeechDiagnosticRecorder {
    val directory: File = File(filesDir, DIRECTORY_NAME)
    private val lock = Any()

    override fun record(sessionId: String, stage: String, content: String) {
        val id = safeId(sessionId)
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create speech case directory" }
            File(directory, "$id.jsonl").appendText(
                SpeechDiagnosticFormat.line(
                    System.currentTimeMillis(), id, stage,
                    CredentialRedactor.redact(content, sensitiveValues()),
                ) + "\n",
                Charsets.UTF_8,
            )
        }
    }

    /** 只恢复最近一次完整的 LLM 失败；绝不从旧失败或已经写库的会话重试。 */
    fun latestRetryable(): RetryableSpeech? = synchronized(lock) {
        val latest = directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jsonl") }
            ?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: return@synchronized null
        val id = latest.name.removeSuffix(".jsonl")
        if (!SAFE_ID.matches(id)) return@synchronized null
        var text: String? = null
        var llmFailed = false
        var unknown = false
        var unsafe = false
        latest.forEachLine(Charsets.UTF_8) { line ->
            val entry = runCatching {
                val objectValue = Json.parseToJsonElement(line).jsonObject
                objectValue["stage"]?.jsonPrimitive?.content to
                    objectValue["content"]?.jsonPrimitive?.content.orEmpty()
            }.getOrNull()
                ?: return@forEachLine
            val stage = entry.first ?: return@forEachLine
            val content = entry.second
            when {
                stage == "ASR_RESULT" || stage == "RETRY_TEXT" -> text = content
                stage.startsWith("LLM_") &&
                    (stage.endsWith("_ERROR") || stage.endsWith("_REJECTED")) -> llmFailed = true
                stage == "PLAN" -> unknown = content.startsWith("kind=UNKNOWN ")
                stage == "WRITE_RESULT" || stage == "RETRY_DISMISSED" -> unsafe = true
            }
        }
        text?.takeIf { it.isNotBlank() && llmFailed && unknown && !unsafe }
            ?.let { RetryableSpeech(id, it) }
    }

    fun startAudio(sessionId: String): SpeechAudioCapture {
        val id = safeId(sessionId)
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create speech case directory" }
            return WavCapture(File(directory, "$id.wav"))
        }
    }

    fun clear(): Long = synchronized(lock) {
        val bytes = directory.totalFileBytes()
        if (directory.exists()) check(directory.deleteRecursively()) { "Cannot clear speech case files" }
        // Older debug builds used two files at the root of filesDir.
        listOf("speech-diagnostics.jsonl", "speech-diagnostics.previous.jsonl").forEach { name ->
            val file = File(filesDir, name)
            if (file.exists()) check(file.delete()) { "Cannot clear $name" }
        }
        bytes
    }

    fun sizeBytes(): Long = synchronized(lock) {
        directory.totalFileBytes() + listOf(
            "speech-diagnostics.jsonl", "speech-diagnostics.previous.jsonl",
        ).sumOf { File(filesDir, it).takeIf(File::isFile)?.length() ?: 0L }
    }

    /** Stream private evidence into a user-selected ZIP; never add it to automatic inventory backups. */
    fun exportTo(output: OutputStream) = synchronized(lock) {
        ZipOutputStream(output).use { zip ->
            directory.listFiles()?.filter(File::isFile)?.sortedBy(File::getName)?.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                if (file.extension == "jsonl") {
                    zip.write(CredentialRedactor.redact(file.readText(Charsets.UTF_8), sensitiveValues())
                        .toByteArray(Charsets.UTF_8))
                } else {
                    file.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
            listOf("speech-diagnostics.jsonl", "speech-diagnostics.previous.jsonl").forEach { name ->
                val file = File(filesDir, name)
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(CredentialRedactor.redact(file.readText(Charsets.UTF_8), sensitiveValues())
                        .toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    companion object {
        const val DIRECTORY_NAME = "speech-cases"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
        private fun safeId(value: String): String {
            require(SAFE_ID.matches(value)) { "Invalid speech session ID" }
            return value
        }
    }
}

interface SpeechAudioCapture {
    fun append(frame: ByteArray)
    fun finish()
    fun abort() = finish()
}

object NoSpeechAudioCapture : SpeechAudioCapture {
    override fun append(frame: ByteArray) = Unit
    override fun finish() = Unit
    override fun abort() = Unit
}

/** 16 kHz, mono, PCM16. A valid header is written even if a session is empty or interrupted. */
internal class WavCapture(private val file: File) : SpeechAudioCapture {
    private val output = RandomAccessFile(file, "rw")
    private var bytes = 0L
    private var closed = false

    init {
        output.setLength(0)
        writeHeader(0)
    }

    @Synchronized
    override fun append(frame: ByteArray) {
        if (closed || frame.isEmpty()) return
        require(bytes + frame.size <= Int.MAX_VALUE.toLong()) { "WAV too large" }
        output.write(frame)
        bytes += frame.size
    }

    @Synchronized
    override fun finish() {
        if (closed) return
        closed = true
        try {
            output.seek(0)
            writeHeader(bytes.toInt())
            output.fd.sync()
        } finally {
            output.close()
        }
    }

    @Synchronized
    override fun abort() {
        if (!closed) {
            closed = true
            output.close()
        }
        if (file.exists()) check(file.delete()) { "Cannot delete cancelled speech audio" }
    }

    private fun writeHeader(dataBytes: Int) {
        output.writeBytes("RIFF")
        intLE(36 + dataBytes)
        output.writeBytes("WAVEfmt ")
        intLE(16)
        shortLE(1)
        shortLE(1)
        intLE(16_000)
        intLE(32_000)
        shortLE(2)
        shortLE(16)
        output.writeBytes("data")
        intLE(dataBytes)
    }

    private fun shortLE(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun intLE(value: Int) {
        shortLE(value)
        shortLE(value ushr 16)
    }
}

internal fun File.totalFileBytes(): Long = when {
    isFile -> length()
    isDirectory -> listFiles()?.sumOf { it.totalFileBytes() } ?: 0L
    else -> 0L
}
