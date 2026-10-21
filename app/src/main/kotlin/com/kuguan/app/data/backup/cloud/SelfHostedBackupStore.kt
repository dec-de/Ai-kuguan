package com.kuguan.app.data.backup.cloud

import com.kuguan.app.data.backup.BackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The app never follows a redirect carrying a bearer token to another host. */
class SelfHostedBackupStore(
    baseUrl: String,
    private val token: String,
    client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES).build(),
) : CloudBackupStore {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = baseUrl.trimEnd('/').also {
        val parsed = it.toHttpUrlOrNull() ?: error("服务器地址无效")
        require(parsed.scheme == "https" && parsed.username.isEmpty() && parsed.password.isEmpty()) { "仅支持 HTTPS 地址" }
        require(parsed.encodedPath == "/api/v1") { "服务器地址必须以 /api/v1 结尾" }
    }

    override suspend fun testConnection(): Unit = withContext(Dispatchers.IO) {
        request("GET", "capabilities").use { response ->
            ensureSuccess(response)
            val obj = parseObject(response.body?.string() ?: error("服务器响应为空"))
            require(obj.number("maxUploadBytes") >= BackupCodec.MAX_BYTES) { "服务器文件上限低于 500 MB" }
            require(obj.number("retentionCount") >= 30) { "服务器保留份数低于 30" }
        }
    }

    override suspend fun upload(backupId: UUID, source: File, sha256: String): CloudBackupEntry = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() <= BackupCodec.MAX_BYTES) { "备份文件超过 500 MB" }
        require(sha256.matches(Regex("[0-9a-f]{64}")) && digest(source) == sha256) { "备份文件哈希不符" }
        if (source.length() > CHUNK_BYTES) return@withContext uploadChunks(backupId, source, sha256)
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = source.length()
            override fun writeTo(sink: BufferedSink) { FileInputStream(source).use { input -> sink.writeAll(input.source()) } }
        }
        request("PUT", "backups/$backupId", body, mapOf("X-Content-SHA256" to sha256)).use { response ->
            ensureSuccess(response)
            entry(parseObject(response.body?.string() ?: error("服务器响应为空")), "receivedAt")
                .also { require(it.id == backupId && it.sizeBytes == source.length() && it.sha256 == sha256) { "上传回执校验失败" } }
        }
    }

    private fun uploadChunks(backupId: UUID, source: File, sha256: String): CloudBackupEntry {
        request("GET", "capabilities").use { response ->
            ensureSuccess(response)
            val capabilities = parseObject(response.body?.string() ?: error("服务器响应为空"))
            require((capabilities["chunkUpload"] as? JsonPrimitive)?.booleanOrNull == true &&
                capabilities.number("maxRequestBytes") >= CHUNK_BYTES) { "服务器不支持大文件分块上传" }
        }
        val chunks = ((source.length() + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val received = mutableSetOf<Int>()
        request("GET", "uploads/$backupId").use { response ->
            if (response.code != 404) {
                ensureSuccess(response)
                val status = parseObject(response.body?.string() ?: error("上传状态为空"))
                require(status.number("totalSizeBytes") == source.length() && status.string("sha256") == sha256 &&
                    status.number("chunkCount") == chunks.toLong()) { "同 ID 的上传内容冲突" }
                if ((status["completed"] as? JsonPrimitive)?.booleanOrNull == true) {
                    return entry(status["backup"] as? JsonObject ?: error("缺少完成回执"), "receivedAt")
                }
                val array = status["receivedChunks"] as? JsonArray ?: error("上传状态格式错误")
                received += array.map { (it as? JsonPrimitive)?.int ?: error("上传块索引错误") }
            }
        }
        for (index in 0 until chunks) {
            if (index in received) continue
            val offset = index.toLong() * CHUNK_BYTES
            val length = minOf(CHUNK_BYTES, source.length() - offset)
            val chunkHash = digestRange(source, offset, length)
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) {
                    FileInputStream(source).use { input ->
                        input.channel.position(offset)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (n < 0) error("源文件上传时被截断")
                            sink.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                }
            }
            request("PUT", "uploads/$backupId/chunks/$index", body, mapOf(
                "X-Chunk-SHA256" to chunkHash,
                "X-Upload-Length" to source.length().toString(),
                "X-Upload-SHA256" to sha256,
            )).use(::ensureSuccess)
        }
        return request("POST", "uploads/$backupId/complete", ByteArray(0).toRequestBody()).use { response ->
            ensureSuccess(response)
            entry(parseObject(response.body?.string() ?: error("完成回执为空")), "receivedAt")
                .also { require(it.id == backupId && it.sizeBytes == source.length() && it.sha256 == sha256) { "上传完成回执校验失败" } }
        }
    }

    override suspend fun list(): List<CloudBackupEntry> = withContext(Dispatchers.IO) {
        val all = mutableListOf<CloudBackupEntry>()
        var cursor: String? = null
        do {
            val path = "backups?limit=30" + (cursor?.let { "&cursor=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
            request("GET", path).use { response ->
                ensureSuccess(response)
                val obj = parseObject(response.body?.string() ?: error("服务器响应为空"))
                val items = obj["items"] as? JsonArray ?: error("列表格式错误")
                all += items.map { entry(it as? JsonObject ?: error("列表格式错误"), "receivedAt") }
                cursor = (obj["nextCursor"] as? JsonPrimitive)?.contentOrNull
                require(all.size <= 10_000) { "列表页数过多" }
            }
        } while (!cursor.isNullOrEmpty())
        all
    }

    override suspend fun download(backupId: UUID, target: File): CloudBackupEntry = withContext(Dispatchers.IO) {
        require(!target.exists()) { "目标文件已存在" }
        val temp = File.createTempFile("download-", ".json", target.parentFile)
        try {
            request("GET", "backups/$backupId").use { response ->
                ensureSuccess(response)
                val expectedHash = response.header("X-Content-SHA256") ?: error("缺少哈希")
                val declared = response.header("Content-Length")?.toLongOrNull()
                require(declared == null || declared in 0..BackupCodec.MAX_BYTES.toLong()) { "备份文件超过 500 MB" }
                val actual = streamBody(response.body ?: error("备份文件为空"), temp)
                require((declared == null || actual.first == declared) && actual.second == expectedHash) { "下载文件校验失败" }
                temp.inputStream().use { BackupCodec.inspect(it).also { preview -> require(preview.backupId == backupId.toString()) { "备份 ID 不一致" } } }
                require(temp.renameTo(target)) { "无法保存下载文件" }
                CloudBackupEntry(backupId, actual.first, actual.second, response.header("X-Received-At") ?: "")
            }
        } finally { temp.delete() }
    }

    override suspend fun prune() { /* server owns retention */ }

    private fun request(method: String, path: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url("$base/$path").header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.method(method, body).build()).execute()
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val code = response.code
        val (category, reason) = when (code) {
            401, 403 -> CloudBackupException.Category.AUTH to "访问令牌无效或已撤销"
            409 -> CloudBackupException.Category.CONFLICT to "备份 ID 冲突"
            413, 507 -> CloudBackupException.Category.CAPACITY to "服务器空间或配额不足"
            422 -> CloudBackupException.Category.CORRUPT to "服务端校验失败"
            429 -> CloudBackupException.Category.RATE_LIMIT to "请求过于频繁"
            in 500..599 -> CloudBackupException.Category.SERVER to "服务器暂不可用"
            else -> CloudBackupException.Category.UNSUPPORTED to "HTTP $code"
        }
        throw CloudBackupException(category, reason + (response.header("Retry-After")?.let { "，请在 $it 后重试" } ?: ""))
    }

    private fun parseObject(body: String): JsonObject = Json.parseToJsonElement(body) as? JsonObject ?: error("响应格式错误")
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content ?: error("缺少 $key")
    private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.long ?: error("缺少 $key")
    private fun entry(o: JsonObject, dateField: String) = CloudBackupEntry(UUID.fromString(o.string("backupId")), o.number("sizeBytes"), o.string("sha256"), o.string(dateField))

    private fun streamBody(body: ResponseBody, target: File): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        var count = 0L
        body.byteStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= BackupCodec.MAX_BYTES) { "备份文件超过 500 MB" }
                md.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        } }
        return count to md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digestRange(file: File, offset: Long, length: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            input.channel.position(offset)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n < 0) error("源文件计算哈希时被截断")
                md.update(buffer, 0, n)
                remaining -= n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private const val CHUNK_BYTES = 10_000_000L }
}
