package app.photopromptcodes.ui

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.automation.ChatGptDriver
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PREFS = "ppc_prefs"
private const val KEY_LOGGED_IN = "chatgpt_logged_in"
private const val LOGIN_URL = "https://chatgpt.com/auth/login"

fun isLoggedInFlag(ctx: Context): Boolean =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOGGED_IN, false)

fun setLoggedInFlag(ctx: Context) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_LOGGED_IN, true).apply()
}

fun clearLoggedInFlag(ctx: Context) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().remove(KEY_LOGGED_IN).apply()
}

class LoginViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private var driver: ChatGptDriver? = null
    var pageLoaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    fun attach(wv: WebView, onDetected: () -> Unit) {
        if (driver != null) return
        driver = ChatGptDriver().also { d ->
            d.attach(wv)
            // Keep the driver's WebViewClient but hook page events for our UI
            val outer = wv.webViewClient
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    outer?.onPageStarted(view, url, favicon)
                    pageLoaded = false
                    loadError = null
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    outer?.onPageFinished(view, url)
                    pageLoaded = true
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    outer?.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        loadError = "Couldn't load the page (${error?.description}). Check your connection and tap Reload."
                    }
                }
            }
            wv.loadUrl(LOGIN_URL)
            d.startLoginWatch { onDetected() }
        }
    }

    fun reload() {
        loadError = null
        pageLoaded = false
    }

    fun markLoggedIn() {
        setLoggedInFlag(getApplication())
        driver?.notifyLoginCompleted()
    }

    override fun onCleared() {
        driver?.destroy()
    }
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val vm: LoginViewModel = viewModel()
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose { try { webView?.destroy() } catch (_: Exception) {} }
    }

    Scaffold(containerColor = NearBlack) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { c ->
                    WebView(c).also { wv ->
                        webView = wv
                        vm.attach(wv) {
                            scope.launch {
                                delay(1500)
                                vm.markLoggedIn()
                                onDone()
                            }
                        }
                    }
                },
                update = { wv ->
                    // Reload requested
                    if (vm.loadError == null && !vm.pageLoaded) {
                        // still loading, do nothing
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Loading spinner while the page loads
            if (!vm.pageLoaded && vm.loadError == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Amber)
                        Spacer(Modifier.height(12.dp))
                        Text("Loading ChatGPT…", color = PaperDim)
                    }
                }
            }

            // Bottom card
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Sign in to ChatGPT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    if (vm.loadError != null) {
                        Text(
                            vm.loadError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            OutlinedButton(
                                onClick = {
                                    vm.reload()
                                    webView?.loadUrl(LOGIN_URL)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reload")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    vm.markLoggedIn()
                                    onDone()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Amber,
                                    contentColor = NearBlack
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Continue", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Text(
                            "Log in with your email and password above. " +
                                "You only do this once — the app remembers you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PaperDim
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                vm.markLoggedIn()
                                onDone()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = NearBlack
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("I've signed in — continue", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
