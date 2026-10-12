package app.photopromptcodes.automation

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Progress steps shown on the run screen, in order. */
enum class RunStep(val label: String) {
    OPENING_CHATGPT("Opening ChatGPT"),
    CHECKING_LOGIN("Checking sign-in"),
    ATTACHING_PHOTO("Attaching photo"),
    SENDING_PROMPT("Sending prompt"),
    GENERATING("Generating image"),
    DONE("Done")
}

/** Events the driver emits. Always delivered on the main thread. */
sealed interface DriverEvent {
    data class Step(val step: RunStep) : DriverEvent
    data object LoginRequired : DriverEvent
    data class Finished(val imageBytes: ByteArray) : DriverEvent
    data class Failed(val message: String) : DriverEvent
}

class DriverException(message: String) : Exception(message)

/**
 * Drives ChatGPT's web photo editor inside a WebView.
 *
 * Owns no UI itself: the screen creates the WebView (via AndroidView) and
 * hands it to [attach]. Cookies persist in the app's WebView storage, so the
 * ChatGPT login survives app restarts after the one-time manual sign-in.
 *
 * All public methods are safe to call from the main thread; the driver hops
 * to Dispatchers.Main internally wherever WebView demands it.
 */
class ChatGptDriver {

    companion object {
        private const val TAG = "ChatGptDriver"
        private const val CHATGPT_URL = "https://chatgpt.com/"
        private const val PAGE_TIMEOUT_MS = 60_000L
        private const val ATTACH_TIMEOUT_MS = 30_000L
        private const val GENERATION_TIMEOUT_MS = 12 * 60_000L
        private const val LOGIN_TIMEOUT_MS = 15 * 60_000L
    }

    private var webView: WebView? = null
    private var listener: ((DriverEvent) -> Unit)? = null
    private var pendingPhoto: Uri? = null
    private var loginGate: CompletableDeferred<Unit>? = null
    private var imageArrived: CompletableDeferred<String>? = null
    private val imageHandled = AtomicBoolean(false)
    private var runJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun setListener(l: (DriverEvent) -> Unit) {
        listener = l
    }

    private fun emit(event: DriverEvent) {
        scope.launch { listener?.invoke(event) }
    }

    /**
     * Configures a WebView created by the UI layer. Must be called on the
     * main thread (AndroidView's factory runs there).
     */
    fun attach(wv: WebView) {
        webView = wv
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(wv, true)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "page finished: $url")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                val uri = pendingPhoto
                return if (uri != null) {
                    pendingPhoto = null
                    Log.d(TAG, "supplying photo to file chooser")
                    filePathCallback.onReceiveValue(arrayOf(uri))
                    true
                } else {
                    Log.w(TAG, "file chooser opened with no pending photo")
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
        wv.addJavascriptInterface(Bridge(), "PpcBridge")
    }

    /** Starts (or restarts) an edit run. Any in-flight run is cancelled. */
    fun start(photo: Uri, prompt: String) {
        runJob?.cancel()
        imageHandled.set(false)
        runJob = scope.launch {
            try {
                runEdit(photo, prompt)
            } catch (e: CancellationException) {
                // superseded by a newer run — stay silent
            } catch (e: Exception) {
                emit(DriverEvent.Failed(humanError(e)))
            }
        }
    }

    /** Called by the UI after the user finishes the manual sign-in. */
    fun notifyLoginCompleted() {
        CookieManager.getInstance().flush()
        loginGate?.complete(Unit)
    }

    fun destroy() {
        runJob?.cancel()
        scope.cancel()
        try {
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
    }

    // ------------------------------------------------------------------ flow

    private suspend fun runEdit(photo: Uri, prompt: String) {
        val wv = webView ?: throw DriverException("Browser not ready. Please retry.")
        currentPhoto = photo

        emit(DriverEvent.Step(RunStep.OPENING_CHATGPT))
        withContext(Dispatchers.Main) { wv.loadUrl(CHATGPT_URL) }
        val settled = waitFor(PAGE_TIMEOUT_MS) {
            val state = loginState()
            state == "in" || state == "out"
        }
        if (!settled) throw DriverException(
            "ChatGPT didn't finish loading. Check your connection and retry."
        )

        emit(DriverEvent.Step(RunStep.CHECKING_LOGIN))
        if (loginState() == "out") {
            emit(DriverEvent.LoginRequired)
            loginGate = CompletableDeferred()
            val done = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
                loginGate!!.await()
                true
            } ?: false
            loginGate = null
            if (!done) throw DriverException("Sign-in timed out. Please try again.")
            withContext(Dispatchers.Main) { wv.loadUrl(CHATGPT_URL) }
            val signedIn = waitFor(PAGE_TIMEOUT_MS) { loginState() == "in" }
            if (!signedIn) throw DriverException(
                "Still not signed in to ChatGPT. Sign in in the browser above, then retry."
            )
        } else if (loginState() != "in") {
            throw DriverException("Couldn't tell whether ChatGPT is signed in. Retry.")
        }

        emit(DriverEvent.Step(RunStep.ATTACHING_PHOTO))
        attachPhoto()

        emit(DriverEvent.Step(RunStep.SENDING_PROMPT))
        sendPrompt(prompt)

        emit(DriverEvent.Step(RunStep.GENERATING))
        val dataUrl = waitForImage()
        val bytes = dataUrlToBytes(dataUrl)
            ?: throw DriverException("Got the image back but couldn't decode it. Please retry.")

        emit(DriverEvent.Step(RunStep.DONE))
        emit(DriverEvent.Finished(bytes))
    }

