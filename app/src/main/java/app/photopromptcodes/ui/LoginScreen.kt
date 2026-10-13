package app.photopromptcodes.ui

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    var detected by mutableStateOf(false)
        private set

    fun attach(wv: WebView, onDetected: () -> Unit) {
        if (driver != null) return
        driver = ChatGptDriver().also { d ->
            d.attach(wv)
            d.loadChatGpt()
            // Poll for login state — auto-advance when the user signs in
            d.startLoginWatch { detected = true; onDetected() }
        }
    }

    fun markLoggedIn() {
        setLoggedInFlag(getApplication())
        driver?.notifyLoginCompleted()
    }

    override fun onCleared() {
        driver?.detach()
    }
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val vm: LoginViewModel = viewModel()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
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
                            // Auto-detected login — save and move on
                            scope.launch {
                                delay(1500) // let cookies settle
                                vm.markLoggedIn()
                                onDone()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // Bottom card: instructions + manual continue
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
                    Text(
                        "Log in with your email and password in the browser above. " +
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
