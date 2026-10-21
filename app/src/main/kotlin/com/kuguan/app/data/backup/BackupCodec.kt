package com.kuguan.app.data.backup

import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.LocationRecordEntity
import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking

data class ReminderBackupSettings(val enabled: Boolean)

data class BackupPreview(
    val items: Int,
    val locations: Int,
    val operations: Int,
    val schemaVersion: Int,
    val backupId: String?,
    val createdAtUtc: String?,
    val reminderSettings: ReminderBackupSettings?,
)

data class BackupSnapshot(
    val items: List<ItemEntity>,
    val locationRecords: List<LocationRecordEntity>,
    val operations: List<OperationEntity>,
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
    val backupId: String? = UUID.randomUUID().toString(),
    val createdAtUtc: String? = Instant.now().toString(),
    val reminderSettings: ReminderBackupSettings? = ReminderBackupSettings(true),
)

/** UTF-8 JSON is processed one record at a time; total file bytes are capped exactly. */
object BackupCodec {
    const val SCHEMA_VERSION = 4
    const val MAX_BYTES = 500_000_000
    private const val MAX_ROWS = 100_000
    private val json = Json { isLenient = false }

    fun encode(snapshot: BackupSnapshot): String = ByteArrayOutputStream().also { encodeTo(it, snapshot) }.toString("UTF-8")

    fun encodeTo(output: OutputStream, snapshot: BackupSnapshot) {
        validate(snapshot)
        require(snapshot.schemaVersion == SCHEMA_VERSION) { "只可导出新版备份" }
        val writer = OutputStreamWriter(LimitedOutput(output), Charsets.UTF_8)
        writer.write("{\"schemaVersion\":$SCHEMA_VERSION,\"backupId\":")
        writer.write(JsonPrimitive(snapshot.backupId!!).toString())
        writer.write(",\"createdAtUtc\":")
        writer.write(JsonPrimitive(snapshot.createdAtUtc!!).toString())
        writer.write(",\"reminderSettings\":{\"enabled\":${snapshot.reminderSettings!!.enabled}}")
        writer.write(",\"items\":[")
        snapshot.items.forEachIndexed { n, row -> if (n != 0) writer.write(","); writer.write(itemJson(row).toString()) }
        writer.write("],\"locationRecords\":[")
        snapshot.locationRecords.forEachIndexed { n, row -> if (n != 0) writer.write(","); writer.write(locationJson(row).toString()) }
        writer.write("],\"operations\":[")
        snapshot.operations.forEachIndexed { n, row -> if (n != 0) writer.write(","); writer.write(operationJson(row).toString()) }
        writer.write("]}")
        writer.flush()
    }

    suspend fun encodePaged(
        output: OutputStream,
        backupId: String,
        createdAtUtc: String,
        reminderEnabled: Boolean,
        itemPage: suspend (String) -> List<ItemEntity>,
        locationPage: suspend (String) -> List<LocationRecordEntity>,
        operationPage: suspend (Long) -> List<OperationEntity>,
    ) {
        validate(BackupSnapshot(emptyList(), emptyList(), emptyList(), backupId = backupId,
            createdAtUtc = createdAtUtc, reminderSettings = ReminderBackupSettings(reminderEnabled)))
        val writer = OutputStreamWriter(LimitedOutput(output), Charsets.UTF_8)
        writer.write("{\"schemaVersion\":$SCHEMA_VERSION,\"backupId\":${JsonPrimitive(backupId)},")
        writer.write("\"createdAtUtc\":${JsonPrimitive(createdAtUtc)},\"reminderSettings\":{\"enabled\":$reminderEnabled},")
        writer.write("\"items\":[")
        var itemCursor = ""
        var count = 0
        while (true) {
            val page = itemPage(itemCursor)
            if (page.isEmpty()) break
            require(count + page.size <= MAX_ROWS && page.zipWithNext().all { (a, b) -> a.id < b.id } && page.first().id > itemCursor) { "物品分页无效" }
            page.forEach { if (count++ != 0) writer.write(","); writer.write(itemJson(it).toString()) }
            itemCursor = page.last().id
        }
        writer.write("],\"locationRecords\":[")
        var locationCursor = ""
        count = 0
        while (true) {
            val page = locationPage(locationCursor)
            if (page.isEmpty()) break
            require(count + page.size <= MAX_ROWS && page.zipWithNext().all { (a, b) -> a.id < b.id } && page.first().id > locationCursor) { "位置分页无效" }
            page.forEach { if (count++ != 0) writer.write(","); writer.write(locationJson(it).toString()) }
            locationCursor = page.last().id
        }
        writer.write("],\"operations\":[")
        var operationCursor = 0L
        count = 0
        while (true) {
            val page = operationPage(operationCursor)
            if (page.isEmpty()) break
            require(count + page.size <= MAX_ROWS && page.zipWithNext().all { (a, b) -> a.seq < b.seq } && page.first().seq > operationCursor) { "操作分页无效" }
            page.forEach { if (count++ != 0) writer.write(","); writer.write(operationJson(it).toString()) }
            operationCursor = page.last().seq
        }
        writer.write("]}")
        writer.flush()
    }

