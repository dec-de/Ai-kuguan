package com.kuguan.app.data.backup.cloud

import com.kuguan.app.data.backup.BackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV storage limited to the official Nutstore HTTPS endpoint. Credentials never follow redirects. */
class NutstoreWebDavStore(
    account: String,
    appPassword: String,
    client: OkHttpClient = OkHttpClient(),
) : CloudBackupStore {
    private val auth = Credentials.basic(account.requireNonBlank(), appPassword.requireNonBlank(), Charsets.UTF_8)
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = "https://dav.jianguoyun.com/dav/AI-Kuguan-Backup/"
    private val json = Json { isLenient = false }

    override suspend fun testConnection() = withContext(Dispatchers.IO) {
        ensureDirectory()
        entriesInDirectory()
        Unit
    }

    override suspend fun upload(backupId: UUID, source: File, sha256: String): CloudBackupEntry = withContext(Dispatchers.IO) {
        require(backupId.version() == 4) { "backupId must be UUID v4" }
        require(source.isFile && source.length() in 1..MAX_BYTES) { "备份文件为空或超过 500 MB" }
        require(SHA_PATTERN.matches(sha256)) { "SHA-256 格式错误" }
        val length = source.length()
        if (fileHash(source) != sha256) throw CloudBackupException(CloudBackupException.Category.CORRUPT, "本地备份哈希不匹配")
        ensureDirectory()
        if (entriesInDirectory().size >= DIRECTORY_MAX - 3) {
            prune()
            if (entriesInDirectory().size >= DIRECTORY_MAX - 3)
                throw CloudBackupException(CloudBackupException.Category.CAPACITY, "坚果云目录接近 750 项上限")
        }
        val finalName = backupName(backupId)
        val markerName = "$finalName.ok.json"
        readMarkerIfExists(markerName)?.let { existing ->
            if (existing.id != backupId || existing.sha256 != sha256 || existing.sizeBytes != length)
                throw CloudBackupException(CloudBackupException.Category.CONFLICT, "同一备份 ID 已有不同内容")
            verifyRemote(finalName, length, sha256)
            return@withContext existing
        }

        val tempName = ".upload-${UUID.randomUUID()}.tmp"
        try {
            put(tempName, source, ifNoneMatch = true)
            verifyRemote(tempName, length, sha256)
            val moved = move(tempName, finalName)
            if (!moved) {
                // A server without MOVE must still avoid silently replacing another backup.
                val existing = remoteHashIfExists(finalName)
                if (existing != null && existing != sha256)
                    throw CloudBackupException(CloudBackupException.Category.CONFLICT, "同一备份 ID 已有不同内容")
                if (existing == null) put(finalName, source, ifNoneMatch = true)
            }
            verifyRemote(finalName, length, sha256)
            val entry = CloudBackupEntry(backupId, length, sha256, Instant.now().toString())
            putMarker(markerName, entry)
            if (readMarkerIfExists(markerName) != entry)
                throw CloudBackupException(CloudBackupException.Category.CORRUPT, "提交标记回读不匹配")
            // A failed prune is reported separately by prune(), never rolls back a committed backup.
            runCatching { prune() }
            entry
        } finally {
            runCatching { delete(tempName) }
        }
    }

    override suspend fun list(): List<CloudBackupEntry> = withContext(Dispatchers.IO) {
        val names = entriesInDirectory().keys
        names.filter { it.endsWith(".json.ok.json") }.mapNotNull { marker ->
            val fileName = marker.removeSuffix(".ok.json")
            if (fileName !in names) return@mapNotNull null
            readMarkerIfExists(marker)?.takeIf { backupName(it.id) == fileName }
        }.sortedByDescending { it.createdAtUtc }
    }

    override suspend fun download(backupId: UUID, target: File): CloudBackupEntry = withContext(Dispatchers.IO) {
        require(backupId.version() == 4)
        if (target.exists()) throw CloudBackupException(CloudBackupException.Category.CONFLICT, "目标文件已存在")
        val name = backupName(backupId)
        val marker = readMarkerIfExists("$name.ok.json")
            ?: throw CloudBackupException(CloudBackupException.Category.CORRUPT, "备份提交标记缺失")
        val parent = target.absoluteFile.parentFile ?: throw IOException("目标目录无效")
        require(parent.isDirectory)
        val temp = File.createTempFile(".cloud-backup-", ".tmp", parent)
        try {
            getToFile(name, temp, marker.sizeBytes, marker.sha256)
            val preview = try { temp.inputStream().use(BackupCodec::inspect) }
            catch (e: Exception) { throw CloudBackupException(CloudBackupException.Category.CORRUPT, "备份 JSON 校验失败", e) }
            if (preview.schemaVersion != 3 || preview.backupId != backupId.toString())
                throw CloudBackupException(CloudBackupException.Category.CORRUPT, "备份内部 ID 或版本不匹配")
            Files.move(temp.toPath(), target.toPath())
            marker
        } finally {
            temp.delete()
        }
    }

    override suspend fun prune() = withContext(Dispatchers.IO) {
        val all = list()
        all.drop(RETENTION_COUNT).forEach { old ->
            val name = backupName(old.id)
            delete(name)
            delete("$name.ok.json")
        }
        val entries = entriesInDirectory()
        val cutoff = Instant.now().minusSeconds(24 * 60 * 60)
        entries.forEach { (name, modified) ->
            if (modified != null && modified.isBefore(cutoff) &&
                (name.startsWith(".upload-") && name.endsWith(".tmp") ||
                    name.startsWith("backup-") && name.endsWith(".json") && "$name.ok.json" !in entries ||
                    name.endsWith(".json.ok.json") && name.removeSuffix(".ok.json") !in entries)) {
                delete(name)
            }
        }
        Unit
    }

    private fun ensureDirectory() {
        val response = execute("MKCOL", "")
        response.use {
            if (it.code !in setOf(201, 405)) fail(it)
        }
    }

    private fun entriesInDirectory(): Map<String, Instant?> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val response = execute("PROPFIND", "", stringBody(body, "application/xml; charset=utf-8"), mapOf("Depth" to "1"))
        response.use {
            if (it.code != 207) fail(it)
            val xml = limitedBytes(it, XML_MAX_BYTES)
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setXIncludeAware(false)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(xml.inputStream())
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            if (responses.length > DIRECTORY_MAX + 1) throw CloudBackupException(CloudBackupException.Category.CAPACITY, "坚果云目录项目超过 750")
            val names = mutableMapOf<String, Instant?>()
            for (i in 0 until responses.length) {
                val element = responses.item(i) as Element
                val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val propstats = element.getElementsByTagNameNS("DAV:", "propstat")
                val validProp = (0 until propstats.length).map { propstats.item(it) as Element }.firstOrNull { prop ->
                    val status = prop.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent.orEmpty()
                    status.contains(" 200 ") || status.endsWith(" 200 OK")
                } ?: continue
                val path = runCatching { java.net.URI(href).path }.getOrNull() ?: continue
                val prefix = "/dav/AI-Kuguan-Backup/"
                if (!path.startsWith(prefix)) continue
                val name = path.removePrefix(prefix).removeSuffix("/")
                if (name.isNotEmpty() && '/' !in name && '\\' !in name) {
                    val date = validProp.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent
                    names[name] = date?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
                }
            }
            return names
        }
    }

    private fun put(name: String, file: File, ifNoneMatch: Boolean) {
        val headers = if (ifNoneMatch) mapOf("If-None-Match" to "*") else emptyMap()
        execute("PUT", name, object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) { file.inputStream().use { input -> sink.writeAll(input.source()) } }
        }, headers).use { if (it.code !in 200..299) fail(it) }
    }

    private fun move(from: String, to: String): Boolean {
        execute("MOVE", from, null, mapOf("Destination" to url(to), "Overwrite" to "F")).use {
            if (it.code in 200..299) return true
            if (it.code in setOf(405, 501)) return false
            if (it.code == 412) {
                // Existing destination is checked by the caller before any fallback PUT.
                return false
            }
            fail(it)
        }
    }

    private fun remoteHashIfExists(name: String): String? {
        execute("GET", name).use { response ->
            if (response.code == 404) return null
            if (response.code !in 200..299) fail(response)
            return digest(response.body?.byteStream() ?: throw IOException("空响应"), MAX_BYTES).first
        }
    }

    private fun verifyRemote(name: String, expectedLength: Long, expectedHash: String) {
        execute("GET", name).use { response ->
            if (response.code !in 200..299) fail(response)
            val (hash, length) = digest(response.body?.byteStream() ?: throw IOException("空响应"), MAX_BYTES)
            if (length != expectedLength || hash != expectedHash)
                throw CloudBackupException(CloudBackupException.Category.CORRUPT, "坚果云回读哈希或长度不匹配")
        }
    }

    private fun getToFile(name: String, target: File, expectedLength: Long, expectedHash: String) {
        execute("GET", name).use { response ->
            if (response.code !in 200..299) fail(response)
            val source = response.body?.byteStream() ?: throw IOException("空响应")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES || total > expectedLength) throw CloudBackupException(CloudBackupException.Category.CORRUPT, "下载长度超限")
                    md.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
            if (total != expectedLength || md.hex() != expectedHash)
                throw CloudBackupException(CloudBackupException.Category.CORRUPT, "下载备份哈希或长度不匹配")
        }
    }

    private fun putMarker(name: String, entry: CloudBackupEntry) {
        val body = buildJsonObject {
            put("id", entry.id.toString()); put("fileName", backupName(entry.id))
            put("sizeBytes", entry.sizeBytes); put("sha256", entry.sha256)
            put("createdAtUtc", entry.createdAtUtc); put("schemaVersion", 3)
        }.toString()
        execute("PUT", name, stringBody(body, "application/json; charset=utf-8"), mapOf("If-None-Match" to "*")).use {
            if (it.code == 412) {
                val existing = readMarkerIfExists(name)
                if (existing == entry) return
                throw CloudBackupException(CloudBackupException.Category.CONFLICT, "同一备份 ID 的提交标记冲突")
            }
            if (it.code !in 200..299) fail(it)
        }
    }

    private fun readMarkerIfExists(name: String): CloudBackupEntry? {
        execute("GET", name).use {
            if (it.code == 404) return null
            if (it.code !in 200..299) fail(it)
            try {
                val o = json.parseToJsonElement(limitedBytes(it, MARKER_MAX_BYTES).toString(Charsets.UTF_8)) as? JsonObject
                    ?: throw IllegalArgumentException("marker object expected")
                val id = UUID.fromString(o.string("id"))
                val size = (o["sizeBytes"] as JsonPrimitive).long
                val hash = o.string("sha256")
                val created = o.string("createdAtUtc")
                if (id.version() != 4 || o.string("fileName") != backupName(id) ||
                    size !in 1..MAX_BYTES || !SHA_PATTERN.matches(hash) ||
                    (o["schemaVersion"] as JsonPrimitive).isString ||
                    (o["schemaVersion"] as JsonPrimitive).long != 3L) throw IllegalArgumentException()
                Instant.parse(created)
                return CloudBackupEntry(id, size, hash, created)
            } catch (e: Exception) {
                throw CloudBackupException(CloudBackupException.Category.CORRUPT, "提交标记内容错误", e)
            }
        }
    }

    private fun delete(name: String) {
        execute("DELETE", name).use { if (it.code !in 200..299 && it.code != 404) fail(it) }
    }

    private fun execute(method: String, name: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val target = url(name)
        val parsed = target.toHttpUrl()
        check(parsed.scheme == "https" && parsed.host == "dav.jianguoyun.com" && parsed.port == 443)
        val request = Request.Builder().url(parsed).header("Authorization", auth).header("Accept-Encoding", "identity")
        headers.forEach { (key, value) -> request.header(key, value) }
        request.method(method, body)
        try {
            return http.newCall(request.build()).execute().also {
                if (it.code in 300..399) {
                    it.close()
                    throw CloudBackupException(CloudBackupException.Category.UNSUPPORTED, "坚果云重定向被拒绝")
                }
            }
        } catch (e: CloudBackupException) { throw e }
        catch (e: IOException) { throw CloudBackupException(CloudBackupException.Category.NETWORK, "坚果云网络请求失败", e) }
    }

    private fun fail(response: Response): Nothing {
        val category = when (response.code) {
            401, 403 -> CloudBackupException.Category.AUTH
            409, 412 -> CloudBackupException.Category.CONFLICT
            413, 507 -> CloudBackupException.Category.CAPACITY
            429 -> CloudBackupException.Category.RATE_LIMIT
            in 500..599 -> CloudBackupException.Category.SERVER
            else -> CloudBackupException.Category.UNSUPPORTED
        }
        throw CloudBackupException(category, "坚果云请求失败：HTTP ${response.code}")
    }

    private fun url(name: String): String {
        require(name.isEmpty() || SAFE_NAME.matches(name))
        return base + name
    }

    private fun limitedBytes(response: Response, max: Int): ByteArray {
        val input = response.body?.byteStream() ?: throw IOException("空响应")
        return input.readNBytes(max + 1).also {
            if (it.size > max) throw CloudBackupException(CloudBackupException.Category.CORRUPT, "WebDAV 响应超限")
        }
    }

    private fun digest(input: InputStream, max: Long): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            size += n
            if (size > max) throw CloudBackupException(CloudBackupException.Category.CORRUPT, "备份数据超限")
            md.update(buffer, 0, n)
        }
        return md.hex() to size
    }

    private fun fileHash(file: File) = file.inputStream().use { digest(it, MAX_BYTES).first }
    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key invalid")
    private fun String.requireNonBlank() = also { require(it.isNotBlank()) }
    private fun backupName(id: UUID) = "backup-$id.json"
    private fun stringBody(text: String, type: String) = text.toRequestBody(type.toMediaType())

    companion object {
        const val MAX_BYTES = 500_000_000L
        const val RETENTION_COUNT = 30
        private const val DIRECTORY_MAX = 750
        private const val XML_MAX_BYTES = 2 * 1024 * 1024
        private const val MARKER_MAX_BYTES = 4096
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
    }
}
