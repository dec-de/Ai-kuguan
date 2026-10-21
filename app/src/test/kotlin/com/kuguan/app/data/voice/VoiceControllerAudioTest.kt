package com.kuguan.app.data.voice

import com.kuguan.app.data.asr.AsrClient
import com.kuguan.app.data.asr.AsrResult
import com.kuguan.app.data.asr.AsrSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VoiceControllerAudioTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun capturedFramesAndAsrResultShareSession() = runBlocking {
        val store = SpeechCaseStore(temp.root)
        val frame = ByteArray(9_600) { (it % 100).toByte() }
        val controller = VoiceController(
            asrClientFactory = { FakeAsr() },
            recorderFactory = { OneFrameRecorder(frame) },
            diagnostics = store,
            audioCaptureFactory = store::startAudio,
        )
        try {
            assertTrue(controller.start())
            val trace = controller.stop()
            assertEquals("识别文本", trace.text)
            val id = trace.sessionId!!
            assertEquals(44 + frame.size.toLong(), File(store.directory, "$id.wav").length())
            val log = File(store.directory, "$id.jsonl").readText()
            assertTrue(log.contains("ASR_RESULT"))
            assertTrue(log.contains("识别文本"))
        } finally { controller.release() }
    }

    @Test fun audioStoreFailureDoesNotBlockRecognition() = runBlocking {
        val controller = VoiceController(
            asrClientFactory = { FakeAsr() },
            recorderFactory = { OneFrameRecorder(ByteArray(9_600)) },
            audioCaptureFactory = { error("disk full") },
        )
        try {
            assertTrue(controller.start())
            assertEquals("识别文本", controller.stop().text)
        } finally { controller.release() }
    }

    @Test fun cancelStopsRecorderAndAsrWithoutFinishingOrKeepingAudio() = runBlocking {
        val store = SpeechCaseStore(temp.root)
        val stopped = AtomicBoolean(false)
        val pushed = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val session = object : AsrSession {
            override fun push(pcm: ByteArray) { pushed.complete(Unit) }
            override suspend fun finish(timeoutMs: Long): AsrResult {
                finished.set(true)
                return AsrResult(text = "不应识别")
            }
            override fun cancel() { cancelled.set(true) }
        }
        val controller = VoiceController(
            asrClientFactory = { object : AsrClient {
                override suspend fun open(): AsrSession = session
            } },
            recorderFactory = { object : Recorder {
                override fun start(onFrame: (ByteArray) -> Unit): Boolean {
                    onFrame(ByteArray(9_600))
                    return true
                }
                override fun stop(): Int { stopped.set(true); return 9_600 }
            } },
            diagnostics = store,
            audioCaptureFactory = store::startAudio,
        )
        try {
            assertTrue(controller.start())
            pushed.await()
            controller.cancel()
            assertEquals(VoiceState.IDLE, controller.state.value)
            assertTrue(stopped.get())
            assertTrue(cancelled.get())
            assertFalse(finished.get())
            val id = controller.lastTrace.sessionId ?: store.directory.listFiles()!!
                .single { it.name.endsWith(".jsonl") }.name.removeSuffix(".jsonl")
            assertFalse(File(store.directory, "$id.wav").exists())
            assertTrue(File(store.directory, "$id.jsonl").readText().contains("VOICE_CANCELLED"))
        } finally { controller.release() }
    }

    @Test fun lateConnectionFromCancelledRecordingCannotAttachToNextRecording() = runBlocking {
        val opening = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldCancelled = AtomicBoolean(false)
        val oldSession = object : AsrSession {
            override fun push(pcm: ByteArray) = Unit
            override suspend fun finish(timeoutMs: Long) = AsrResult(text = "旧会话")
            override fun cancel() { oldCancelled.set(true) }
        }
        var opens = 0
        val controller = VoiceController(
            asrClientFactory = { object : AsrClient {
                override suspend fun open(): AsrSession {
                    opens++
                    return if (opens == 1) {
                        opening.complete(Unit)
                        withContext(NonCancellable) { releaseOld.await() }
                        oldSession
                    } else FakeAsr().open()
                }
            } },
            recorderFactory = { OneFrameRecorder(ByteArray(9_600)) },
        )
        try {
            assertTrue(controller.start())
            opening.await()
            controller.cancel()
            assertTrue(controller.start())
            releaseOld.complete(Unit)
            withTimeout(3_000) {
                while (!oldCancelled.get()) delay(10)
            }
            assertEquals("识别文本", controller.stop().text)
        } finally { controller.release() }
    }

    private class OneFrameRecorder(private val frame: ByteArray) : Recorder {
        override fun start(onFrame: (ByteArray) -> Unit): Boolean {
            onFrame(frame)
            return true
        }
        override fun stop(): Int = frame.size
    }

    private class FakeAsr : AsrClient {
        override suspend fun open(): AsrSession = object : AsrSession {
            override fun push(pcm: ByteArray) = Unit
            override suspend fun finish(timeoutMs: Long) = AsrResult(text = "识别文本")
            override fun cancel() = Unit
        }
    }
}
