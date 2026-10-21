package com.kuguan.app.data.backup.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class NutstoreWebDavStoreTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun uploadListDownloadAndIdempotentRetry() = runBlocking {
        val fake = FakeDav()
        val store = NutstoreWebDavStore("user@example.com", "application-password", OkHttpClient.Builder().addInterceptor(fake).build())
        val id = UUID.randomUUID()
        val payload = backupJson(id)
        val source = tempFile(payload)
        val hash = sha(source.readBytes())
        val first = store.upload(id, source, hash)
        assertEquals(first, store.upload(id, source, hash))
        assertEquals(listOf(first), store.list())
        val target = File.createTempFile("dav-down-", ".json").apply { delete() }
        assertEquals(first, store.download(id, target))
        assertEquals(payload, target.readText())
        assertEquals(1, fake.files.keys.count { it.endsWith(".ok.json") })
        assertTrue(fake.requests.all { it.url.scheme == "https" && it.url.host == "dav.jianguoyun.com" })
        assertTrue(fake.requests.all { it.header("Authorization")?.startsWith("Basic ") == true })
    }

    @Test fun moveUnsupportedFallsBackToVerifiedPut() = runBlocking {
        val fake = FakeDav(moveSupported = false)
        val store = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        val id = UUID.randomUUID()
        val file = tempFile(backupJson(id))
        store.upload(id, file, sha(file.readBytes()))
        assertTrue(fake.files.containsKey("backup-$id.json"))
        assertTrue(fake.files.containsKey("backup-$id.json.ok.json"))
    }

    @Test fun corruptedTemporaryUploadCannotCommit() = runBlocking {
        val fake = FakeDav(corruptTempGet = true)
        val store = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        val id = UUID.randomUUID()
        val file = tempFile(backupJson(id))
        val error = assertThrows(CloudBackupException::class.java) { runBlocking { store.upload(id, file, sha(file.readBytes())) } }
        assertEquals(CloudBackupException.Category.CORRUPT, error.category)
        assertFalse(fake.files.keys.any { it.endsWith(".ok.json") })
        assertFalse(fake.files.keys.any { it.startsWith(".upload-") })
    }

    @Test fun tamperedDownloadLeavesExistingTargetUntouched() = runBlocking {
        val fake = FakeDav()
        val store = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        val id = UUID.randomUUID()
        val file = tempFile(backupJson(id))
        store.upload(id, file, sha(file.readBytes()))
        fake.files["backup-$id.json"] = "corrupt".toByteArray()
        val target = File.createTempFile("dav-down-", ".json").apply { delete() }
        val error = assertThrows(CloudBackupException::class.java) { runBlocking { store.download(id, target) } }
        assertEquals(CloudBackupException.Category.CORRUPT, error.category)
        assertFalse(target.exists())
    }

    @Test fun idConflictDoesNotOverwriteRemote() = runBlocking {
        val fake = FakeDav()
        val store = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        val id = UUID.randomUUID()
        val first = tempFile(backupJson(id))
        store.upload(id, first, sha(first.readBytes()))
        val second = tempFile(backupJson(id, enabled = false))
        val error = assertThrows(CloudBackupException::class.java) { runBlocking { store.upload(id, second, sha(second.readBytes())) } }
        assertEquals(CloudBackupException.Category.CONFLICT, error.category)
        assertEquals(first.readText(), fake.files["backup-$id.json"]!!.toString(Charsets.UTF_8))
    }

    @Test fun downloadRejectsExistingTargetAndWrongInternalId() = runBlocking {
        val fake = FakeDav()
        val store = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        val id = UUID.randomUUID()
        val wrong = tempFile(backupJson(UUID.randomUUID()))
        store.upload(id, wrong, sha(wrong.readBytes()))
        val target = tempFile("keep me")
        val existing = assertThrows(CloudBackupException::class.java) { runBlocking { store.download(id, target) } }
        assertEquals(CloudBackupException.Category.CONFLICT, existing.category)
        assertEquals("keep me", target.readText())
        target.delete()
        val mismatch = assertThrows(CloudBackupException::class.java) { runBlocking { store.download(id, target) } }
        assertEquals(CloudBackupException.Category.CORRUPT, mismatch.category)
        assertFalse(target.exists())
    }

    @Test fun redirectIsRejectedWithoutCrossHostCredentialRequest() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            response(chain.request(), 302, location = "https://attacker.example/steal")
        }.build()
        val store = NutstoreWebDavStore("u", "p", client)
        val error = assertThrows(CloudBackupException::class.java) { runBlocking { store.testConnection() } }
        assertEquals(CloudBackupException.Category.UNSUPPORTED, error.category)
        assertEquals(1, requests.size)
        assertEquals("dav.jianguoyun.com", requests.single().url.host)
    }

    @Test fun maliciousXmlAndDirectoryOverflowAreRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().method == "MKCOL") response(chain.request(), 201)
            else response(chain.request(), 207, xml)
        }.build()
        val store = NutstoreWebDavStore("u", "p", client)
        assertThrows(Exception::class.java) { runBlocking { store.testConnection() } }

        val fake = FakeDav()
        repeat(751) { fake.files["x$it"] = byteArrayOf(1) }
        val overflow = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        val error = assertThrows(CloudBackupException::class.java) { runBlocking { overflow.testConnection() } }
        assertEquals(CloudBackupException.Category.CAPACITY, error.category)
    }

    @Test fun rateAndCapacityErrorsAreClassified() {
        for ((status, expected) in listOf(429 to CloudBackupException.Category.RATE_LIMIT, 507 to CloudBackupException.Category.CAPACITY)) {
            val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), status) }.build()
            val store = NutstoreWebDavStore("u", "p", client)
            val error = assertThrows(CloudBackupException::class.java) { runBlocking { store.testConnection() } }
            assertEquals(expected, error.category)
        }
    }

    @Test fun thirtyNewestCommittedBackupsAreRetained() = runBlocking {
        val fake = FakeDav()
        val store = NutstoreWebDavStore("u", "p", OkHttpClient.Builder().addInterceptor(fake).build())
        repeat(32) {
            val id = UUID.randomUUID()
            val file = tempFile(backupJson(id))
            store.upload(id, file, sha(file.readBytes()))
        }
        assertEquals(30, store.list().size)
        assertEquals(30, fake.files.keys.count { it.endsWith(".ok.json") })
        assertEquals(30, fake.files.keys.count { it.endsWith(".json") && !it.endsWith(".ok.json") })
    }

    private fun tempFile(content: String): File = File.createTempFile("nutstore-", ".json").apply { writeText(content); deleteOnExit() }
    private fun backupJson(id: UUID, enabled: Boolean = true) =
        """{"schemaVersion":3,"backupId":"$id","createdAtUtc":"2026-10-19T00:00:00Z","reminderSettings":{"enabled":$enabled},"items":[],"locationRecords":[],"operations":[]}"""

    private class FakeDav(private val moveSupported: Boolean = true, private val corruptTempGet: Boolean = false) : Interceptor {
        val files = linkedMapOf<String, ByteArray>()
        val requests = mutableListOf<Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val name = request.url.encodedPath.substringAfterLast('/').let { java.net.URLDecoder.decode(it, "UTF-8") }
            return when (request.method) {
                "MKCOL" -> response(request, 201)
                "PROPFIND" -> response(request, 207, directoryXml())
                "PUT" -> {
                    if (request.header("If-None-Match") == "*" && name in files) return response(request, 412)
                    val sink = Buffer()
                    request.body!!.writeTo(sink)
                    files[name] = sink.readByteArray()
                    response(request, 201)
                }
                "GET" -> files[name]?.let {
                    response(request, 200, if (corruptTempGet && name.startsWith(".upload-")) "corrupt" else it.toString(Charsets.UTF_8))
                } ?: response(request, 404)
                "MOVE" -> {
                    if (!moveSupported) return response(request, 405)
                    val to = request.header("Destination")!!.substringAfterLast('/')
                    if (to in files) return response(request, 412)
                    files[to] = files.remove(name) ?: return response(request, 404)
                    response(request, 201)
                }
                "DELETE" -> if (files.remove(name) != null) response(request, 204) else response(request, 404)
                else -> response(request, 405)
            }
        }
        private fun directoryXml(): String = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\">")
            append("<d:response><d:href>/dav/AI-Kuguan-Backup/</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            files.keys.forEach { name ->
                append("<d:response><d:href>/dav/AI-Kuguan-Backup/$name</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
    }

    companion object {
        private fun response(request: Request, code: Int, body: String = "", location: String? = null): Response {
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(body.toResponseBody("application/octet-stream".toMediaType()))
            location?.let { builder.header("Location", it) }
            return builder.build()
        }
    }
}
