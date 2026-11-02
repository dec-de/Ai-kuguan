package com.kuguan.app.di

import android.content.Context
import androidx.room.withTransaction
import com.kuguan.app.data.asr.AliyunAsrClient
import com.kuguan.app.data.asr.AsrClient
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.llm.DeepSeekLlmClient
import com.kuguan.app.data.llm.LlmClient
import com.kuguan.app.data.reminder.ReminderManager
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import com.kuguan.app.data.settings.VoiceConnectionTester
import com.kuguan.app.data.settings.VoiceServiceConfig
import com.kuguan.app.data.settings.VoiceServiceSettings
import com.kuguan.app.data.voice.IntentGateway
import com.kuguan.app.data.voice.SpeechCaseStore
import com.kuguan.app.data.voice.recordSafely
import com.kuguan.app.data.voice.SpeechDiagnosticRecorder
import com.kuguan.app.data.voice.VoiceController

/**
 * 手动依赖容器 —— 刻意**不用** Hilt/Dagger。
 *
 * 理由（开发计划 3.1）：
 * - 这个 App 的全部依赖就是「一个 Room 库 + 一个 Repository」，Hilt 的注解处理器
 *   会拖慢构建，且在 AGP 9 下有兼容性风险（jianshen 工程已踩过插件相关的坑）。
 * - 40 行手写单例在这个体量下可读性更好，且没有一层不必要的间接。
 *
 * 规模判断：等依赖超过 5 个、或出现真正需要作用域隔离（Session/Activity 级）的东西时，
 * 再换框架不迟 —— 到那时迁移成本仍然很低。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext
    val voiceSettings: VoiceServiceSettings by lazy { VoiceServiceSettings(appContext) }
    val voiceConnectionTester: VoiceConnectionTester by lazy { VoiceConnectionTester() }

    /** 正式版和调试版均保留逐次语音证据，直到用户在存储页清除。 */
    val speechCases: SpeechCaseStore by lazy {
        SpeechCaseStore(appContext.filesDir, sensitiveValues = { voiceSettings.sensitiveValues() })
    }
    val speechDiagnostics: SpeechDiagnosticRecorder by lazy { speechCases }

    // Room 单例，进程内共用一个连接
    val database: KuguanDatabase by lazy { KuguanDatabase.get(appContext) }

    // Repository 是唯一的写库入口，UI 层不直接碰 DAO（便于将来加缓存/同步）
    val repository: StorageRepository by lazy {
        StorageRepository(
            itemDao = database.itemDao(),
            locationDao = database.locationDao(),
            operationDao = database.operationDao(),
            // 批量写入与整批撤销必须是原子的：真实的「半批」记录用户发现不了
            transactionRunner = TransactionRunner { block -> database.withTransaction { block() } },
            stockStateRefresh = { ReminderManager(appContext).refreshStockState() },
        )
    }

    // ---------- 语音链路（M1）----------

    /**
     * 识别客户端。**每次按下新建一个实例**（= 一个 WebSocket 连接），
     * 但共享底层 OkHttp 连接池，TLS 不用重新握。
     */
    private fun newAsrClient(rawEventSink: (String) -> Unit = {}): AsrClient =
        voiceSettings.read().config.let { config -> AliyunAsrClient(
            wsUrl = config.asrUrl,
            apiKey = config.asrKey,
            model = config.asrModel,
            rawEventSink = rawEventSink,
        ) }

    val asrClientFactory: () -> AsrClient = { newAsrClient() }

    /** 语音编排（状态机）。随 Compose 页面创建/释放。 */
    fun newVoiceController(): VoiceController =
        VoiceController({ sessionId -> newAsrClient { raw ->
            speechDiagnostics.recordSafely(sessionId, "ASR_SERVER_RAW", raw)
        } }, diagnostics = speechDiagnostics,
            audioCaptureFactory = speechCases::startAudio)

    // ---------- LLM 意图抽取（M2）----------

    // Reuse TLS pools across config versions; each request holds an immutable route pair.
    private val primaryHttp by lazy { DeepSeekLlmClient.defaultClient(DeepSeekLlmClient.DEFAULT_TIMEOUT_MS) }
    private val fallbackHttp by lazy { DeepSeekLlmClient.defaultClient(DeepSeekLlmClient.DEFAULT_TIMEOUT_MS) }
    private var cachedRoutes: Pair<VoiceServiceConfig, Pair<LlmClient, LlmClient?>>? = null

    @Synchronized
    private fun llmRoutes(): Pair<LlmClient, LlmClient?> {
        val config = voiceSettings.read().config
        cachedRoutes?.takeIf { it.first == config }?.let { return it.second }
        val primary = DeepSeekLlmClient(config.primaryUrl, config.primaryKey, config.primaryModel,
            http = primaryHttp, provider = config.primaryProvider)
        val fallback = if (config.fallbackConfigured) DeepSeekLlmClient(
            config.fallbackUrl, config.fallbackKey, config.fallbackModel, http = fallbackHttp) else null
        return (primary to fallback).also { cachedRoutes = config to it }
    }

    /**
     * prompt 模板：`assets/prompt/intent-v1.md`（单一真源，回归脚本读同一份文件）。
     *
     * 读失败返回空串；`IntentGateway` 会显示错误且不产生待写入行。
     */
    val intentPromptTemplate: String by lazy {
        runCatching {
            appContext.assets.open("prompt/intent-v1.md").bufferedReader().use { it.readText() }
        }.getOrElse {
            android.util.Log.e("AppContainer", "读取 prompt 失败：${it.javaClass.simpleName}")
            ""
        }
    }

    /** 「一句话 → 若干行」的编排入口。 */
    val intentGateway: IntentGateway by lazy {
        IntentGateway(
            repository = repository,
            llm = llmRoutes().first,
            routeProvider = ::llmRoutes,
            promptTemplate = intentPromptTemplate,
            diagnostics = speechDiagnostics,
        )
    }

}
