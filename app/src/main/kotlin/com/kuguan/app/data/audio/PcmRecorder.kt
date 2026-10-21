package com.kuguan.app.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 麦克风录音：**16kHz / 单声道 / 16bit PCM**。
 *
 * 这三个参数不是可选项 —— 阿里 `qwen-audio-3.1-asr-flash-message` 的实时接口
 * 只接受 16kHz 单声道裸 PCM。想「先录成 m4a 再转」是不行的：实时识别要在说话的同时推流，
 * 录完再转至少多出一个音频时长（2 秒的话就多 2 秒，直接击穿 2.0s 门禁）。
 *
 * ## 按 100ms 帧回调
 *
 * 帧大小 = 16000 × 2 字节 × 0.1s = **3200 字节**。100ms 是 M0.5/M0.6 横测用的节奏，
 * 换节奏会让延迟基线不可比。太小（如 20ms）会让 WebSocket 每秒发 50 条小包，
 * 4G 下反而更慢；太大（如 500ms）会把「说完到最后一个包发出去」的尾巴拉长。
 *
 * ## 一轮一个实例
 *
 * [start] 建立 `AudioRecord`，[stop] 停线程并 `release()`。**每次按下都新建**，
 * 复用实例在部分机型上会出现「第二次录音静音」—— 排查成本远高于建实例的开销。
 */
class PcmRecorder : com.kuguan.app.data.voice.Recorder {

    companion object {
        private const val TAG = "PcmRecorder"

        /** 阿里实时 ASR 硬约束：16kHz 单声道。 */
        const val SAMPLE_RATE = 16000

        /** 一帧 100ms 的字节数（16bit = 2 字节/采样点）。 */
        const val FRAME_BYTES = SAMPLE_RATE * 2 / 10

        /** 停止时最多等录音线程这么久，避免 `stop()` 把 UI 卡住。 */
        private const val JOIN_TIMEOUT_MS = 500L
    }

    @Volatile
    private var recording = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** 已经录到的字节数（用来实现「最短 300ms 防误触」）。 */
    @Volatile
    var bytesRecorded: Int = 0
        private set

    /** 正在录。 */
    val isRecording: Boolean get() = recording

    /**
     * 开始录音。**同步返回**是否成功启动（权限没给、被别的 App 占用都返回 false），
     * 帧数据在独立线程里通过 [onFrame] 回调。
     */
    @SuppressLint("MissingPermission") // 调用方（HomeScreen）已先确保 RECORD_AUDIO 已授予
    override fun start(onFrame: (ByteArray) -> Unit): Boolean {
        if (recording) return false

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "getMinBufferSize 返回 $minBuffer，设备不支持 16k/mono/pcm16")
            return false
        }

        val bufferSize = maxOf(minBuffer, FRAME_BYTES * 4)
        val rec = try {
            AudioRecord(
                // MIC 在近场（手持 20~30cm）够用；VOICE_COMMUNICATION 会开回声消除，
                // 反而把高频削弱，实测「七」「一」区分度变差。
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord 构造失败", t)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化（state=${rec.state}），可能被其他 App 占用")
            rec.release()
            return false
        }

        return try {
            rec.startRecording()
            record = rec
            bytesRecorded = 0
            recording = true
            thread = Thread({ readLoop(rec, onFrame) }, "pcm-recorder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording 失败", t)
            rec.release()
            record = null
            false
        }
    }

    private fun readLoop(rec: AudioRecord, onFrame: (ByteArray) -> Unit) {
        val buf = ByteArray(FRAME_BYTES)
        while (recording) {
            // 用 read 而不是 read(ByteArray,off,len,READ_BLOCKING)：
            // 后者在部分机型上会一直阻塞到整个 buffer 满，松手后要多等一帧。
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (t: Throwable) {
                Log.e(TAG, "read 异常，停止录音", t)
                break
            }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                continue
            }
            bytesRecorded += n
            // 必须拷一份：buf 会被下一轮 read 覆盖，而回调里可能异步发送
            onFrame(buf.copyOf(n))
        }
    }

    /**
     * 停止录音并释放底层资源。**幂等**，可以在任意线程调。
     *
     * 返回已经录到的字节数（调用方据此判断是不是误触）。
     */
    override fun stop(): Int {
        if (!recording) return bytesRecorded
        recording = false
        thread?.let { t ->
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (ignore: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        record?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (ignore: Throwable) {
                // 已经停了会抛 IllegalStateException，忽略
            }
            try {
                rec.release()
            } catch (ignore: Throwable) {
            }
        }
        record = null
        return bytesRecorded
    }
}
