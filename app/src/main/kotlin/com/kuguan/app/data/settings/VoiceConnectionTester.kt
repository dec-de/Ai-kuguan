package com.kuguan.app.data.settings

import com.kuguan.app.data.asr.AliyunAsrClient
import com.kuguan.app.data.llm.DeepSeekLlmClient
import com.kuguan.app.data.llm.LlmRequest
import com.kuguan.app.data.llm.LlmResult

data class ConnectionTestResult(val success: Boolean, val message: String)

/** Tests the caller's current form values without saving, recording or touching inventory. */
class VoiceConnectionTester {
    suspend fun testAsr(config: VoiceServiceConfig): ConnectionTestResult {
        return try {
            AliyunAsrClient(config.asrUrl, config.asrKey, config.asrModel).open().cancel()
            ConnectionTestResult(true, "语音识别连接成功")
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message?.takeIf { it.startsWith("语音识别") ||
                it.startsWith("识别服务") || it.startsWith("连不上") || it.startsWith("密钥") }
                ?: "语音识别连接失败，请检查网络、Key 和模型")
        }
    }

    suspend fun testPrimary(config: VoiceServiceConfig): ConnectionTestResult =
        testLlm(config.primaryUrl, config.primaryKey, config.primaryModel, config.primaryProvider)

    suspend fun testFallback(config: VoiceServiceConfig): ConnectionTestResult =
        if (!config.fallbackConfigured) ConnectionTestResult(false, "备用 LLM 须填全地址、模型和 Key")
        else testLlm(config.fallbackUrl, config.fallbackKey, config.fallbackModel)

    private suspend fun testLlm(url: String, key: String, model: String, provider: String = ""): ConnectionTestResult {
        val client = DeepSeekLlmClient(url, key, model, provider = provider)
        client.configError?.let { return ConnectionTestResult(false, it) }
        return when (val result = client.extract(LlmRequest(
            systemPrompt = "你正在执行连接测试。仅返回一个 JSON 对象。",
            userText = "请返回 {\"ok\":true}。",
        ))) {
            is LlmResult.Success -> ConnectionTestResult(true, "LLM 连接成功")
            is LlmResult.Failure -> ConnectionTestResult(false, result.reason)
        }
    }
}
