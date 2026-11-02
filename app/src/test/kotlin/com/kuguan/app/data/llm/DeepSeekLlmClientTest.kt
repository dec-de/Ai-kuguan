package com.kuguan.app.data.llm

import java.io.InterruptedIOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekLlmClientTest {
    @Test
    fun `总超时配置保留且拒绝明文 HTTP 地址`() {
        assertEquals(10000, DeepSeekLlmClient.DEFAULT_TIMEOUT_MS)
        val configured = DeepSeekLlmClient.defaultClient(DeepSeekLlmClient.DEFAULT_TIMEOUT_MS)
        assertEquals(10000, configured.callTimeoutMillis)
        assertEquals(10000, configured.readTimeoutMillis)
        assertEquals(10000, configured.writeTimeoutMillis)
        assertEquals(1000, configured.connectTimeoutMillis)
        assertFalse(configured.followRedirects)
        val client = DeepSeekLlmClient(
            baseUrl = "http://127.0.0.1:9999",
            apiKey = "test-key",
            model = "test-model",
        )
        assertFalse(client.configured)
        assertTrue(client.configError.orEmpty().contains("HTTPS"))
    }

    @Test fun `鉴权与重定向错误不泄露服务端响应`() {
        for (code in listOf(401, 302)) {
            val client = testClient(Interceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("remote secret sentinel")
                    .body("remote secret sentinel".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            val result = runBlocking { client.extract(LlmRequest("prompt", "test")) }
            assertTrue(result is LlmResult.Failure)
            assertFalse((result as LlmResult.Failure).reason.contains("sentinel"))
        }
    }

    @Test fun `调用超时返回可读错误且不泄露异常原文`() {
        val client = testClient(Interceptor { throw InterruptedIOException("timeout secret sentinel") })
        val result = runBlocking { client.extract(LlmRequest("prompt", "test")) }
        assertTrue(result is LlmResult.Failure)
        assertTrue((result as LlmResult.Failure).reason.contains("超时"))
        assertFalse(result.reason.contains("sentinel"))
    }

    private fun testClient(interceptor: Interceptor): DeepSeekLlmClient = DeepSeekLlmClient(
        baseUrl = "https://example.test", apiKey = "fake-key", model = "fake-model",
        http = OkHttpClient.Builder().addInterceptor(interceptor).followRedirects(false).build(),
    )
}
