package com.kuguan.app.data.backup.cloud

import java.io.File
import java.util.UUID

/** A remote backup is usable only after its bytes and metadata have been verified. */
data class CloudBackupEntry(
    val id: UUID,
    val sizeBytes: Long,
    val sha256: String,
    val createdAtUtc: String,
)

/** The caller owns source and target files; implementations must never replace target with invalid bytes. */
interface CloudBackupStore {
    suspend fun testConnection()
    suspend fun upload(backupId: UUID, source: File, sha256: String): CloudBackupEntry
    suspend fun list(): List<CloudBackupEntry>
    suspend fun download(backupId: UUID, target: File): CloudBackupEntry
    suspend fun prune()
}

/** Stable categories let the UI distinguish credential, capacity, conflict and retryable failures. */
class CloudBackupException(
    val category: Category,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Category { AUTH, CAPACITY, CONFLICT, CORRUPT, NETWORK, RATE_LIMIT, SERVER, UNSUPPORTED }
}
