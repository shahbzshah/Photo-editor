package app.photopromptcodes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Loads the 172 prompt codes from app/src/main/assets/codes.json (converted from the web app's codes.ts). */
object CodeRepository {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<PromptCode>? = null

    suspend fun load(context: Context): List<PromptCode> = withContext(Dispatchers.IO) {
        cache ?: run {
            val text = context.assets.open("codes.json").bufferedReader().use { it.readText() }
            json.decodeFromString<List<PromptCode>>(text).also { cache = it }
        }
    }

    /** Categories in first-appearance order. */
    fun categories(codes: List<PromptCode>): List<String> = codes.map { it.category }.distinct()

    /** Newest stamped release in the data, e.g. "2.4.0" — null when nothing is stamped. */
    fun latestAddedVersion(codes: List<PromptCode>): String? =
        codes.mapNotNull { it.addedIn }.maxWithOrNull(
            compareBy(
                { it.split('.').getOrNull(0)?.toIntOrNull() ?: 0 },
                { it.split('.').getOrNull(1)?.toIntOrNull() ?: 0 },
                { it.split('.').getOrNull(2)?.toIntOrNull() ?: 0 }
            )
        )

    /** True when the code is from the newest stamped batch — the "new" ones. */
    fun isNew(code: PromptCode, latest: String?): Boolean =
        latest != null && code.addedIn == latest
}