    private suspend fun loginState(): String {
        val js = """(function(){
          var loginSels=[${Selectors.loginIndicators.joinToString(",") { JSONObject.quote(it) }}];
          var composerSels=[${Selectors.composer.joinToString(",") { JSONObject.quote(it) }}];
          function any(sels){ for(var i=0;i<sels.length;i++){ if(document.querySelector(sels[i])) return true; } return false; }
          var bodyText=(document.body?document.body.innerText:'').slice(0,3000);
          if(any(composerSels)) return 'in';
          if(any(loginSels) || /log\s*in/i.test(bodyText)) return 'out';
          return 'unknown';
        })()"""
        return unquote(evalJs(js)) ?: "unknown"
    }

    private suspend fun attachPhoto() {
        pendingPhoto = currentPhoto
        val clicked = unquote(
            evalJs("(function(){var b=${qsFirst(Selectors.attachButton)};if(!b)return 'no';b.click();return 'ok';})()")
        ) == "ok"
        if (!clicked) {
            pendingPhoto = null
            throw DriverException("Couldn't find the attach button — ChatGPT may have changed its layout (see Selectors.kt).")
        }
        // The file chooser consumes pendingPhoto synchronously when it opens.
        val consumed = waitFor(ATTACH_TIMEOUT_MS, 500) { pendingPhoto == null }
        if (!consumed) {
            // Fallback: the "+" may have opened a menu — click its upload item.
            val menuClicked = unquote(evalJs(attachMenuJs())) == "ok"
            if (menuClicked) {
                val consumed2 = waitFor(15000, 500) { pendingPhoto == null }
                if (!consumed2) {
                    pendingPhoto = null
                    throw DriverException("The photo picker didn't open. Try again.")
                }
            } else {
                pendingPhoto = null
                throw DriverException("The photo picker didn't open. Try again.")
            }
        }
        // Best-effort pause so the attachment thumbnail registers before we type.
        delay(2500)
    }

    private var currentPhoto: Uri? = null

    private suspend fun sendPrompt(prompt: String) {
        val setResult = unquote(evalJs(setPromptJs(prompt)))
        if (setResult != "ok") throw DriverException(
            "Couldn't type into the ChatGPT message box — its layout may have changed (see Selectors.kt)."
        )
        delay(1000)
        val sent = unquote(
            evalJs("(function(){var b=${qsFirst(Selectors.sendButton)};if(!b)return 'no';b.click();return 'ok';})()")
        ) == "ok"
        if (!sent) throw DriverException(
            "Couldn't find the send button — ChatGPT may have changed its layout (see Selectors.kt)."
        )
        delay(2000)
    }

    private suspend fun waitForImage(): String {
        imageArrived = CompletableDeferred()
        evalJs(observerJs())
        return withTimeoutOrNull(GENERATION_TIMEOUT_MS) { imageArrived!!.await() }
            ?: throw DriverException(
                "Timed out waiting for the generated image (12 min). It may still appear in your ChatGPT history."
            )
    }

    // ------------------------------------------------------------------ JS

    private fun qsFirst(selectors: List<String>): String {
        val arr = selectors.joinToString(",") { JSONObject.quote(it) }
        return "(function(){var s=[$arr];for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(el)return el;}return null;})()"
    }

    private fun attachMenuJs(): String {
        val hints = Selectors.attachMenuTextHints.joinToString(",") { JSONObject.quote(it) }
        return """(function(){
          var hints=[$hints];
          var els=document.querySelectorAll('button,div[role="menuitem"],div[role="option"],li');
          for(var i=0;i<els.length;i++){
            var t=(els[i].innerText||'').trim().toLowerCase();
            if(t.length>60) continue;
            for(var h=0;h<hints.length;h++){
              if(t.indexOf(hints[h])>-1){ els[i].click(); return 'ok'; }
            }
          }
          return 'no';
        })()"""
    }

