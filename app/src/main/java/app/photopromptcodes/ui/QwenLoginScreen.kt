package app.photopromptcodes.ui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.PaperDim

private const val PREFS = "ppc_prefs"
private const val KEY_QWEN_LOGGED_IN = "qwen_logged_in"

fun isQwenLoggedIn(ctx: Context): Boolean =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_QWEN_LOGGED_IN, false)

fun setQwenLoggedIn(ctx: Context, value: Boolean) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_QWEN_LOGGED_IN, value).apply()
}

/**
 * WebView for Qwen sign-in via email OTP.
 * Qwen uses email one-time-code (no password), which works fine in a WebView.
 * Cookies persist, so the login survives app restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QwenLoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Detect successful login: auth page redirects away,
                    // or the "Log in" button disappears
                    if (url != null && !url.contains("/auth")) {
                        // Check via JS if we're logged in
                        view.evaluateJavascript(
                            """(function(){
                                var loginBtn = document.querySelector('a[href*="/auth"], button');
                                var bodyText = document.body ? document.body.innerText : '';
                                // Logged in if no "Log in" button in header
                                return !bodyText.slice(0, 2000).includes('Log in');
                            })()"""
                        ) { result ->
                            if (result == "true") {
                                setQwenLoggedIn(ctx, true)
                            }
                        }
                    }
                }
            }
            loadUrl("https://chat.qwen.ai/auth")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Qwen") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack)
            )
        },
        containerColor = NearBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "Enter your email — Qwen will send a one-time code. " +
                    "Enter the code to sign in. The app remembers you.",
                color = PaperDim
            )
            Spacer(Modifier.height(12.dp))
            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    // User indicates they're done; check login state
                    webView.evaluateJavascript(
                        """document.body.innerText.slice(0,2000).includes('Log in') ? 'out' : 'in'"""
                    ) { result ->
                        if (result?.contains("in") == true) {
                            setQwenLoggedIn(ctx, true)
                        }
                        onDone()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = NearBlack
                )
            ) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
