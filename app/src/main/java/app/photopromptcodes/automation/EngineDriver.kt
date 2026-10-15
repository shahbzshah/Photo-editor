package app.photopromptcodes.automation

import android.net.Uri
import android.webkit.WebView

/**
 * Common interface for AI image-engine drivers (ChatGPT, Qwen, ...).
 *
 * Each engine drives its website inside a WebView: attach photo, send prompt,
 * harvest the result image. Events are delivered via [DriverEvent].
 */
interface EngineDriver {
    fun setListener(l: (DriverEvent) -> Unit)
    fun attach(webView: WebView)
    fun start(photoUri: Uri, prompt: String)
    fun destroy()
    /** Called when the user completes a manual login gate (if the engine needs one). */
    fun notifyLoginCompleted()
}

/** Which AI engine to use for edits. Persisted in SharedPreferences. */
enum class Engine(val id: String, val displayName: String) {
    CHATGPT("chatgpt", "ChatGPT"),
    QWEN("qwen", "Qwen");

    companion object {
        fun fromId(id: String?): Engine =
            values().firstOrNull { it.id == id } ?: CHATGPT
    }
}
