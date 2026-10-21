package com.kuguan.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kuguan.app.data.db.dao.BackupRestoreDao
import com.kuguan.app.data.db.dao.ItemDao
import com.kuguan.app.data.db.dao.LocationDao
import com.kuguan.app.data.db.dao.OperationDao
import com.kuguan.app.data.db.dao.ReminderSettingsDao

/**
 * v1 → v2：批量录入。
 *
 * 只加一列可空的 `batch_id`，不动任何既有数据 —— 存量操作保持 null，
 * UI 上按「单条」渲染，行为完全不变。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE operations ADD COLUMN batch_id TEXT")
        // 索引名必须与 Room 自己生成的那个一致（index_operations_batch_id），
        // 否则全新安装与升级上来的两份库索引名不同，将来据此写迁移的人会被坑
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_operations_batch_id ON operations(batch_id)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_settings (id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL)")
    }
}

/** Existing items keep reminders disabled unless the user opts in. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN restock_threshold REAL")
    }
}

@Database(
    entities = [ItemEntity::class, LocationRecordEntity::class, OperationEntity::class, ReminderSettingsEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class KuguanDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao
    abstract fun locationDao(): LocationDao
    abstract fun operationDao(): OperationDao
    abstract fun backupRestoreDao(): BackupRestoreDao
    abstract fun reminderSettingsDao(): ReminderSettingsDao

    companion object {
        private const val DB_NAME = "kuguan.db"

        @Volatile
        private var instance: KuguanDatabase? = null

        fun get(context: Context): KuguanDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): KuguanDatabase =
            Room.databaseBuilder(context, KuguanDatabase::class.java, DB_NAME)
                // ⚠️ 禁止 fallbackToDestructiveMigration()：用户数据丢失不可接受。
                // 每次 schema 变更必须写 Migration，并在真机验证迁移路径。
                // 详见开发计划 5.4，沿用 jianshen 工程的既有约定。
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
