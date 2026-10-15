package app.photopromptcodes.automation

import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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

/**
 * Drives Qwen Chat's image editor (chat.qwen.ai) inside a WebView.
 *
 * No login required — image generation works as a guest. First use shows
 * an age-gate modal which the driver dismisses automatically.
 *
 * Flow: load page → dismiss age gate → open "+" menu → "Create Image" mode
 * → inject photo into the hidden file input → type prompt → send → wait for
 * the result image (served from img.alicdn.com) → download it.
 *
 * Same event interface as ChatGptDriver ([DriverEvent]), so the Run screen
 * works unchanged.
 */
class QwenDriver : EngineDriver {

    companion object {
        private const val TAG = "QwenDriver"
        private const val QWEN_URL = "https://chat.qwen.ai/"
        private const val PAGE_TIMEOUT_MS = 60_000L
        private const val ATTACH_TIMEOUT_MS = 30_000L
        private const val GENERATION_TIMEOUT_MS = 12 * 60_000L
    }

    private var webView: WebView? = null
    private var listener: ((DriverEvent) -> Unit)? = null
    private var pendingPhoto: Uri? = null
    private var imageArrived: CompletableDeferred<String>? = null
    private val imageHandled = AtomicBoolean(false)
    private var runJob: Job? = null
    private var currentPhoto: Uri? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun setListener(l: (DriverEvent) -> Unit) {
        listener = l
    }

