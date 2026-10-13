package app.photopromptcodes.data

import android.content.Context

/**
 * Tracks recently used prompt codes (most recent first, max 8).
 * Stored in SharedPreferences.
 */
object RecentCodes {
    private const val PREFS = "ppc_prefs"
    private const val KEY = "recent_codes"
    private const val MAX = 8

    fun record(context: Context, code: String) {
        if (code == "/custom" || code == "__custom__") return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY, emptySet())?.toMutableList() ?: mutableListOf()
        // getStringSet doesn't preserve order, so we store as comma-joined string instead
        val ordered = (prefs.getString(KEY, "") ?: "")
            .split(",")
            .filter { it.isNotEmpty() }
            .toMutableList()
        ordered.remove(code)
        ordered.add(0, code)
        prefs.edit().putString(KEY, ordered.take(MAX).joinToString(",")).apply()
    }

    fun get(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (prefs.getString(KEY, "") ?: "")
            .split(",")
            .filter { it.isNotEmpty() }
            .take(MAX)
    }
}