    fun decode(source: String): BackupSnapshot = decode(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)))

    /** Transfer an untrusted stream into a private file without buffering the whole backup. */
    fun copyLimited(input: InputStream, output: OutputStream): Long {
        val limited = LimitedInput(input)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = limited.read(buffer)
            if (count < 0) return total
            output.write(buffer, 0, count)
            total += count
        }
    }

    fun decode(input: InputStream): BackupSnapshot = runBlocking {
        val items = ArrayList<ItemEntity>()
        val locations = ArrayList<LocationRecordEntity>()
        val operations = ArrayList<OperationEntity>()
        val preview = visit(input, { items += it }, { locations += it }, { operations += it })
        BackupSnapshot(items, locations, operations, preview.schemaVersion, preview.backupId,
            preview.createdAtUtc, preview.reminderSettings)
    }

    /** Full validation with bounded row buffers and compact reference indexes. */
    suspend fun visit(
        input: InputStream,
        onItem: suspend (ItemEntity) -> Unit,
        onLocation: suspend (LocationRecordEntity) -> Unit,
        onOperation: suspend (OperationEntity) -> Unit,
    ): BackupPreview {
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = JsonStream(PushbackReader(InputStreamReader(LimitedInput(input), decoder), 2))
        reader.expect('{')
        val seen = mutableSetOf<String>()
        var version: Int? = null
        var id: String? = null
        var created: String? = null
        var reminder: ReminderBackupSettings? = null
        val itemIds = HashSet<String>()
        val itemLastUsed = HashMap<String, Long?>()
        val locationIds = HashSet<String>()
        val locationPairs = HashSet<Pair<String, String>>()
        val locationRefs = HashSet<String>()
        val operationIds = HashSet<String>()
        val seqs = HashSet<Long>()
        val operationRefs = HashSet<String>()
        val targetRefs = ArrayList<Pair<Long, Long>>()
        val lastTake = HashMap<String, Long>()
        var itemCount = -1
        var missingThresholdCount = 0
        var oldVersionThresholdCount = 0
        var locationCount = -1
        var operationCount = -1
        if (!reader.consume('}')) do {
            val key = reader.value().stringValue()
            require(seen.add(key)) { "备份字段重复" }
            reader.expect(':')
            when (key) {
                "schemaVersion" -> version = reader.value().number().int
                "backupId" -> id = reader.value().stringValue()
                "createdAtUtc" -> created = reader.value().stringValue()
                "reminderSettings" -> {
                    val obj = reader.value().obj()
                    require(obj.keys == setOf("enabled")) { "提醒设置字段无效" }
                    reminder = ReminderBackupSettings(obj.required("enabled").booleanValue())
                }
                "items" -> itemCount = reader.arrayVisit { value ->
                    val o = value.obj()
                    if ("restockThreshold" !in o) missingThresholdCount++
                    val row = ItemEntity(o.string("id"), o.string("name"), o.string("aliases"),
                        o.optionalString("category"), o.optionalString("expiryDate"),
                        o.optionalLong("lastUsedAt"), o.optionalLong("deletedAt"),
                        o.long("createdAt"), o.long("updatedAt"),
                        o["restockThreshold"]?.let { if (it == JsonNull) null else it.number().double })
                    require(row.id.isNotBlank() && row.name.isNotBlank() && itemIds.add(row.id)) { "物品 ID 或名称无效" }
                    require(row.restockThreshold == null || row.restockThreshold.isFinite() && row.restockThreshold >= 0.0) { "补货提醒数量无效" }
                    if (row.restockThreshold != null) oldVersionThresholdCount++
                    val aliases = runCatching { json.parseToJsonElement(row.aliases) }.getOrNull()
                    require(aliases is JsonArray && aliases.all { it is JsonPrimitive && it.isString }) { "别名无效" }
                    itemLastUsed[row.id] = row.lastUsedAt
                    onItem(row)
                }
                "locationRecords" -> locationCount = reader.arrayVisit { value ->
                    val o = value.obj()
                    val row = LocationRecordEntity(o.string("id"), o.string("itemId"), o.string("locationCode"),
                        o.double("quantity"), o.optionalLong("deletedAt"), o.long("createdAt"), o.long("updatedAt"))
                    require(row.id.isNotBlank() && row.locationCode.isNotBlank() && row.quantity.isFinite() && row.quantity > 0 &&
                        locationIds.add(row.id) && locationPairs.add(row.itemId to row.locationCode)) { "位置引用或数量无效" }
                    locationRefs += row.itemId
                    onLocation(row)
                }
                "operations" -> operationCount = reader.arrayVisit { value ->
                    val o = value.obj()
                    val row = OperationEntity(o.string("id"), o.long("seq"), o.string("type"),
                        o.optionalString("itemId"), o.optionalString("locationCode"), o.optionalDouble("quantity"),
                        o.optionalString("rawText"), o.optionalString("asrText"), o.optionalLong("targetSeq"),
                        o.optionalString("batchId"), o.optionalLong("revokedAt"), o.long("createdAt"))
                    require(row.id.isNotBlank() && operationIds.add(row.id) && row.seq > 0 && seqs.add(row.seq) &&
                        row.type in setOf(OperationType.PUT, OperationType.TAKE, OperationType.CORRECTION, OperationType.DELETE, OperationType.EDIT) &&
                        (row.quantity == null || row.quantity.isFinite())) { "操作 ID、序号或字段无效" }
                    row.itemId?.let { operationRefs += it }
                    row.targetSeq?.let { targetRefs += it to row.seq }
                    if (row.type == OperationType.TAKE && row.revokedAt == null && row.itemId != null)
                        lastTake.merge(row.itemId, row.createdAt) { previous, current -> maxOf(previous, current) }
                    onOperation(row)
                }
                else -> error("未知备份字段：$key")
            }
        } while (reader.consume(',').also { if (!it) reader.expect('}') })
        reader.end()
        require(version == 2 || version == 3 || version == SCHEMA_VERSION) { "不支持此备份版本" }
        val required = setOf("schemaVersion", "items", "locationRecords", "operations")
        require(seen == (if (version == 2) required else required + setOf("backupId", "createdAtUtc", "reminderSettings"))) { "备份字段不完整" }
        require(itemCount >= 0 && locationCount >= 0 && operationCount >= 0) { "备份字段不完整" }
        require(version != SCHEMA_VERSION || missingThresholdCount == 0) { "新版备份缺少补货提醒数量" }
        require(version == SCHEMA_VERSION || oldVersionThresholdCount == 0) { "旧版备份含补货提醒数量" }
        if (version != 2) {
            require(id?.let { runCatching { UUID.fromString(it) }.getOrNull()?.let { uuid -> uuid.version() == 4 && uuid.toString() == it } } == true) { "备份 ID 无效" }
            require(created?.let { runCatching { Instant.parse(it) }.isSuccess } == true && reminder != null) { "备份时间或提醒设置无效" }
        }
        require(locationRefs.all { it in itemIds } && operationRefs.all { it in itemIds } &&
            targetRefs.all { (target, seq) -> target in seqs && target < seq } &&
            itemLastUsed.all { (item, used) -> used == lastTake[item] }) { "备份引用或最近取用时间无效" }
        return BackupPreview(itemCount, locationCount, operationCount, version!!, id, created, reminder)
    }

    fun inspect(input: InputStream): BackupPreview = runBlocking { visit(input, {}, {}, {}) }

    fun validate(data: BackupSnapshot) {
        require(data.schemaVersion == 2 || data.schemaVersion == 3 || data.schemaVersion == SCHEMA_VERSION) { "不支持此备份版本" }
        if (data.schemaVersion != 2) {
            require(data.backupId?.let { runCatching { UUID.fromString(it) }.getOrNull()?.let { uuid -> uuid.version() == 4 && uuid.toString() == it } } == true) { "备份 ID 无效" }
            require(data.createdAtUtc?.let { runCatching { Instant.parse(it) }.isSuccess } == true && data.reminderSettings != null) { "备份时间或提醒设置无效" }
        } else require(data.backupId == null && data.createdAtUtc == null && data.reminderSettings == null) { "旧版备份字段无效" }
        require(data.items.size <= MAX_ROWS && data.locationRecords.size <= MAX_ROWS && data.operations.size <= MAX_ROWS) { "备份记录过多" }
        require(data.items.map { it.id }.toSet().size == data.items.size && data.items.none { it.id.isBlank() || it.name.isBlank() }) { "物品 ID 或名称无效" }
        require(data.items.all { it.restockThreshold == null || it.restockThreshold.isFinite() && it.restockThreshold >= 0.0 }) { "补货提醒数量无效" }
        require(data.schemaVersion == SCHEMA_VERSION || data.items.all { it.restockThreshold == null }) { "旧版备份含补货提醒数量" }
        val itemIds = data.items.map { it.id }.toSet()
        require(data.locationRecords.map { it.id }.toSet().size == data.locationRecords.size) { "位置 ID 重复" }
        require(data.locationRecords.map { it.itemId to it.locationCode }.toSet().size == data.locationRecords.size) { "物品位置重复" }
        require(data.locationRecords.all { it.id.isNotBlank() && it.itemId in itemIds && it.locationCode.isNotBlank() && it.quantity.isFinite() && it.quantity > 0 }) { "位置引用或数量无效" }
        require(data.operations.map { it.id }.toSet().size == data.operations.size && data.operations.map { it.seq }.toSet().size == data.operations.size) { "操作 ID 或序号重复" }
        val seqs = data.operations.map { it.seq }.toSet()
        val types = setOf(OperationType.PUT, OperationType.TAKE, OperationType.CORRECTION, OperationType.DELETE, OperationType.EDIT)
        require(data.operations.all { op ->
            op.id.isNotBlank() && op.seq > 0 && op.type in types &&
                (op.itemId == null || op.itemId in itemIds) &&
                (op.quantity == null || op.quantity.isFinite()) &&
                (op.targetSeq == null || op.targetSeq in seqs && op.targetSeq < op.seq)
        }) { "操作引用或字段无效" }
        val lastTake = data.operations.asSequence().filter {
            it.type == OperationType.TAKE && it.revokedAt == null && it.itemId != null
        }.groupBy { it.itemId!! }.mapValues { (_, ops) -> ops.maxOf { it.createdAt } }
        require(data.items.all { item ->
            val aliases = runCatching { json.parseToJsonElement(item.aliases) }.getOrNull()
            aliases is JsonArray && aliases.all { it is JsonPrimitive && it.isString } &&
                item.lastUsedAt == lastTake[item.id]
        }) { "别名或最近取用时间与历史不一致" }
    }

    private class LimitedInput(input: InputStream) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) check(++count <= MAX_BYTES) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) { count += it; check(count <= MAX_BYTES) } }
    }
    private class LimitedOutput(output: OutputStream) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(b: Int) { check(++count <= MAX_BYTES); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { check(count + len <= MAX_BYTES); count += len; out.write(b, off, len) }
    }

    private class JsonStream(private val reader: PushbackReader) {
        private fun next(): Int { var c: Int; do { c = reader.read() } while (c == 32 || c == 10 || c == 13 || c == 9); return c }
        fun consume(c: Char): Boolean { val found = next(); if (found == c.code) return true; if (found >= 0) reader.unread(found); return false }
        fun expect(c: Char) { require(consume(c)) { "JSON 格式错误：缺少 $c" } }
        fun end() { require(next() == -1) { "JSON 末尾有多余内容" } }
        fun value(): JsonElement = json.parseToJsonElement(rawValue())
        fun <T> array(convert: (JsonElement) -> T): List<T> {
            expect('[')
            val result = ArrayList<T>()
            if (!consume(']')) do {
                require(result.size < MAX_ROWS) { "备份记录过多" }
                result += convert(value())
            } while (consume(',').also { if (!it) expect(']') })
            return result
        }
        suspend fun arrayVisit(convert: suspend (JsonElement) -> Unit): Int {
            expect('[')
            var count = 0
            if (!consume(']')) do {
                require(count < MAX_ROWS) { "备份记录过多" }
                convert(value())
                count++
            } while (consume(',').also { if (!it) expect(']') })
            return count
        }
        private fun rawValue(): String {
            val first = next()
            require(first >= 0) { "JSON 提前结束" }
            val raw = StringBuilder().append(first.toChar())
            if (first == '{'.code || first == '['.code) {
                var depth = 1
                var quoted = false
                var escaped = false
                while (depth > 0) {
                    val c = reader.read(); require(c >= 0) { "JSON 提前结束" }
                    raw.append(c.toChar())
                    if (quoted) { if (escaped) escaped = false else if (c == '\\'.code) escaped = true else if (c == '"'.code) quoted = false }
                    else when (c) { '"'.code -> quoted = true; '{'.code, '['.code -> depth++; '}'.code, ']'.code -> depth-- }
                }
            } else if (first == '"'.code) {
                var escaped = false
                while (true) {
                    val c = reader.read(); require(c >= 0) { "JSON 字符串提前结束" }
                    raw.append(c.toChar())
                    if (escaped) escaped = false else if (c == '\\'.code) escaped = true else if (c == '"'.code) break
                }
            } else {
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == ','.code || c == ']'.code || c == '}'.code || c == 32 || c == 10 || c == 13 || c == 9) { reader.unread(c); break }
                    raw.append(c.toChar())
                }
            }
            return raw.toString()
        }
    }

    private fun itemJson(i: ItemEntity) = buildJsonObject {
        put("id", i.id); put("name", i.name); put("aliases", i.aliases)
        putNullable("category", i.category); putNullable("expiryDate", i.expiryDate)
        putNullable("lastUsedAt", i.lastUsedAt); putNullable("deletedAt", i.deletedAt)
        put("createdAt", i.createdAt); put("updatedAt", i.updatedAt)
        putNullable("restockThreshold", i.restockThreshold)
    }
    private fun locationJson(l: LocationRecordEntity) = buildJsonObject {
        put("id", l.id); put("itemId", l.itemId); put("locationCode", l.locationCode)
        put("quantity", l.quantity); putNullable("deletedAt", l.deletedAt)
        put("createdAt", l.createdAt); put("updatedAt", l.updatedAt)
    }
    private fun operationJson(o: OperationEntity) = buildJsonObject {
        put("id", o.id); put("seq", o.seq); put("type", o.type)
        putNullable("itemId", o.itemId); putNullable("locationCode", o.locationCode)
        putNullable("quantity", o.quantity); putNullable("rawText", o.rawText)
        putNullable("asrText", o.asrText); putNullable("targetSeq", o.targetSeq)
        putNullable("batchId", o.batchId); putNullable("revokedAt", o.revokedAt)
        put("createdAt", o.createdAt)
    }
    private fun JsonObjectBuilder.putNullable(key: String, value: String?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonObjectBuilder.putNullable(key: String, value: Double?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonElement.obj() = this as? JsonObject ?: error("备份对象格式错误")
    private fun JsonObject.required(key: String) = this[key] ?: error("缺少字段：$key")
    private fun JsonElement.number() = (this as? JsonPrimitive)?.takeUnless { it.isString } ?: error("数值字段错误")
    private fun JsonElement.stringValue() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("不是字符串")
    private fun JsonElement.booleanValue() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.content?.let { when (it) { "true" -> true; "false" -> false; else -> null } } ?: error("不是布尔值")
    private fun JsonObject.string(key: String) = required(key).stringValue()
    private fun JsonObject.optionalString(key: String) = if (required(key) == JsonNull) null else string(key)
    private fun JsonObject.long(key: String) = required(key).number().long
    private fun JsonObject.optionalLong(key: String) = if (required(key) == JsonNull) null else long(key)
    private fun JsonObject.double(key: String) = required(key).number().double
    private fun JsonObject.optionalDouble(key: String) = if (required(key) == JsonNull) null else double(key)
}
