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
            // Use a Chrome mobile UA so ChatGPT/Cloudflare doesn't block the WebView
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
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

    /** Loads ChatGPT's home page in the attached WebView (for the login screen). */
    fun loadChatGpt() {
        val wv = webView ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            wv.loadUrl(CHATGPT_URL)
        }
    }

    /**
     * Polls the page for a signed-in state and calls [onLogin] once.
     * Used by the first-run login screen for auto-detection.
     */
    fun startLoginWatch(onLogin: () -> Unit) {
        scope.launch {
            var fired = false
            while (!fired) {
                kotlinx.coroutines.delay(2500)
                try {
                    if (loginState() == "in") {
                        fired = true
                        CookieManager.getInstance().flush()
                        withContext(kotlinx.coroutines.Dispatchers.Main) { onLogin() }
                    }
                } catch (_: Exception) {
                }
            }
        }
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
        val photo = currentPhoto
            ?: throw DriverException("No photo to attach. Please pick one again.")
        // Direct injection: downscale the photo, convert to a File in the page,
        // and set it on ChatGPT's file input. Bypasses the menu entirely.
        val ctx = appContext()
        val b64 = downscaleToBase64(ctx, photo)
            ?: throw DriverException("Couldn't read the photo. Please pick it again.")
        val js = """(function(){
          var b64="$b64";
          var bin=atob(b64);var arr=new Uint8Array(bin.length);
          for(var i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);
          var file=new File([arr],"photo.jpg",{type:"image/jpeg"});
          var dt=new DataTransfer();dt.items.add(file);
          var input=document.querySelector('input[type="file"]');
          if(!input){input=document.createElement('input');input.type='file';input.style.display='none';document.body.appendChild(input);}
          input.files=dt.files;
          input.dispatchEvent(new Event('change',{bubbles:true}));
          return 'ok';
        })()"""
        val result = unquote(evalJs(js))
        if (result != "ok") {
            throw DriverException("Couldn't attach the photo. Try again.")
        }
        // Best-effort pause so the attachment thumbnail registers before we type.
        delay(3000)
    }

    /**
     * Reads [uri], downscales to max 1024px, compresses as JPEG,
     * and returns base64. Keeps the JS injection under ~500KB.
     */
    private fun downscaleToBase64(ctx: android.content.Context, uri: Uri): String? {
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            val bmp = android.graphics.BitmapFactory.decodeStream(input)
            input.close()
            if (bmp == null) return null
            val maxDim = 1024
            val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
            val w = (bmp.width * scale).toInt()
            val h = (bmp.height * scale).toInt()
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            } else bmp
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled != bmp) scaled.recycle()
            bmp.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private var currentPhoto: Uri? = null

    private fun appContext(): android.content.Context {
        // ChatGptDriver is created with application context via ViewModel
        return webView?.context?.applicationContext
            ?: throw DriverException("Browser not ready. Please retry.")
    }

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
        // Polling fallback: actively search for the image every 5s in case
        // the MutationObserver misses it (selector drift, timing, etc.)
        // The JS fetches and sends via PpcBridge itself.
        scope.launch {
            while (imageArrived?.isCompleted == false) {
                delay(5000)
                try { evalJs(pollImageJs()) } catch (_: Exception) {}
                // Fail fast if ChatGPT shows an upsell/limit wall instead of generating
                try {
                    val blocked = unquote(evalJs(checkBlockedJs()))
                    if (blocked == "blocked") {
                        try {
                            imageArrived?.completeExceptionally(
                                DriverException(
                                    "ChatGPT isn't generating images right now — it's showing an upgrade prompt. " +
                                    "You've likely hit the free image limit. Wait a while or upgrade, then try again."
                                )
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
        return try {
            withTimeoutOrNull(GENERATION_TIMEOUT_MS) { imageArrived!!.await() }
                ?: throw DriverException(
                    "Timed out waiting for the generated image (12 min). It may still appear in your ChatGPT history."
                )
        } catch (e: DriverException) {
            throw e
        }
    }

    /**
     * Detects ChatGPT's upsell / free-limit wall that appears instead of
     * generating an image. Returns 'blocked' if found.
     */
    private fun checkBlockedJs(): String {
        return """(function(){
          var t=(document.body?document.body.innerText:'').toLowerCase();
          var hints=['create richer images from your files','you have reached your image','image generation limit','upgrade to continue generating','daily limit'];
          for(var i=0;i<hints.length;i++){ if(t.indexOf(hints[i])>-1) return 'blocked'; }
          return 'ok';
        })()"""
    }

    /**
     * Actively searches for a generated image in assistant messages.
     * If found, fetches it and sends via PpcBridge (same as the observer).
     */
    private fun pollImageJs(): String {
        return """(function(){
          window.__ppcSeen=window.__ppcSeen||[];
          // STRICT: only images inside ChatGPT's (assistant) messages.
          // Never the user's uploaded photo.
          var sels=['[data-message-author-role="assistant"] img'];
          for(var s=0;s<sels.length;s++){
            var imgs=document.querySelectorAll(sels[s]);
            for(var k=0;k<imgs.length;k++){
              var img=imgs[k];var src=img.currentSrc||img.src||'';
              if(!src||window.__ppcSeen.indexOf(src)>-1)continue;
              if(!(src.indexOf('http')===0||src.indexOf('blob:')===0))continue;
              if(!(img.complete&&img.naturalWidth>200))continue;
              if(img.naturalWidth<200||img.naturalHeight<200)continue;
              // Skip the attachment thumbnail (small preview of the upload)
              var r=img.getBoundingClientRect();
              if(r.width<150||r.height<150)continue;
              window.__ppcSeen.push(src);
              fetch(src).then(function(r){return r.blob();})
                .then(function(b){return new Promise(function(res,rej){
                  var fr=new FileReader();fr.onload=function(){res(fr.result);};fr.onerror=rej;fr.readAsDataURL(b);});})
                .then(function(d){PpcBridge.onImage(d);})
                .catch(function(e){PpcBridge.onLog('poll fetch failed: '+e);});
              return 'fetching';
            }
          }
          return 'none';
        })()"""
    }

    // ------------------------------------------------------------------ JS

    private fun qsFirst(selectors: List<String>): String {
        val arr = selectors.joinToString(",") { JSONObject.quote(it) }
        return "(function(){var s=[$arr];for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(el)return el;}return null;})()"
    }

    /**
     * Dispatches realistic pointer/mouse/touch events on an element.
     * More reliable than .click() for React-based UIs like ChatGPT's.
     */
    private fun realTapJs(elJs: String): String {
        return """(function(){var el=$elJs;if(!el)return 'no';
          function ev(t,o){var e;try{e=new PointerEvent(t,o);}catch(_){e=document.createEvent('Event');e.initEvent(t,true,true);}
            try{Object.assign(e,o);}catch(_){} el.dispatchEvent(e);}
          var r=el.getBoundingClientRect();var o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,pointerId:1,isPrimary:true};
          ev('pointerdown',o);ev('mousedown',o);ev('pointerup',o);ev('mouseup',o);ev('click',o);
          try{el.click();}catch(_){}
          return 'ok';})()"""
    }

    private fun attachMenuJs(): String {
        val hints = Selectors.attachMenuTextHints.joinToString(",") { JSONObject.quote(it) }
        return """(function(){
          var hints=[$hints];
          function realTap(el){
            function ev(t,o){var e;try{e=new PointerEvent(t,o);}catch(_){e=document.createEvent('Event');e.initEvent(t,true,true);}
              try{Object.assign(e,o);}catch(_){} el.dispatchEvent(e);}
            var r=el.getBoundingClientRect();var o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,pointerId:1,isPrimary:true};
            ev('pointerdown',o);ev('mousedown',o);ev('pointerup',o);ev('mouseup',o);ev('click',o);
            try{el.click();}catch(_){}
          }
          var els=document.querySelectorAll('button,div[role="menuitem"],div[role="menuitemradio"],div[role="option"],li,[data-testid*="upload"],[data-testid*="photo"]');
          if(els.length==0){ els=document.querySelectorAll('*'); }
          for(var i=0;i<els.length;i++){
            var t=(els[i].innerText||els[i].textContent||'').trim().toLowerCase();
            if(t.length==0||t.length>40) continue;
            for(var h=0;h<hints.length;h++){
              if(t===hints[h]||t.indexOf(hints[h])>-1){ realTap(els[i]); return 'ok'; }
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
          // Snapshot ALL images in the DOM (including the user's uploaded
          // photo) so we never mistake them for the generated result.
          var all=document.querySelectorAll('img');
          for(var a=0;a<all.length;a++){ var s0=snap(all[a]); if(s0) window.__ppcSeen.push(s0); }
          var pre=document.querySelectorAll('$sel');
          for(var p=0;p<pre.length;p++){ window.__ppcSeen.push(snap(pre[p])); }
          if(window.__ppcObs){ try{window.__ppcObs.disconnect();}catch(e){} }
          var obs=new MutationObserver(function(){
            var imgs=document.querySelectorAll('$sel');
            for(var k=0;k<imgs.length;k++){
              var img=imgs[k]; var src=snap(img);
              if(!src || window.__ppcSeen.indexOf(src)>-1) continue;
              if(!(src.indexOf('http')===0 || src.indexOf('blob:')===0)) continue;
              if(!(img.complete && img.naturalWidth>200)) continue;
              var r=img.getBoundingClientRect();
              if(r.width<150||r.height<150) continue;
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
