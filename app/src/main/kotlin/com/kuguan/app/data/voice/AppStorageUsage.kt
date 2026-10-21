package com.kuguan.app.data.voice

import android.content.Context
import java.io.File
import java.util.Locale

data class AppStorageSnapshot(
    val totalBytes: Long,
    val dataBytes: Long,
    val databaseBytes: Long,
    val caseBytes: Long,
)

/** File sizes rather than allocated blocks; the total includes APK splits and private app data. */
class AppStorageUsage(private val context: Context, private val cases: SpeechCaseStore) {
    fun read(): AppStorageSnapshot {
        val data = context.dataDir.totalFileBytes()
        val apk = (listOfNotNull(context.applicationInfo.sourceDir) +
            context.applicationInfo.splitSourceDirs.orEmpty()).distinct().sumOf { File(it).totalFileBytes() }
        val db = context.getDatabasePath("kuguan.db")
        val database = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"),
            File(db.path + "-journal")).sumOf { it.totalFileBytes() }
        return AppStorageSnapshot(apk + data, data, database, cases.sizeBytes())
    }
}

internal fun readableBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
}
