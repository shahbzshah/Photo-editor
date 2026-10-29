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
    OPENING_ENGINE("Opening AI engine"),
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
    data class Failed(val message: String, val debugInfo: String? = null) : DriverEvent
}

open class DriverException(message: String, val debugInfo: String? = null) : Exception(message)

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
class ChatGptDriver : EngineDriver {

    companion object {
        private const val TAG = "ChatGptDriver"
        private const val CHATGPT_URL = "https://chatgpt.com/"
        private const val PAGE_TIMEOUT_MS = 60_000L
        // Opening ChatGPT's heavy page on a slow connection can take a while.
        private const val OPEN_TIMEOUT_MS = 180_000L
        private const val ATTACH_TIMEOUT_MS = 30_000L
        private const val SEND_TIMEOUT_MS = 30_000L
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

    override fun setListener(l: (DriverEvent) -> Unit) {
        listener = l
    }

    private fun emit(event: DriverEvent) {
        scope.launch { listener?.invoke(event) }
    }

    /**
     * Configures a WebView created by the UI layer. Must be called on the
     * main thread (AndroidView's factory runs there).
     */
    override fun attach(wv: WebView) {
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
        // Intercept downloads (ChatGPT's image download button).
        // We click it via JS and capture the file here with auth cookies.
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            Log.d(TAG, "download started: $url ($mimeType)")
            scope.launch {
                try {
                    when {
                        url.startsWith("data:", ignoreCase = true) -> {
                            // Already a data URL — hand it over as-is.
                            try { imageArrived?.complete(url) } catch (_: Exception) {}
                        }
                        url.startsWith("blob:", ignoreCase = true) -> {
                            // Native code cannot open blob: URLs, so fetch the
                            // blob inside the page (same renderer, auth applies)
                            // and let PpcBridge.onImage complete the run.
                            fetchDownloadBlobInPage(url)
                        }
                        else -> {
                            val bytes = downloadWithCookies(wv, url, userAgent)
                            if (bytes != null) {
                                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                val dataUrl = "data:$mimeType;base64,$b64"
                                try { imageArrived?.complete(dataUrl) } catch (_: Exception) {}
                            } else {
                                Log.w(TAG, "download produced no bytes: $url")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "download failed: $url : $e")
                }
            }
        }
    }

    /**
     * Fetches a blob: download URL from inside the page (native code cannot
     * open blob: URLs — only the renderer that created them can). The result
     * arrives asynchronously via PpcBridge.onImage, which completes the run.
     */
    private suspend fun fetchDownloadBlobInPage(url: String) {
        val q = JSONObject.quote(url)
        val js = """(function(){
          fetch($q).then(function(r){ if(!r.ok) throw new Error('http '+r.status); return r.blob(); })
            .then(function(b){ return new Promise(function(res,rej){
                var fr=new FileReader();
                fr.onload=function(){res(fr.result);};
                fr.onerror=rej;
                fr.readAsDataURL(b); });})
            .then(function(d){ PpcBridge.onImage(d); })
            .catch(function(e){ PpcBridge.onLog('blob download fetch failed: '+e); });
          return 'fetching';
        })()"""
        val res = unquote(evalJs(js))
        Log.d(TAG, "blob download fetch kicked off: $res")
    }

    /**
     * Downloads [url] using the WebView's cookies (for ChatGPT auth).
     */
    private suspend fun downloadWithCookies(wv: WebView, url: String, userAgent: String): ByteArray? {
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null) conn.setRequestProperty("Cookie", cookies)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.connect()
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Finds the download button on the latest generated image and clicks it.
     * Returns 'ok' if a button was found and clicked.
     */
    private fun clickDownloadJs(): String {
        return """(function(){
          // Find download buttons (aria-label or title containing 'download')
          var btns=document.querySelectorAll('button[aria-label*="ownload" i],button[title*="ownload" i],a[aria-label*="ownload" i],[data-testid*="download" i]');
          if(btns.length===0){
            // Fallback: look for buttons near the last large image
            var imgs=document.querySelectorAll('img');
            var lastImg=null;
            for(var i=imgs.length-1;i>=0;i--){
              if(imgs[i].naturalWidth>200&&imgs[i].naturalHeight>200){ lastImg=imgs[i]; break; }
            }
            if(!lastImg) return 'no-img';
            var container=lastImg.closest('div');
            for(var d=0;d<3&&container;d++){
              var b=container.querySelector('button');
              if(b){ btns=[b]; break; }
              container=container.parentElement;
            }
          }
          if(btns.length===0) return 'no-btn';
          // Click the LAST download button (most recent image)
          var btn=btns[btns.length-1];
          function realTap(el){
            function ev(t,o){var e;try{e=new PointerEvent(t,o);}catch(_){e=document.createEvent('Event');e.initEvent(t,true,true);}
              try{Object.assign(e,o);}catch(_){} el.dispatchEvent(e);}
            var r=el.getBoundingClientRect();var o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,pointerId:1,isPrimary:true};
            ev('pointerdown',o);ev('mousedown',o);ev('pointerup',o);ev('mouseup',o);ev('click',o);
            try{el.click();}catch(_){}
          }
          realTap(btn);
          return 'ok';
        })()"""
    }

    /** Starts (or restarts) an edit run. Any in-flight run is cancelled. */
    override fun start(photo: Uri, prompt: String) {
        runJob?.cancel()
        imageHandled.set(false)
        runJob = scope.launch {
            try {
                runEdit(photo, prompt)
            } catch (e: CancellationException) {
                // superseded by a newer run — stay silent
            } catch (e: Exception) {
                emit(DriverEvent.Failed(humanError(e), (e as? DriverException)?.debugInfo))
            }
        }
    }

    /** Called by the UI after the user finishes the manual sign-in. */
    override fun notifyLoginCompleted() {
        CookieManager.getInstance().flush()
        loginGate?.complete(Unit)
    }

    /**
     * Runs one rescue scan right now. Only acts while a run is waiting for
     * its image; safe to call any other time (no-op).
     */
    override fun requestRescueScan() {
        scope.launch {
            try {
                if (imageArrived?.isCompleted == false) evalJs(rescueScanJs())
            } catch (_: Exception) {
            }
        }
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

    override fun destroy() {
        runJob?.cancel()
        scope.cancel()
        try {
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
    }

    // ------------------------------------------------------------------ flow

    /**
     * Detects Cloudflare / human-verification interstitial pages. These show
     * neither the composer nor login buttons, so without this check the open
     * phase just burns its whole timeout looking "stuck".
     */
    private suspend fun isChallengePage(): Boolean {
        val js = """(function(){
          var t=(document.body?document.body.innerText:'').toLowerCase();
          var h=['verify you are human','attention required','checking your browser','verify your identity'];
          for(var i=0;i<h.length;i++){ if(t.indexOf(h[i])>-1) return true; }
          return !!document.querySelector('iframe[src*="challenges.cloudflare.com"], [id*="cf-challenge"], [class*="cf-challenge"]');
        })()"""
        return unquote(evalJs(js)) == "true"
    }

    /**
     * Waits for ChatGPT's open phase to resolve. Returns "in", "out",
     * "challenge" (human-verification interstitial — the user can complete it
     * in the visible browser), or "timeout".
     */
    private suspend fun waitForOpen(timeoutMs: Long): String {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                if (isChallengePage()) return "challenge"
                val state = loginState()
                if (state == "in" || state == "out") return state
            } catch (_: Exception) {
                // Page mid-navigation — keep polling.
            }
            delay(1000)
        }
        return "timeout"
    }

    private suspend fun runEdit(photo: Uri, prompt: String) {
        val wv = webView ?: throw DriverException("Browser not ready. Please retry.")
        currentPhoto = photo

        emit(DriverEvent.Step(RunStep.OPENING_ENGINE))
        withContext(Dispatchers.Main) { wv.loadUrl(CHATGPT_URL) }
        when (waitForOpen(OPEN_TIMEOUT_MS)) {
            "challenge" -> throw DriverException(
                "ChatGPT is showing a human-verification check. Tap the checkbox in the browser above, " +
                "wait for ChatGPT to finish loading, then retry the edit."
            )
            "timeout" -> throw DriverException(
                "ChatGPT didn't finish loading after 3 minutes. Check your connection and retry."
            )
        }

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

        // Let the user message (with the uploaded photo) render fully
        // before we snapshot. Otherwise the snapshot misses it and the
        // harvester mistakes the upload for the generated result.
        delay(5000)

        emit(DriverEvent.Step(RunStep.GENERATING))
        val dataUrl = waitForImage()
        val bytes = dataUrlToBytes(dataUrl)
            ?: throw DriverException("Got the image back but couldn't decode it. Please retry.")

        emit(DriverEvent.Step(RunStep.DONE))
        emit(DriverEvent.Finished(bytes))
    }

    private suspend fun loginState(): String {
        // ChatGPT now shows a working-looking composer even when logged out,
        // and its "Log in" controls are buttons (not links), so neither the
        // old composer check nor the old link selectors can be trusted alone.
        // Rule: composer counts as signed-in ONLY when no login affordance
        // is present; a login button/text always wins (a false "out" just
        // asks the user to confirm sign-in, while a false "in" sends their
        // photo into a login wall and hangs the run).
        val js = """(function(){
          var loginSels=[${Selectors.loginIndicators.joinToString(",") { JSONObject.quote(it) }}];
          var composerSels=[${Selectors.composer.joinToString(",") { JSONObject.quote(it) }}];
          function any(sels){ for(var i=0;i<sels.length;i++){ if(document.querySelector(sels[i])) return true; } return false; }
          var bodyText=(document.body?document.body.innerText:'').slice(0,3000);
          var loginBtn=false;
          try{
            var btns=document.querySelectorAll('button');
            for(var b=0;b<btns.length;b++){ if(/^\s*log\s*in\s*$/i.test(btns[b].textContent||'')){ loginBtn=true; break; } }
          }catch(e){}
          var loggedOut = any(loginSels) || loginBtn || /log\s*in/i.test(bodyText);
          if(any(composerSels) && !loggedOut) return 'in';
          if(loggedOut) return 'out';
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
            "Couldn't type into the ChatGPT message box. Please retry — if it keeps happening, copy the error details and send them to me.",
            unquote(evalJs(diagnoseComposerJs()))
        )
        // Baseline: how many user messages exist before we send, so we can
        // tell a real send apart from a tap that did nothing.
        val beforeCount = unquote(evalJs(userMessageCountJs()))?.toIntOrNull() ?: 0
        // The send button is disabled until the typed text (and the photo
        // upload) registers in ChatGPT's UI, and the toolbar can re-render
        // after typing — so poll for an enabled, visible button instead of
        // looking once and giving up.
        val deadline = SystemClock.uptimeMillis() + SEND_TIMEOUT_MS
        var lastDiag: String? = null
        var sent = false
        while (SystemClock.uptimeMillis() < deadline && !sent) {
            val tap = unquote(evalJs(tapSendButtonJs()))
            if (tap == "ok") {
                delay(2000)
                if (unquote(evalJs(sentCheckJs(beforeCount))) == "sent") {
                    sent = true
                }
                // Tapped but nothing sent (upload still processing, button
                // re-rendered): keep polling, the button comes back enabled.
            } else if (tap != null && tap.startsWith("diag:")) {
                lastDiag = tap.removePrefix("diag:")
            }
            if (!sent) delay(500)
        }
        if (!sent) {
            // Fallback that needs no button at all: ChatGPT sends on Enter.
            unquote(evalJs(enterToSendJs()))
            delay(2000)
            if (unquote(evalJs(sentCheckJs(beforeCount))) == "sent") sent = true
        }
        if (!sent) throw DriverException(
            "ChatGPT's send button never became available, so the message didn't go. " +
                "Please retry — if it keeps happening, copy the error details and send them to me.",
            "Last button scan: ${lastDiag ?: "none"}\n${unquote(evalJs(diagnoseComposerJs()))}"
        )
        delay(2000)
    }

    private suspend fun waitForImage(): String {
        imageArrived = CompletableDeferred()
        evalJs(observerJs())
        // Polling fallback: actively search for the image every 5s in case
        // the MutationObserver misses it (selector drift, timing, etc.)
        // The JS fetches and sends via PpcBridge itself.
        // Also try ChatGPT's download button every 15s — the DownloadListener
        // captures the file directly, bypassing URL harvesting entirely.
        var polls = 0
        val waitStarted = SystemClock.uptimeMillis()
        var rescueAttempts = 0
        scope.launch {
            while (imageArrived?.isCompleted == false) {
                delay(5000)
                polls++
                try { evalJs(pollImageJs()) } catch (_: Exception) {}
                // Every 3rd poll (15s), try the download button
                if (polls % 3 == 0) {
                    try {
                        val dl = unquote(evalJs(clickDownloadJs()))
                        if (dl == "ok") Log.d(TAG, "clicked download button")
                    } catch (_: Exception) {}
                }
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
                // Re-arm the observer every 30s: a full page reload (login
                // refresh, navigation) drops the injected watcher, and the
                // re-install keeps the existing seen-list on the same
                // document (see observerJs).
                if (polls % 6 == 0) {
                    try { evalJs(observerJs()) } catch (_: Exception) {}
                }
                // Rescue: once the turn looks finished (send button back,
                // no stop button) or 90s have passed, scan with looser
                // filters for the newest assistant image. At most twice.
                if (rescueAttempts < 2 &&
                    SystemClock.uptimeMillis() - waitStarted > 25_000L
                ) {
                    val finished = try {
                        unquote(evalJs(turnFinishedJs())) == "done"
                    } catch (_: Exception) { false }
                    if (finished || SystemClock.uptimeMillis() - waitStarted > 90_000L) {
                        rescueAttempts++
                        try { evalJs(rescueScanJs()) } catch (_: Exception) {}
                    }
                }
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
          // AGGRESSIVE: check every image on the page. The __ppcSeen snapshot
          // (taken when waiting started) already excludes the uploaded photo,
          // so any NEW large image must be the generated result.
          var imgs=document.querySelectorAll('img');
          var candidates=[];
          for(var k=0;k<imgs.length;k++){
            var img=imgs[k];var src=img.currentSrc||img.src||'';
            if(!src||window.__ppcSeen.indexOf(src)>-1)continue;
            // Accept http, https, blob, and data URLs
            if(!(src.indexOf('http')===0||src.indexOf('blob:')===0||src.indexOf('data:image')===0))continue;
            if(!img.complete)continue;
            if(img.naturalWidth<200||img.naturalHeight<200)continue;
            var inAssistant=false; try{ inAssistant=!!img.closest('[data-message-author-role="assistant"]'); }catch(e){}
            if(!inAssistant){ var r=img.getBoundingClientRect(); if(r.width<150||r.height<150)continue; }
            // Skip UI icons/logos by checking the src
            var ls=src.toLowerCase();
            if(ls.indexOf('logo')>-1||ls.indexOf('icon')>-1||ls.indexOf('avatar')>-1)continue;
            try{ if(img.closest('[data-message-author-role="user"]'))continue; }catch(e){}
            candidates.push({img:img,src:src});
          }
          if(candidates.length===0)return 'none';
          // Take the LAST candidate in DOM order (chat order = oldest first,
          // so the generated image, which comes after the upload, is last)
          var best=candidates[candidates.length-1];var bsrc=best.src;
          window.__ppcSeen.push(bsrc);
          if(bsrc.indexOf('data:image')===0){ PpcBridge.onImage(bsrc); return 'direct'; }
          fetch(bsrc).then(function(r){return r.blob();})
            .then(function(b){return new Promise(function(res,rej){
              var fr=new FileReader();fr.onload=function(){res(fr.result);};fr.onerror=rej;fr.readAsDataURL(b);});})
            .then(function(d){PpcBridge.onImage(d);})
            .catch(function(e){PpcBridge.onLog('poll fetch failed: '+e);
              var ix=window.__ppcSeen.indexOf(bsrc); if(ix>-1) window.__ppcSeen.splice(ix,1);});
          return 'fetching';
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
     * Finds an enabled, visible send button and taps it with realistic
     * pointer events (React UIs often ignore a bare .click()). Skips the
     * stop button (same id while a reply is generating) and disabled or
     * hidden buttons. Returns 'ok', or 'diag:<button inventory>' so a
     * failure can say what the page actually had.
     */
    private fun tapSendButtonJs(): String {
        val sels = Selectors.sendButton.joinToString(",") { JSONObject.quote(it) }
        return """(function(){
          var sels=[$sels];
          function visible(el){ var r=el.getBoundingClientRect(); var s=window.getComputedStyle(el);
            return r.width>0 && r.height>0 && s.display!=='none' && s.visibility!=='hidden'; }
          function describe(el){ return (el.tagName||'?').toLowerCase()+'#'+(el.id||'')+
            '[testid='+(el.getAttribute('data-testid')||'')+'][label='+(el.getAttribute('aria-label')||'')+']'+
            ' disabled='+!!el.disabled+' aria-disabled='+el.getAttribute('aria-disabled')+' visible='+visible(el); }
          var seen=[]; var inv=[];
          var stopPresent=false;
          for(var i=0;i<sels.length;i++){
            var el=document.querySelector(sels[i]);
            if(!el) continue;
            if(el.getAttribute('data-testid')==='stop-button'){ stopPresent=true; inv.push('STOP: '+describe(el)); continue; }
            var key=describe(el);
            if(seen.indexOf(key)>-1) continue; seen.push(key); inv.push(key);
            if(el.disabled || el.getAttribute('aria-disabled')==='true') continue;
            if(!visible(el)) continue;
            function realTap(t){
              function ev(n,o){ var e; try{ e=new PointerEvent(n,o); }catch(_){ e=document.createEvent('Event'); e.initEvent(n,true,true); }
                try{ Object.assign(e,o); }catch(_){} t.dispatchEvent(e); }
              var r=t.getBoundingClientRect();
              var o={bubbles:true,cancelable:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2,pointerId:1,isPrimary:true};
              ev('pointerdown',o); ev('mousedown',o); ev('pointerup',o); ev('mouseup',o); ev('click',o);
              try{ t.click(); }catch(_){}
            }
            realTap(el);
            return 'ok';
          }
          if(stopPresent) inv.push('note: a previous reply is still generating');
          return 'diag:'+inv.join(' | ');
        })()"""
    }

    /** Sends with the Enter key — needs no send button at all. */
    private fun enterToSendJs(): String {
        val composer = qsFirst(Selectors.composer)
        return """(function(){
          var el=$composer;
          if(!el) return 'no-composer';
          el.focus();
          function key(t){ var e;
            try{ e=new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}); }
            catch(_){ e=document.createEvent('Event'); e.initEvent(t,true,true); }
            el.dispatchEvent(e); }
          key('keydown'); key('keypress'); key('keyup');
          return 'ok';
        })()"""
    }

    private fun userMessageCountJs(): String {
        return "(function(){return String(document.querySelectorAll('[data-message-author-role=\"user\"]').length);})()"
    }

    /**
     * 'sent' when the composer emptied (ChatGPT clears it on send) or a
     * new user message appeared since [beforeCount].
     */
    private fun sentCheckJs(beforeCount: Int): String {
        val composer = qsFirst(Selectors.composer)
        return """(function(){
          var el=$composer;
          var txt=el?(el.innerText||el.textContent||''):'';
          var users=document.querySelectorAll('[data-message-author-role="user"]').length;
          return (txt.trim().length===0 || users>$beforeCount)?'sent':'pending';
        })()"""
    }

    /** One-line snapshot of the composer state, for copyable error details. */
    private fun diagnoseComposerJs(): String {
        val composer = qsFirst(Selectors.composer)
        return """(function(){
          var el=$composer;
          if(!el) return 'composer: MISSING';
          var txt=el.innerText||el.textContent||'';
          var btns=document.querySelectorAll('button');
          var ids=[];
          for(var i=0;i<btns.length && ids.length<12;i++){
            var b=btns[i];
            ids.push((b.id||'')+'/'+(b.getAttribute('data-testid')||'')+'/'+(b.getAttribute('aria-label')||''));
          }
          return 'composer: present, textLen='+txt.trim().length+' buttons['+btns.length+']: '+ids.join(' | ');
        })()"""
    }

    /** 'done' when the turn looks finished: stop button gone, send button back. */
    private fun turnFinishedJs(): String {
        return """(function(){
          if(document.querySelector('[data-testid="stop-button"]')) return 'busy';
          var send=document.querySelector('#composer-submit-button, button[data-testid="send-button"]');
          return send?'done':'unknown';
        })()"""
    }

    /**
     * Loose rescue scan: the newest large image that is not the user's own
     * upload, with no on-screen size requirement and no seen-list — for
     * when the strict watcher missed a finished image (collapsed, lazy,
     * or rendered before the watcher armed). Prefers images inside an
     * assistant message. Fetches and hands over via PpcBridge like the
     * observer does. Safe to run repeatedly: already-rescued URLs are
     * skipped (unless their fetch failed), and the bridge only accepts
     * the first image.
     */
    private fun rescueScanJs(): String {
        return """(function(){
          window.__ppcRescued=window.__ppcRescued||[];
          var imgs=document.querySelectorAll('img');
          var fallback=null; var best=null;
          for(var k=imgs.length-1;k>=0;k--){
            var img=imgs[k]; var src=img.currentSrc||img.src||'';
            if(!src) continue;
            if(window.__ppcRescued.indexOf(src)>-1) continue;
            if(!(src.indexOf('http')===0||src.indexOf('blob:')===0||src.indexOf('data:image')===0)) continue;
            if(!img.complete) continue;
            if(img.naturalWidth<150||img.naturalHeight<150) continue;
            var ls=src.toLowerCase();
            if(ls.indexOf('logo')>-1||ls.indexOf('icon')>-1||ls.indexOf('avatar')>-1) continue;
            try{ if(img.closest('[data-message-author-role="user"]')) continue; }catch(e){}
            if(!fallback) fallback={img:img,src:src};
            try{ if(img.closest('[data-message-author-role="assistant"]')){ best={img:img,src:src}; break; } }catch(e){}
          }
          best=best||fallback;
          if(!best) return 'none';
          window.__ppcRescued.push(best.src);
          if(best.src.indexOf('data:image')===0){ PpcBridge.onImage(best.src); return 'found'; }
          fetch(best.src).then(function(r){ if(!r.ok) throw new Error('http '+r.status); return r.blob(); })
            .then(function(b){ return new Promise(function(res,rej){
                var fr=new FileReader(); fr.onload=function(){res(fr.result);}; fr.onerror=rej; fr.readAsDataURL(b); });})
            .then(function(d){ PpcBridge.onImage(d); })
            .catch(function(e){ PpcBridge.onLog('rescue fetch failed: '+e);
              var ix=window.__ppcRescued.indexOf(best.src); if(ix>-1) window.__ppcRescued.splice(ix,1); });
          return 'found';
        })()"""
    }

    /**
     * Watches for a NEW, fully-loaded image inside assistant messages, then
     * fetches it in-page (so ChatGPT's auth cookies apply) and hands a data
     * URL back over the PpcBridge.
     */
    private fun observerJs(): String {
        return """(function(){
          window.__ppcSeen=window.__ppcSeen||[];
          function snap(el){ return el.currentSrc||el.src||''; }
          // First install on this document only: snapshot every image already
          // present (the uploaded photo above all) so a later scan never
          // mistakes it for the result. Re-installs on the same document
          // keep the existing list — re-snapshotting would poison it with
          // a finished-but-unharvested image.
          if(!window.__ppcSnapDone){
            window.__ppcSnapDone=true;
            var all=document.querySelectorAll('img');
            for(var a=0;a<all.length;a++){ var s0=snap(all[a]); if(s0) window.__ppcSeen.push(s0); }
          }
          if(window.__ppcObs){ try{window.__ppcObs.disconnect();}catch(e){} }
          function tryHarvest(){
            var imgs=document.querySelectorAll('img');
            var best=null;
            for(var k=0;k<imgs.length;k++){
              var img=imgs[k]; var src=snap(img);
              if(!src || window.__ppcSeen.indexOf(src)>-1) continue;
              if(!(src.indexOf('http')===0||src.indexOf('blob:')===0||src.indexOf('data:image')===0)) continue;
              if(!img.complete) continue;
              if(img.naturalWidth<200||img.naturalHeight<200) continue;
              // Images inside an assistant message are the result (or an
              // earlier turn's) even when collapsed or off-screen, so they
              // skip the on-screen size check. Anything else must be visibly
              // rendered, or it is a tracking pixel or hidden UI image.
              var inAssistant=false;
              try{ inAssistant=!!img.closest('[data-message-author-role="assistant"]'); }catch(e){}
              if(!inAssistant){
                var r=img.getBoundingClientRect();
                if(r.width<150||r.height<150) continue;
              }
              var ls=src.toLowerCase();
              if(ls.indexOf('logo')>-1||ls.indexOf('icon')>-1||ls.indexOf('avatar')>-1) continue;
              // Never harvest the user's own uploaded photo: skip anything
              // inside a user-authored message bubble.
              try{ if(img.closest('[data-message-author-role="user"]')) continue; }catch(e){}
              // Keep the LAST match in DOM order (newest in chat)
              best={img:img,src:src};
            }
            if(!best) return false;
            window.__ppcSeen.push(best.src);
            try{window.__ppcObs.disconnect();}catch(e){}
            if(best.src.indexOf('data:image')===0){ PpcBridge.onImage(best.src); return true; }
            fetch(best.src).then(function(r){ if(!r.ok) throw new Error('http '+r.status); return r.blob(); })
              .then(function(b){ return new Promise(function(res,rej){
                  var fr=new FileReader();
                  fr.onload=function(){res(fr.result);};
                  fr.onerror=rej;
                  fr.readAsDataURL(b);
              });})
              .then(function(d){ PpcBridge.onImage(d); })
              .catch(function(e){ PpcBridge.onLog('image fetch failed: '+e);
                var ix=window.__ppcSeen.indexOf(best.src); if(ix>-1) window.__ppcSeen.splice(ix,1); });
            return true;
          }
          var obs=new MutationObserver(function(){ tryHarvest(); });
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
        // Timeout guard: a frozen WebView never calls back.
        withTimeoutOrNull(15000) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(js) { result ->
                    if (!cont.isCompleted) cont.resume(result)
                }
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