    private fun emit(event: DriverEvent) {
        scope.launch(Dispatchers.Main) { listener?.invoke(event) }
    }

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
                    filePathCallback.onReceiveValue(arrayOf(uri))
                    true
                } else {
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
        wv.addJavascriptInterface(Bridge(), "PpcBridge")
    }

    inner class Bridge {
        @JavascriptInterface
        fun onImage(dataUrl: String) {
            if (imageHandled.compareAndSet(false, true)) {
                try { imageArrived?.complete(dataUrl) } catch (_: Exception) {}
            }
        }
    }

    override fun start(photo: Uri, prompt: String) {
        runJob?.cancel()
        imageHandled.set(false)
        imageArrived = CompletableDeferred()
        runJob = scope.launch {
            try {
                runEdit(photo, prompt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DriverExceptionWithDebug) {
                Log.w(TAG, "run failed", e)
                emit(DriverEvent.Failed(e.message ?: "Qwen edit failed.", e.debugInfo))
            } catch (e: Exception) {
                Log.w(TAG, "run failed", e)
                emit(DriverEvent.Failed(e.message ?: "Qwen edit failed. Please retry."))
            }
        }
    }

    override fun notifyLoginCompleted() {
        // Qwen needs no login — nothing to do.
    }

    override fun destroy() {
        runJob?.cancel()
        scope.cancel()
        webView = null
    }

    private suspend fun runEdit(photo: Uri, prompt: String) {
        val wv = webView ?: throw DriverException("Browser not ready. Please retry.")
        currentPhoto = photo

        // Watch for the age gate throughout the run — it can pop up at any
        // point (page load, after send, during generation) and blocks the page.
        val gateWatcher = scope.launch {
            while (true) {
                try {
                    dismissAgeGate()
                } catch (_: Exception) {}
                delay(3000)
            }
        }

        try {
            emit(DriverEvent.Step(RunStep.OPENING_ENGINE))
            withContext(Dispatchers.Main) { wv.loadUrl(QWEN_URL) }
            val loaded = waitFor(PAGE_TIMEOUT_MS) { pageReady() }
            if (!loaded) throw DriverException(
                "Qwen didn't finish loading. Check your connection and retry."
            )

            // Dismiss the age gate if it appears
            dismissAgeGate()

            emit(DriverEvent.Step(RunStep.ATTACHING_PHOTO))
            selectImageMode()
            attachPhoto()

            emit(DriverEvent.Step(RunStep.SENDING_PROMPT))
            sendPrompt(prompt)

            // Dismiss age gate again — it can appear on first send
            delay(2000)
            dismissAgeGate()

            delay(5000)

            emit(DriverEvent.Step(RunStep.GENERATING))
            val dataUrl = waitForImage()
            val bytes = dataUrlToBytes(dataUrl)
                ?: throw DriverException("Got the image back but couldn't decode it. Please retry.")

            emit(DriverEvent.Step(RunStep.DONE))
            emit(DriverEvent.Finished(bytes))
        } finally {
            gateWatcher.cancel()
        }
    }

    /** True when the composer is present AND the app has finished hydrating. */
    private suspend fun pageReady(): Boolean {
        val js = """(function(){
          var sels=[${QwenSelectors.composer.joinToString(",") { JSONObject.quote(it) }}];
          var hasComposer=false;
          for(var i=0;i<sels.length;i++){ if(document.querySelector(sels[i])){ hasComposer=true; break; } }
          if(!hasComposer) return false;
          // The React app shows "Model loading..." while hydrating — clicks
          // during this window hit a dead page. Wait for it to clear.
          var bodyText=(document.body?document.body.innerText:'');
          if(bodyText.indexOf('Model loading')>=0) return false;
          return true;
        })()"""
        return evalJs(webView!!, js) == "true"
    }

    /** Clicks the age-gate Continue button if the modal is visible. */
    private suspend fun dismissAgeGate() {
        val wv = webView ?: return
        repeat(3) {
            val visible = evalJs(wv, """(function(){
              var sels=[${QwenSelectors.ageGateVisible.joinToString(",") { JSONObject.quote(it) }}];
              for(var i=0;i<sels.length;i++){ if(document.querySelector(sels[i])) return true; }
              return false;
            })()""") == "true"
            if (!visible) return
            Log.d(TAG, "age gate visible — dismissing")
            evalJs(wv, """(function(){
              var sels=[${QwenSelectors.ageGateContinue.joinToString(",") { JSONObject.quote(it) }}];
              for(var i=0;i<sels.length;i++){
                var el=document.querySelector(sels[i]);
                if(el){ el.click(); return true; }
              }
              return false;
            })()""")
            delay(2000)
        }
    }

    /** Opens the "+" menu and selects "Create Image" mode. */
    private suspend fun selectImageMode() {
        val wv = webView ?: throw DriverException("Browser not ready.")
        // Already in image mode?
        val inMode = evalJs(wv, """(function(){
          var sels=[${QwenSelectors.imageModeChip.joinToString(",") { JSONObject.quote(it) }}];
          for(var i=0;i<sels.length;i++){ if(document.querySelector(sels[i])) return true; }
          // Also check for image-mode placeholder text in composer
          var ta=document.querySelector('textarea');
          if(ta && ta.placeholder && ta.placeholder.toLowerCase().indexOf('image')>=0) return true;
          return false;
        })()""") == "true"
        if (inMode) {
            Log.d(TAG, "already in image mode")
            return
        }
        // Click the "+" mode button with realistic mouse events (React needs
        // proper event bubbling — a bare .click() on a div can be ignored).
        // Retry up to 3 times, verifying the menu actually opened each time.
        var menuOpened = false
        repeat(3) {
            evalJs(wv, """(function(){
              function realClick(el){
                var r=el.getBoundingClientRect();
                var x=r.left+r.width/2, y=r.top+r.height/2;
                var opts={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};
                el.dispatchEvent(new MouseEvent('mousedown',opts));
                el.dispatchEvent(new MouseEvent('mouseup',opts));
                el.dispatchEvent(new MouseEvent('click',opts));
              }
              var sels=[${QwenSelectors.modeButton.joinToString(",") { JSONObject.quote(it) }}];
              for(var i=0;i<sels.length;i++){
                var el=document.querySelector(sels[i]);
                if(el){ realClick(el); return true; }
              }
              var ms=document.querySelector('.mode-select-open');
              if(ms){ realClick(ms); return true; }
              return false;
            })()""")
            delay(2500)
            menuOpened = evalJs(wv,
                "(document.querySelectorAll('[role=\"menuitem\"]').length>0 || " +
                "document.querySelectorAll('.mode-selector-drawer-list-item').length>0 || " +
                "document.querySelectorAll('.qwen-chat-v2-dropdown-menu-item').length>0)"
            ) == "true"
            if (menuOpened) return@repeat
            Log.d(TAG, "menu did not open, retrying (${it + 1}/3)")
        }
        if (!menuOpened) {
            val diagnostics = collectDiagnostics(wv)
            Log.w(TAG, "menu open failed. diagnostics: $diagnostics")
            throw DriverExceptionWithDebug(
                "Couldn't open the mode menu. Please retry.",
                diagnostics
            )
        }
        // Click "Create Image" — search broadly for any element with that text
        val selected = evalJs(wv, """(function(){
          function realClick(el){
            var r=el.getBoundingClientRect();
            var x=r.left+r.width/2, y=r.top+r.height/2;
            var opts={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};
            el.dispatchEvent(new MouseEvent('mousedown',opts));
            el.dispatchEvent(new MouseEvent('mouseup',opts));
            el.dispatchEvent(new MouseEvent('click',opts));
          }
          function clickCreateImage(){
            // Strategy 1: drawer list items (mobile/tablet layout)
            var drawers=document.querySelectorAll('.mode-selector-drawer-list-item');
            for(var i=0;i<drawers.length;i++){
              if(drawers[i].textContent.indexOf('Create Image')>=0){ realClick(drawers[i]); return true; }
            }
            // Strategy 2: known dropdown menu structure (desktop layout)
            var items=document.querySelectorAll('.qwen-chat-v2-dropdown-menu-item');
            for(var i=0;i<items.length;i++){
              var name=items[i].querySelector('.mode-select-dropdown-item-name');
              if(name && name.textContent.trim()==='Create Image'){ realClick(items[i]); return true; }
            }
            // Strategy 3: any clickable element whose text is exactly "Create Image"
            var all=document.querySelectorAll('div,span,button,li');
            for(var i=0;i<all.length;i++){
              if(all[i].textContent.trim()==='Create Image'){
                var el=all[i];
                while(el && el.tagName!=='BUTTON' && !el.getAttribute('role')){
                  if(el.className && el.className.indexOf('menu-item')>=0) break;
                  if(el.className && el.className.indexOf('drawer-list-item')>=0) break;
                  el=el.parentElement;
                }
                if(el){ realClick(el); return true; }
              }
            }
            // Strategy 4: partial text match on menu-like elements
            var menus=document.querySelectorAll('[role="menuitem"], .dropdown-menu-item, li, .mode-selector-drawer-list-item');
            for(var i=0;i<menus.length;i++){
              if(menus[i].textContent.indexOf('Create Image')>=0){ realClick(menus[i]); return true; }
            }
            return false;
          }
          return clickCreateImage();
        })()""") == "true"
        if (!selected) {
            // Capture full diagnostics for the error report
            val diagnostics = collectDiagnostics(wv)
            Log.w(TAG, "mode select failed. diagnostics: $diagnostics")
            throw DriverExceptionWithDebug(
                "Couldn't select Create Image mode. Please retry.",
                diagnostics
            )
        }
        delay(2000)
        Log.d(TAG, "image mode selected")
    }

    /** Dumps page state to help debug automation failures. Shown via "Copy details". */
    private suspend fun collectDiagnostics(wv: WebView): String {
        val sb = StringBuilder()
        sb.appendLine("=== QWEN DIAGNOSTICS ===")
        sb.appendLine("URL: ${evalJs(wv, "window.location.href")}")
        sb.appendLine("Title: ${evalJs(wv, "document.title")}")
        sb.appendLine()
        sb.appendLine("--- Mode button candidates ---")
        sb.appendLine(evalJs(wv, """(function(){
          var out=[];
          var els=document.querySelectorAll('[aria-label="Select Mode"], .mode-select-open, div[role="button"]');
          for(var i=0;i<Math.min(els.length,10);i++){
            var e=els[i];
            out.push(e.tagName+'.'+(e.className||'').toString().slice(0,60)+' aria-label='+(e.getAttribute('aria-label')||'none'));
          }
          return out.length? out.join('\n') : 'NONE FOUND';
        })()"""))
        sb.appendLine()
        sb.appendLine("--- Menu items (role=menuitem / drawer-list-item) ---")
        sb.appendLine(evalJs(wv, """(function(){
          var items=document.querySelectorAll('[role="menuitem"], .mode-selector-drawer-list-item, .qwen-chat-v2-dropdown-menu-item');
          if(!items.length) return 'NONE FOUND';
          var texts=[];
          for(var i=0;i<Math.min(items.length,12);i++){ texts.push((i+1)+'. '+items[i].textContent.trim().slice(0,50)); }
          return texts.join('\n');
        })()"""))
        sb.appendLine()
        sb.appendLine("--- Composer ---")
        sb.appendLine("textarea found: ${evalJs(wv, "!!document.querySelector('textarea.message-input-textarea')")}")
        sb.appendLine("image mode chip: ${evalJs(wv, "!!document.querySelector('.mode-select-current-mode')")}")
        sb.appendLine()
        sb.appendLine("--- Body text sample ---")
        sb.appendLine(evalJs(wv, "(document.body?document.body.innerText:'').slice(0,500)"))
        return sb.toString()
    }

    /** Exception carrying diagnostic info for the error report. */
    class DriverExceptionWithDebug(message: String, val debugInfo: String) : DriverException(message)

    /** Injects the photo into Qwen's hidden file input via DataTransfer. */
    private suspend fun attachPhoto() {
        val wv = webView ?: throw DriverException("Browser not ready.")
        val photo = currentPhoto ?: throw DriverException("No photo selected.")
        val b64 = downscaleToBase64(photo)
            ?: throw DriverException("Couldn't read the photo. Please pick it again.")

        val js = """(function(){
          var sels=[${QwenSelectors.fileInput.joinToString(",") { JSONObject.quote(it) }}];
          var input=null;
          for(var i=0;i<sels.length;i++){ input=document.querySelector(sels[i]); if(input) break; }
          if(!input) return 'no-input';
          var b64=${JSONObject.quote(b64)};
          var byteStr=atob(b64);
          var ab=new ArrayBuffer(byteStr.length);
          var ia=new Uint8Array(ab);
          for(var i=0;i<byteStr.length;i++){ ia[i]=byteStr.charCodeAt(i); }
          var file=new File([ab],'photo.jpg',{type:'image/jpeg'});
          var dt=new DataTransfer();
          dt.items.add(file);
          input.files=dt.files;
          input.dispatchEvent(new Event('change',{bubbles:true}));
          return 'ok';
        })()"""
        val result = withTimeoutOrNull(ATTACH_TIMEOUT_MS) {
            var r: String? = null
            val done = waitFor(ATTACH_TIMEOUT_MS) {
                r = evalJs(wv, js)
                r == "ok" || r == "no-input"
            }
            if (!done) null else r
        }
        if (result != "ok") throw DriverException(
            "Couldn't attach the photo to Qwen. Please retry."
        )
        // Wait for the upload preview to appear
        delay(3000)
        Log.d(TAG, "photo attached")
    }

    private fun downscaleToBase64(uri: Uri): String? {
        return try {
            val ctx = webView?.context?.applicationContext ?: return null
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
            input.close()
            val maxDim = 1024
            var sample = 1
            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
            val input2 = ctx.contentResolver.openInputStream(uri) ?: return null
            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeStream(input2, null, decodeOpts)
            input2.close() ?: Unit
            if (bmp == null) return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "downscale failed", e)
            null
        }
    }

    /** Types the prompt into Qwen's textarea and clicks send. */
    private suspend fun sendPrompt(prompt: String) {
        val wv = webView ?: throw DriverException("Browser not ready.")
        val escaped = JSONObject.quote(prompt)
        val typed = evalJs(wv, """(function(){
          var sels=[${QwenSelectors.composer.joinToString(",") { JSONObject.quote(it) }}];
          var ta=null;
          for(var i=0;i<sels.length;i++){ ta=document.querySelector(sels[i]); if(ta) break; }
          if(!ta) return 'no-composer';
          ta.focus();
          // Use execCommand for framework compatibility, fallback to value setter
          document.execCommand('selectAll', false, null);
          document.execCommand('insertText', false, $escaped);
          if(!ta.value){ ta.value=$escaped; }
          ta.dispatchEvent(new Event('input',{bubbles:true}));
          ta.dispatchEvent(new Event('change',{bubbles:true}));
          return 'ok';
        })()""")
        if (typed != "ok") throw DriverException("Couldn't type the prompt. Please retry.")
        delay(1500)
        val sent = evalJs(wv, """(function(){
          var sels=[${QwenSelectors.sendButton.joinToString(",") { JSONObject.quote(it) }}];
          for(var i=0;i<sels.length;i++){
            var el=document.querySelector(sels[i]);
            if(el && el.offsetParent!==null){ el.click(); return true; }
          }
          return false;
        })()""") == "true"
        if (!sent) throw DriverException("Couldn't send the prompt. Please retry.")
        Log.d(TAG, "prompt sent")
    }

    /**
     * Polls for the generated image. Qwen serves results from img.alicdn.com.
     * Returns a data URL (downloaded with the page's context) or the direct
     * CDN URL if download fails.
     */
    private suspend fun waitForImage(): String {
        val wv = webView ?: throw DriverException("Browser not ready.")
        val deadline = System.currentTimeMillis() + GENERATION_TIMEOUT_MS
        var lastCount = 0
        while (System.currentTimeMillis() < deadline) {
            // Ask the page for all candidate result image URLs
            val json = evalJs(wv, """(function(){
              var sels=[${QwenSelectors.resultImages.joinToString(",") { JSONObject.quote(it) }}];
              var urls=[];
              for(var i=0;i<sels.length;i++){
                var imgs=document.querySelectorAll(sels[i]);
                for(var j=0;j<imgs.length;j++){
                  var src=imgs[j].src||'';
                  if(src && urls.indexOf(src)<0) urls.push(src);
                }
              }
              return JSON.stringify(urls);
            })()""")
            val urls = try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }
            // Filter to alicdn result images, excluding UI icons/avatars
            val candidates = urls.filter { u ->
                u.contains("alicdn.com") && !u.contains("72-72") && u.length > 60
            }
            if (candidates.size > lastCount) {
                Log.d(TAG, "found ${candidates.size} candidate images")
                lastCount = candidates.size
            }
            // Wait until generation finishes (no spinner) and we have an image
            val generating = evalJs(wv, """(function(){
              var sels=[${QwenSelectors.generating.joinToString(",") { JSONObject.quote(it) }}];
              for(var i=0;i<sels.length;i++){ if(document.querySelector(sels[i])) return true; }
              return false;
            })()""") == "true"
            if (!generating && candidates.isNotEmpty()) {
                val url = candidates.last()
                Log.d(TAG, "harvesting $url")
                // Download the image directly (alicdn CDN needs no auth)
                val bytes = downloadUrl(url)
                if (bytes != null) {
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    return "data:image/jpeg;base64,$b64"
                }
                return url
            }
            delay(3000)
        }
        throw DriverException("Qwen took too long to generate. Please retry.")
    }

    private suspend fun downloadUrl(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            if (conn.responseCode != 200) return@withContext null
            val bytes = conn.inputStream.readBytes()
            conn.disconnect()
            if (bytes.size < 10_000) null else bytes
        } catch (e: Exception) {
            Log.w(TAG, "download failed: $url", e)
            null
        }
    }

    private fun dataUrlToBytes(dataUrl: String): ByteArray? {
        return try {
            if (dataUrl.startsWith("data:")) {
                val b64 = dataUrl.substringAfter("base64,")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
            null
        }
    }

    private suspend fun evalJs(wv: WebView, js: String): String? {
        return suspendCancellableCoroutine { cont ->
            try {
                wv.evaluateJavascript(js) { result ->
                    if (cont.isActive) cont.resume(result?.removeSurrounding("\""))
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun waitFor(timeoutMs: Long, cond: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond()) return true
            } catch (_: Exception) {}
            delay(1000)
        }
        return false
    }
}
