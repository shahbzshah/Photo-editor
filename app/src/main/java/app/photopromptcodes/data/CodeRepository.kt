package app.photopromptcodes.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Loads the 167 prompt codes from app/src/main/assets/codes.json (converted from the web app's codes.ts). */
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
}
