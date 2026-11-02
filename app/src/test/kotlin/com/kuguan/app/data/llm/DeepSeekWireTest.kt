package com.kuguan.app.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层：端点拼接、请求体必带参数、响应解析。
 *
 * `reasoning_effort:none` 有专门一条用例 —— 它掉了延迟就从 0.9s 变 2.7s，
 * 而这种回归在界面上看不出来（只是「变慢了」）。
 */
class DeepSeekWireTest {

    @Test
    fun `端点必须带 v1`() {
        assertEquals(
            "https://llm.example.com/v1/chat/completions",
            DeepSeekWire.endpoint("https://llm.example.com"),
        )
    }

    @Test
    fun `端点容忍结尾斜杠`() {
        assertEquals(
            "https://llm.example.com/v1/chat/completions",
            DeepSeekWire.endpoint("https://llm.example.com/"),
        )
    }

    @Test
    fun `端点容忍已经带 v1 的配置`() {
        assertEquals(
            "https://llm.example.com/v1/chat/completions",
            DeepSeekWire.endpoint("https://llm.example.com/v1"),
        )
    }

    @Test
    fun `官方直连也使用兼容的 v1 端点`() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            DeepSeekWire.endpoint("https://api.deepseek.com"))
    }

    @Test
    fun `请求体必带 reasoning_effort none`() {
        val body = DeepSeekWire.requestBody("deepseek-flash", "sys", "user")
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("none", json["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("disabled", json["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `可选供应商限制且空值不发送`() {
        val specified = Json.parseToJsonElement(DeepSeekWire.requestBody("m", "s", "u", provider = "wafer")).jsonObject
        assertEquals("wafer", specified["provider"]!!.jsonObject["only"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("none", specified["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val unspecified = Json.parseToJsonElement(DeepSeekWire.requestBody("m", "s", "u")).jsonObject
        assertFalse(unspecified.containsKey("provider"))
    }

    @Test
    fun `请求体温度为零且要求 JSON`() {
        val json = Json.parseToJsonElement(DeepSeekWire.requestBody("m", "s", "u")).jsonObject
        assertEquals("0", json["temperature"]!!.jsonPrimitive.content)
        assertEquals("json_object", json["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `请求体包含 system 与 user 两条消息`() {
        val json = Json.parseToJsonElement(DeepSeekWire.requestBody("m", "系统提示", "用户原话")).jsonObject
        val messages = json["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("系统提示", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("用户原话", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `解析正常响应`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"{\"intent\":\"put\"}"}}],
             "usage":{"prompt_tokens":120,"completion_tokens":30}}
        """.trimIndent()
        val parsed = DeepSeekWire.parseResponse(200, body)
        assertTrue(parsed is DeepSeekWire.Parsed.Ok)
        parsed as DeepSeekWire.Parsed.Ok
        assertEquals("""{"intent":"put"}""", parsed.content)
        assertEquals(120, parsed.promptTokens)
        assertEquals(30, parsed.completionTokens)
    }

    @Test
    fun `非 2xx 一律失败`() {
        val parsed = DeepSeekWire.parseResponse(500, """{"error":"boom"}""")
        assertTrue(parsed is DeepSeekWire.Parsed.Err)
        assertTrue((parsed as DeepSeekWire.Parsed.Err).message.contains("500"))
    }

    @Test
    fun `200 但返回 HTML 也失败`() {
        val parsed = DeepSeekWire.parseResponse(200, "<!DOCTYPE html><html>管理页</html>")
        assertTrue(parsed is DeepSeekWire.Parsed.Err)
    }

    @Test
    fun `缺少 content 字段失败`() {
        val parsed = DeepSeekWire.parseResponse(200, """{"choices":[{"message":{}}]}""")
        assertTrue(parsed is DeepSeekWire.Parsed.Err)
    }

    @Test
    fun `空 choices 失败`() {
        val parsed = DeepSeekWire.parseResponse(200, """{"choices":[]}""")
        assertTrue(parsed is DeepSeekWire.Parsed.Err)
    }

    @Test
    fun `usage 缺失不影响内容`() {
        val parsed = DeepSeekWire.parseResponse(200, """{"choices":[{"message":{"content":"{}"}}]}""")
        assertTrue(parsed is DeepSeekWire.Parsed.Ok)
        assertEquals(null, (parsed as DeepSeekWire.Parsed.Ok).promptTokens)
    }

    @Test
    fun `错误信息里不带请求头`() {
        val parsed = DeepSeekWire.parseResponse(401, "unauthorized") as DeepSeekWire.Parsed.Err
        assertFalse(parsed.message.contains("Bearer"))
    }
}
