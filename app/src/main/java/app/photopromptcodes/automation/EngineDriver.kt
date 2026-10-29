package app.photopromptcodes.automation

import android.net.Uri
import android.webkit.WebView

/**
 * Common interface for the AI image-engine driver (ChatGPT).
 *
 * The driver drives its website inside a WebView: attach photo, send prompt,
 * harvest the result image. Events are delivered via [DriverEvent].
 */
interface EngineDriver {
    fun setListener(l: (DriverEvent) -> Unit)
    fun attach(webView: WebView)
    fun start(photoUri: Uri, prompt: String)
    fun destroy()
    /** Called when the user completes a manual login gate (if the engine needs one). */
    fun notifyLoginCompleted()
    /** Runs one loose rescue scan for a finished image. No-op when no run is waiting. */
    fun requestRescueScan()
}

