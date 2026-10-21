package com.kuguan.app.data.backup.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.backup.BackupCodec
import com.kuguan.app.data.backup.BackupSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Run only on an emulator with a disposable server token; never reads or restores personal Room data. */
@RunWith(AndroidJUnit4::class)
class SelfHostedLiveTest {
    @Test fun realHttpsChunkUploadAndVerifiedDownload() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("selfhost_token")
        val rawId = args.getString("selfhost_id")
        val serverUrl = args.getString("selfhost_url")
        assumeTrue(!token.isNullOrBlank() && !rawId.isNullOrBlank() && !serverUrl.isNullOrBlank())
        val id = UUID.fromString(rawId!!)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "live-selfhost-$id.json")
        val target = File(context.cacheDir, "live-selfhost-$id-downloaded.json")
        try {
            val body = BackupCodec.encode(BackupSnapshot(emptyList(), emptyList(), emptyList(), backupId = id.toString(),
                createdAtUtc = "2026-10-19T00:00:00Z"))
                .toByteArray(Charsets.UTF_8)
            val total = 10_000_001L
            source.outputStream().use { output ->
                output.write(body[0].toInt())
                val blank = ByteArray(64 * 1024) { ' '.code.toByte() }
                var remaining = total - body.size
                while (remaining > 0) { val n = minOf(remaining, blank.size.toLong()).toInt(); output.write(blank, 0, n); remaining -= n }
                output.write(body, 1, body.size - 1)
            }
            assertEquals(total, source.length())
            val hash = digest(source)
            val store = SelfHostedBackupStore(serverUrl!!, token!!)
            store.testConnection()
            val uploaded = store.upload(id, source, hash)
            assertEquals(total, uploaded.sizeBytes)
            assertTrue(store.list().any { it.id == id && it.sha256 == hash })
            val downloaded = store.download(id, target)
            assertEquals(hash, downloaded.sha256)
            assertEquals(hash, digest(target))
            assertEquals(id.toString(), target.inputStream().use { BackupCodec.inspect(it).backupId })
        } finally {
            source.delete()
            target.delete()
        }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").also { md ->
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; md.update(bytes, 0, n) }
        }
    }.digest().joinToString("") { "%02x".format(it) }
}