    /**
     * Types into ChatGPT's ProseMirror composer. execCommand('insertText')
     * keeps the editor's internal state consistent; the fallbacks cover the
     * case where it is unavailable.
     */
    private fun setPromptJs(prompt: String): String {
        val q = JSONObject.quote(prompt)
        val composer = qsFirst(Selectors.composer)
        return """(function(){
          var el=$composer;
          if(!el) return 'no-composer';
          el.focus();
          var inserted=false;
          try{ inserted=document.execCommand('insertText',false,$q); }catch(e){}
          if(!inserted){
            try{
              var sel=window.getSelection(); var range=document.createRange();
              range.selectNodeContents(el); range.collapse(false);
              sel.removeAllRanges(); sel.addRange(range);
              inserted=document.execCommand('insertText',false,$q);
            }catch(e){}
          }
          if(!inserted){
            el.textContent=$q;
            el.dispatchEvent(new InputEvent('input',{bubbles:true}));
          }
          var txt=el.innerText||el.textContent||'';
          return txt.length>10?'ok':'empty';
        })()"""
    }

    /**
     * Watches for a NEW, fully-loaded image inside assistant messages, then
     * fetches it in-page (so ChatGPT's auth cookies apply) and hands a data
     * URL back over the PpcBridge.
     */
    private fun observerJs(): String {
        val sel = Selectors.generatedImage[0].replace("\"", "\\\"")
        return """(function(){
          window.__ppcSeen=window.__ppcSeen||[];
          function snap(el){ return el.currentSrc||el.src||''; }
          var pre=document.querySelectorAll('$sel');
          for(var p=0;p<pre.length;p++){ window.__ppcSeen.push(snap(pre[p])); }
          if(window.__ppcObs){ try{window.__ppcObs.disconnect();}catch(e){} }
          var obs=new MutationObserver(function(){
            var imgs=document.querySelectorAll('$sel');
            for(var k=0;k<imgs.length;k++){
              var img=imgs[k]; var src=snap(img);
              if(!src || window.__ppcSeen.indexOf(src)>-1) continue;
              if(!(src.indexOf('http')===0 || src.indexOf('blob:')===0)) continue;
              if(!(img.complete && img.naturalWidth>50)) continue;
              window.__ppcSeen.push(src);
              try{obs.disconnect();}catch(e){}
              fetch(src).then(function(r){ if(!r.ok) throw new Error('http '+r.status); return r.blob(); })
                .then(function(b){ return new Promise(function(res,rej){
                    var fr=new FileReader();
                    fr.onload=function(){res(fr.result);};
                    fr.onerror=rej;
                    fr.readAsDataURL(b);
                });})
                .then(function(d){ PpcBridge.onImage(d); })
                .catch(function(e){ PpcBridge.onLog('image fetch failed: '+e); });
              return;
            }
          });
          window.__ppcObs=obs;
          obs.observe(document.body,{childList:true,subtree:true});
          return 'observing';
        })()"""
    }

    // ------------------------------------------------------------------ plumbing

    private inner class Bridge {
        @JavascriptInterface
        fun onImage(dataUrl: String) {
            if (imageHandled.compareAndSet(false, true)) {
                imageArrived?.complete(dataUrl)
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d(TAG, "page: $msg")
        }
    }

    /** evaluateJavascript must run on the UI thread; results come back JSON-quoted. */
    private suspend fun evalJs(js: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result ->
                if (!cont.isCompleted) cont.resume(result)
            }
        }
    }

    private fun unquote(result: String?): String? {
        if (result == null || result == "null" || result == "undefined") return null
        return try {
            JSONObject("{\"v\":$result}").optString("v")
        } catch (_: Exception) {
            result
        }
    }

    private suspend fun waitFor(
        timeoutMs: Long,
        pollMs: Long = 1000L,
        check: suspend () -> Boolean
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                if (check()) return true
            } catch (_: Exception) {
                // keep polling; the page may be mid-navigation
            }
            delay(pollMs)
        }
        return false
    }

    private fun dataUrlToBytes(dataUrl: String): ByteArray? {
        return try {
            val base64 = dataUrl.substringAfter(",", missingDelimiterValue = "")
            if (base64.isEmpty()) null else Base64.decode(base64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    private fun humanError(e: Exception): String = when (e) {
        is DriverException -> e.message ?: "Something went wrong."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }
}
