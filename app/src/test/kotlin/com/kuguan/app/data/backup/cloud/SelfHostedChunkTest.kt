package com.kuguan.app.data.backup.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

class SelfHostedChunkTest {
    @Test fun splitResumeAndCompleteWithOriginalId() = runBlocking {
        val source = File.createTempFile("selfhost-chunk-", ".json")
        try {
            RandomAccessFile(source, "rw").use { it.setLength(10_000_001) }
            val fullHash = digest(source)
            val id = UUID.randomUUID()
            val seen = mutableListOf<String>()
            var completed = false
            val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                seen += "${request.method} $path"
                fun response(code: Int, body: String) = Response.Builder().request(request)
                    .protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
                when {
                    path.endsWith("/capabilities") -> response(200, """{"maxUploadBytes":500000000,"retentionCount":30,"chunkUpload":true,"maxRequestBytes":10000000}""")
                    path.endsWith("/uploads/$id") -> if (!completed) response(404, "{}") else response(200,
                        """{"completed":true,"totalSizeBytes":10000001,"sha256":"$fullHash","chunkCount":2,"receivedChunks":[0,1],"backup":{"backupId":"$id","sizeBytes":10000001,"sha256":"$fullHash","receivedAt":"2026-10-19T00:00:00Z"}}""")
                    path.endsWith("/uploads/$id/chunks/0") || path.endsWith("/uploads/$id/chunks/1") -> {
                        val index = path.substringAfterLast('/').toInt()
                        val expected = if (index == 0) 10_000_000L else 1L
                        assertEquals(expected, request.body!!.contentLength())
                        assertEquals("10000001", request.header("X-Upload-Length"))
                        assertEquals(fullHash, request.header("X-Upload-SHA256"))
                        val bytes = Buffer().also { request.body!!.writeTo(it) }
                        assertEquals(expected, bytes.size)
                        assertEquals(request.header("X-Chunk-SHA256"), bytes.readByteArray().sha256())
                        response(201, "{}")
                    }
                    path.endsWith("/uploads/$id/complete") -> {
                        completed = true
                        response(201, """{"backupId":"$id","sizeBytes":10000001,"sha256":"$fullHash","receivedAt":"2026-10-19T00:00:00Z"}""")
                    }
                    else -> response(404, "{}")
                }
            }).build()
            val store = SelfHostedBackupStore("https://backup.example/api/v1", "test-token", client)
            assertEquals(id, store.upload(id, source, fullHash).id)
            assertEquals(id, store.upload(id, source, fullHash).id)
            assertEquals(1, seen.count { it.endsWith("chunks/0") })
            assertEquals(1, seen.count { it.endsWith("chunks/1") })
            assertTrue(seen.any { it.endsWith("/complete") })
        } finally { source.delete() }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").also { md ->
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) }
        }
    }.digest().joinToString("") { "%02x".format(it) }

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }
}
