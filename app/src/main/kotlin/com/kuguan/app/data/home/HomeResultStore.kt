package com.kuguan.app.data.home

import android.content.Context
import android.util.AtomicFile
import com.kuguan.app.domain.query.ItemBrief
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LocationQty
import com.kuguan.app.domain.query.LookupResult
import com.kuguan.app.ui.home.ConfirmAction
import com.kuguan.app.ui.home.ConfirmBatch
import com.kuguan.app.ui.home.ConfirmRow
import com.kuguan.app.ui.home.Field
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One private, versioned result. Inventory backup and diagnostic exports never include this file. */
sealed interface HomeResult {
    val createdAt: Long
    data class Confirmation(val batch: ConfirmBatch, override val createdAt: Long) : HomeResult
    data class Query(val result: LookupResult, override val createdAt: Long) : HomeResult
}

/** AtomicFile protects against process death; the monitor serializes writes from different screens. */
class HomeResultStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "home-result-v1.json"))

    fun read(): HomeResult? = synchronized(lock) {
        runCatching { HomeResultCodec.decode(file.openRead().bufferedReader().use { it.readText() }) }
            .getOrNull()
    }

    fun save(result: HomeResult) = synchronized(lock) {
        val json = HomeResultCodec.encode(result) ?: return@synchronized
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    /** Called only after the inventory restore transaction succeeds. Safe to call repeatedly. */
    fun clear() = synchronized(lock) { file.delete() }

    private companion object { val lock = Any() }
}

/** Pure codec so every LookupResult subtype and full ConfirmBatch can be tested on the JVM. */
object HomeResultCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(result: HomeResult): String? {
        val payload = when (result) {
            is HomeResult.Confirmation -> {
                if (result.batch.rows.any { (it.committedSeq ?: 1L) <= 0L }) return null
                obj(
                    "type" to str("confirmation"), "createdAt" to num(result.createdAt),
                    "batch" to obj(
                        "batchId" to nullable(result.batch.batchId),
                        "heardText" to nullable(result.batch.heardText),
                        "unparsed" to bool(result.batch.unparsed),
                        "notice" to nullable(result.batch.notice),
                        "rows" to JsonArray(result.batch.rows.map { row ->
                            obj(
                                "key" to str(row.key), "action" to str(row.action.name),
                                "locationCode" to str(row.locationCode), "itemName" to str(row.itemName),
                                "quantity" to (row.quantity?.let(::num) ?: JsonNull),
                                "uncertainFields" to JsonArray(row.uncertainFields.map { str(it.name) }),
                                "committedSeq" to (row.committedSeq?.let(::num) ?: JsonNull),
                                "corrected" to bool(row.corrected),
                            )
                        }),
                    ),
                )
            }
            is HomeResult.Query -> obj(
                "type" to str("query"), "createdAt" to num(result.createdAt),
                "lookup" to lookup(result.result),
            )
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    fun decode(source: String): HomeResult {
        val root = json.parseToJsonElement(source).asObject()
        require(root.string("type") in setOf("confirmation", "query"))
        require(root.long("createdAt") > 0L)
        require(root.long("version") == 1L)
        val at = root.long("createdAt")
        return when (root.string("type")) {
            "confirmation" -> {
                val batch = root.obj("batch")
                val rows = batch.array("rows").map { element ->
                    val row = element.asObject()
                    ConfirmRow(
                        key = row.string("key"),
                        action = ConfirmAction.valueOf(row.string("action")),
                        locationCode = row.string("locationCode"),
                        itemName = row.string("itemName"),
                        quantity = row.optionalDouble("quantity"),
                        uncertainFields = row.array("uncertainFields").map { Field.valueOf(it.asString()) }.toSet(),
                        committedSeq = row.optionalLong("committedSeq"),
                        corrected = row.boolean("corrected"),
                    )
                }
                require(rows.none { (it.committedSeq ?: 1L) <= 0L })
                HomeResult.Confirmation(
                    ConfirmBatch(rows, batch.optionalString("batchId"), batch.optionalString("heardText"),
                        batch.boolean("unparsed"), batch.optionalString("notice")), at,
                )
            }
            else -> HomeResult.Query(readLookup(root.obj("lookup")), at)
        }
    }

    private fun lookup(result: LookupResult): JsonObject = when (result) {
        is LookupResult.Found -> obj("kind" to str("found"), "item" to item(result.item))
        is LookupResult.Matches -> obj("kind" to str("matches"), "items" to JsonArray(result.items.map(::item)))
        is LookupResult.NoLocations -> obj("kind" to str("noLocations"), "item" to item(result.item))
        is LookupResult.NotFound -> obj("kind" to str("notFound"), "query" to str(result.query),
            "suggestions" to JsonArray(result.suggestions.map { obj("id" to str(it.id), "name" to str(it.name)) }))
        LookupResult.NoName -> obj("kind" to str("noName"))
    }

    private fun readLookup(value: JsonObject): LookupResult = when (value.string("kind")) {
        "found" -> LookupResult.Found(readItem(value.obj("item")))
        "matches" -> LookupResult.Matches(value.array("items").map { readItem(it.asObject()) })
        "noLocations" -> LookupResult.NoLocations(readItem(value.obj("item")))
        "notFound" -> LookupResult.NotFound(value.string("query"), value.array("suggestions").map {
            val brief = it.asObject(); ItemBrief(brief.string("id"), brief.string("name"))
        })
        "noName" -> LookupResult.NoName
        else -> error("Unknown lookup result")
    }

    private fun item(value: ItemSnapshot): JsonObject = obj(
        "id" to str(value.id), "name" to str(value.name), "category" to nullable(value.category),
        "locations" to JsonArray(value.locations.map {
            obj("locationCode" to str(it.locationCode), "quantity" to num(it.quantity))
        }),
    )

    private fun readItem(value: JsonObject) = ItemSnapshot(
        value.string("id"), value.string("name"), value.optionalString("category"),
        value.array("locations").map {
            val location = it.asObject(); LocationQty(location.string("locationCode"), location.double("quantity"))
        },
    )

    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf("version" to num(1L), *values))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Number) = JsonPrimitive(value)
    private fun bool(value: Boolean) = JsonPrimitive(value)
    private fun nullable(value: String?) = value?.let(::str) ?: JsonNull
    private fun JsonElement.asObject() = this as? JsonObject ?: error("Expected object")
    private fun JsonElement.asString() = (this as? JsonPrimitive)?.content ?: error("Expected string")
    private fun JsonObject.string(key: String) = getValue(key).asString()
    private fun JsonObject.optionalString(key: String) = this[key]?.takeUnless { it == JsonNull }?.asString()
    private fun JsonObject.obj(key: String) = getValue(key).asObject()
    private fun JsonObject.array(key: String) = getValue(key) as? JsonArray ?: error("Expected array")
    private fun JsonObject.long(key: String) = (getValue(key) as? JsonPrimitive)?.longOrNull ?: error("Expected long")
    private fun JsonObject.optionalLong(key: String) = this[key]?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.longOrNull ?: error("Expected long")
    }
    private fun JsonObject.double(key: String) = (getValue(key) as? JsonPrimitive)?.doubleOrNull ?: error("Expected double")
    private fun JsonObject.optionalDouble(key: String) = this[key]?.takeUnless { it == JsonNull }?.let {
        (it as? JsonPrimitive)?.doubleOrNull ?: error("Expected double")
    }
    private fun JsonObject.boolean(key: String) = (getValue(key) as? JsonPrimitive)?.booleanOrNull ?: error("Expected boolean")
}
