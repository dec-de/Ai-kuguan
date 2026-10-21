package com.kuguan.app.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.db.ReminderSettingsEntity
import com.kuguan.app.data.home.HomeResultStore
import com.kuguan.app.data.reminder.ReminderManager
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.time.Instant
import java.util.UUID

data class BackupCounts(val items: Int, val locations: Int, val operations: Int)
data class StagedBackup(val file: File, val preview: BackupPreview) {
    fun discard() { file.delete() }
}

/** SAF 只负责字节流；本服务保证快照一致性和恢复原子性。 */
class BackupService(private val db: KuguanDatabase, private val context: Context? = null) {
    suspend fun snapshot(backupId: String = UUID.randomUUID().toString()): BackupSnapshot {
        // DataStore migration must finish before reading the consistent Room snapshot.
        context?.let { ReminderManager(it.applicationContext).enabled() }
        return db.withTransaction {
            BackupSnapshot(db.itemDao().exportAll(), db.locationDao().exportAll(), db.operationDao().exportAll(),
                backupId = backupId,
                reminderSettings = ReminderBackupSettings(db.reminderSettingsDao().enabledOrNull() ?: true))
        }
    }

    suspend fun exportJson(): String = java.io.ByteArrayOutputStream().also { exportTo(it) }.toString("UTF-8")

    /** Caller owns and closes the stream; retain the resulting bytes for retries. */
    suspend fun exportTo(output: OutputStream, backupId: String = UUID.randomUUID().toString()) {
        context?.let { ReminderManager(it.applicationContext).enabled() }
        db.withTransaction {
            BackupCodec.encodePaged(output, backupId, Instant.now().toString(),
                db.reminderSettingsDao().enabledOrNull() ?: true,
                { db.itemDao().backupPage(it, 256) },
                { db.locationDao().backupPage(it, 256) },
                { db.operationDao().backupPage(it, 256) })
        }
    }

    /** Stage under noBackupFilesDir, validate every row, then return only small preview metadata. */
    fun stageImport(input: InputStream, directory: File = context?.noBackupFilesDir
        ?: error("未提供暂存目录")): StagedBackup {
        val dir = File(directory, "backup-stage").apply { mkdirs() }
        val file = File.createTempFile("backup-", ".json", dir)
        try {
            file.outputStream().use { BackupCodec.copyLimited(input, it) }
            val preview = file.inputStream().use(BackupCodec::inspect)
            return StagedBackup(file, preview)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** The second parse and all writes share one Room transaction; any error rolls back. */
    suspend fun restoreStaged(staged: StagedBackup) {
        try { db.withTransaction {
            db.backupRestoreDao().clearOperations()
            db.locationDao().deleteAll()
            db.itemDao().deleteAll()
            // JSON object member order is arbitrary; three bounded passes preserve FK order.
            val items = staged.file.inputStream().use { input ->
                BackupCodec.visit(input, { db.itemDao().upsert(it) }, {}, {})
            }
            require(items == staged.preview) { "暂存文件在预览后发生变化" }
            val locations = staged.file.inputStream().use { input ->
                BackupCodec.visit(input, {}, { db.locationDao().upsert(it) }, {})
            }
            require(locations == staged.preview) { "暂存文件在预览后发生变化" }
            val batch = ArrayList<com.kuguan.app.data.db.OperationEntity>(256)
            val operations = staged.file.inputStream().use { input ->
                BackupCodec.visit(input, {}, {}, { row ->
                    batch += row
                    if (batch.size >= 256) { db.operationDao().appendAll(batch); batch.clear() }
                })
            }
            if (batch.isNotEmpty()) db.operationDao().appendAll(batch)
            require(operations == staged.preview) { "暂存文件在预览后发生变化" }
            operations.reminderSettings?.let { db.reminderSettingsDao().set(ReminderSettingsEntity(enabled = it.enabled)) }
        } } finally { staged.discard() }
        context?.let {
            HomeResultStore(it.applicationContext).clear()
            ReminderManager(it.applicationContext).onSettingsRestored()
        }
    }

    suspend fun currentCounts(): BackupCounts = db.withTransaction {
        BackupCounts(db.itemDao().backupCount(), db.locationDao().backupCount(), db.operationDao().count())
    }

    /** 调用方须先完成预览与用户确认；重复校验阻止绕过 UI 的损坏数据。 */
    suspend fun restore(snapshot: BackupSnapshot) {
        BackupCodec.validate(snapshot)
        db.withTransaction {
            db.backupRestoreDao().clearOperations()
            db.locationDao().deleteAll()
            db.itemDao().deleteAll()
            snapshot.items.forEach { db.itemDao().upsert(it) }
            snapshot.locationRecords.forEach { db.locationDao().upsert(it) }
            db.operationDao().appendAll(snapshot.operations)
            snapshot.reminderSettings?.let { db.reminderSettingsDao().set(ReminderSettingsEntity(enabled = it.enabled)) }
        }
        context?.let {
            HomeResultStore(it.applicationContext).clear()
            ReminderManager(it.applicationContext).onSettingsRestored()
        }
    }

    /** The byte cap and full JSON validation run before any database mutation. */
    suspend fun restore(input: InputStream) {
        val staged = stageImport(input, context?.noBackupFilesDir ?: File(System.getProperty("java.io.tmpdir") ?: "."))
        try { restoreStaged(staged) } finally { staged.discard() }
    }
}
