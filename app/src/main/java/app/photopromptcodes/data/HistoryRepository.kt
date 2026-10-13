package app.photopromptcodes.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class HistoryRecord(
    val id: String,
    val code: String,
    val timestamp: Long,
    val originalFileName: String,
    val resultFileName: String
)

/**
 * File-based history store. Images live in files/history/, the index in
 * files/history/index.json. No database, no dependencies — simple and robust.
 */
class HistoryRepository(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "history").apply { mkdirs() }
    private val indexFile: File get() = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(): List<HistoryRecord> = withContext(Dispatchers.IO) {
        readIndex().sortedByDescending { it.timestamp }
    }

    suspend fun get(id: String): HistoryRecord? = withContext(Dispatchers.IO) {
        readIndex().firstOrNull { it.id == id }
    }

    fun originalFile(record: HistoryRecord): File = File(dir, record.originalFileName)
    fun resultFile(record: HistoryRecord): File = File(dir, record.resultFileName)

    /** Deletes all history records and their image files. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        readIndex().forEach { record ->
            try { originalFile(record).delete() } catch (_: Exception) {}
            try { resultFile(record).delete() } catch (_: Exception) {}
        }
        try { indexFile.delete() } catch (_: Exception) {}
    }

    /** Copies the picked photo into history storage; returns the new record id. */
    suspend fun stageOriginal(code: String, photoUri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dest = File(dir, "${id}_orig.jpg")
        context.contentResolver.openInputStream(photoUri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read the selected photo.")
        id
    }

    suspend fun saveResult(id: String, code: String, resultBytes: ByteArray): HistoryRecord =
        withContext(Dispatchers.IO) {
            val record = HistoryRecord(
                id = id,
                code = code,
                timestamp = System.currentTimeMillis(),
                originalFileName = "${id}_orig.jpg",
                resultFileName = "${id}_result.png"
            )
            File(dir, record.resultFileName).writeBytes(resultBytes)
            writeIndex(readIndex().filterNot { it.id == id } + record)
            record
        }

    suspend fun delete(record: HistoryRecord) = withContext(Dispatchers.IO) {
        originalFile(record).delete()
        resultFile(record).delete()
        writeIndex(readIndex().filterNot { it.id == record.id })
    }

    private fun readIndex(): List<HistoryRecord> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<HistoryRecord>>(indexFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(records: List<HistoryRecord>) {
        indexFile.writeText(json.encodeToString(records))
    }
}
