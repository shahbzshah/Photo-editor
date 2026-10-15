package app.photopromptcodes.data

import android.content.Context
import app.photopromptcodes.automation.Engine

/** Persists the user's chosen AI engine (ChatGPT / Qwen). */
object EnginePrefs {
    private const val PREFS = "ppc_prefs"
    private const val KEY = "selected_engine"

    fun get(context: Context): Engine {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, Engine.CHATGPT.id)
        return Engine.fromId(id)
    }

    fun set(context: Context, engine: Engine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, engine.id).apply()
    }
}
