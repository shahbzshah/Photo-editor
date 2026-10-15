package app.photopromptcodes.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.automation.ChatGptDriver
import app.photopromptcodes.automation.Engine
import app.photopromptcodes.automation.EngineDriver
import app.photopromptcodes.automation.QwenDriver
import app.photopromptcodes.data.EnginePrefs
import app.photopromptcodes.automation.DriverEvent
import app.photopromptcodes.automation.RunStep
import app.photopromptcodes.data.CodeRepository
import app.photopromptcodes.data.HistoryRepository
import app.photopromptcodes.data.PromptCode
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Danger
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class RunUi(
    val step: RunStep = RunStep.OPENING_ENGINE,
    val loginRequired: Boolean = false,
    val error: String? = null,
    val errorDebug: String? = null,
    val code: PromptCode? = null,
    val photoUri: Uri? = null
)

class RunViewModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf(RunUi())
        private set

    private val _done = MutableSharedFlow<String>()
    val doneEvents: SharedFlow<String> = _done

    private var driver: EngineDriver? = null
    private var started = false
    private val history = HistoryRepository(app)
    private var stageId: String? = null

    fun attachWebView(wv: WebView) {
        if (driver != null) return
        val engine = EnginePrefs.get(getApplication())
        driver = (if (engine == Engine.QWEN) QwenDriver() else ChatGptDriver()).also {
            it.setListener(::onEvent)
            it.attach(wv)
        }
    }

    /**
     * Called when the screen's AndroidView releases the WebView (navigated
     * away, backstack entry left behind). Frees the browser's native memory
     * immediately instead of waiting for ViewModel clear — on a 4GB device,
     * several live WebViews (one per run left in the backstack) add up to an
     * OOM crash "after some usage".
     */
    fun releaseWebView() {
        driver?.destroy()
        driver = null
    }

    fun ensureStarted(codeName: String, photoUriString: String, customPrompt: String? = null) {
        if (started) return
        started = true
        val photoUri = Uri.parse(photoUriString)
        viewModelScope.launch {
            val code = if (codeName == "__custom__" && customPrompt != null) {
                PromptCode(
                    code = "/custom",
                    category = "Custom",
                    description = customPrompt.take(80),
                    prompt = "Edit this photo: $customPrompt Keep the subject's identity and pose recognizable unless the instruction says otherwise. Photorealistic."
                )
            } else {
                CodeRepository.load(getApplication()).firstOrNull { it.code == codeName }
            }
            if (code == null) {
                ui = ui.copy(error = "Code not found: $codeName")
                return@launch
            }
            // Track as recently used
            app.photopromptcodes.data.RecentCodes.record(getApplication(), code.code)
            ui = ui.copy(code = code, photoUri = photoUri)
            stageId = try {
                history.stageOriginal(code.code, photoUri)
            } catch (e: Exception) {
                ui = ui.copy(error = "Couldn't read the selected photo. Please pick it again.")
                return@launch
            }
            driver?.start(photoUri, code.prompt)
                ?: run { ui = ui.copy(error = "Browser not ready. Please retry.") }
        }
    }

    fun retry() {
        val code = ui.code ?: return
        val photo = ui.photoUri ?: return
        ui = ui.copy(error = null, errorDebug = null, loginRequired = false, step = RunStep.OPENING_ENGINE)
        driver?.start(photo, code.prompt)
    }

    fun continueAfterLogin() {
        ui = ui.copy(loginRequired = false)
        driver?.notifyLoginCompleted()
    }

    private fun onEvent(event: DriverEvent) {
        when (event) {
            is DriverEvent.Step -> ui = ui.copy(step = event.step, error = null, errorDebug = null)
            DriverEvent.LoginRequired -> ui = ui.copy(loginRequired = true)
            is DriverEvent.Finished -> viewModelScope.launch {
                val id = stageId ?: return@launch
                val record = history.saveResult(id, ui.code?.code.orEmpty(), event.imageBytes)
                _done.emit(record.id)
            }
            is DriverEvent.Failed -> ui = ui.copy(error = event.message, errorDebug = event.debugInfo)
        }
    }

    override fun onCleared() {
        driver?.destroy()
        driver = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    code: String,
    photoUriString: String,
    customPrompt: String? = null,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
    vm: RunViewModel = viewModel()
) {
    LaunchedEffect(code, photoUriString) { vm.ensureStarted(code, photoUriString, customPrompt) }
    LaunchedEffect(Unit) { vm.doneEvents.collect { onDone(it) } }
    val ui = vm.ui

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.code?.code ?: code,
                        fontFamily = Mono,
                        fontWeight = FontWeight.SemiBold,
                        color = Amber
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = PaperDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NearBlack)
            )
        },
        containerColor = NearBlack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx -> WebView(ctx).also { vm.attachWebView(it) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { wv ->
                    vm.releaseWebView()
                    try {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.removeAllViews()
                        wv.destroy()
                    } catch (_: Exception) {
                    }
                }
            )
            // Progress overlay docked at the bottom; the WebView stays
            // interactive behind it (needed for the manual sign-in step).
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (ui.error != null) {
                        Text(
                            "Something went wrong",
                            style = MaterialTheme.typography.titleMedium,
                            color = Danger
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(ui.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = vm::retry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = NearBlack
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry", fontWeight = FontWeight.SemiBold)
                        }
                        // Copy error details for debugging (shows when diagnostics available)
                        if (ui.errorDebug != null) {
                            Spacer(Modifier.height(8.dp))
                            val ctx = LocalContext.current
                            var copied by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("error", ui.errorDebug))
                                    copied = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (copied) "Copied!" else "Copy error details")
                            }
                        }
                    } else if (ui.loginRequired) {
                        Text(
                            "Sign in required",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "ChatGPT isn't signed in. Sign in in the browser above, then tap Continue.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = vm::continueAfterLogin,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = NearBlack
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Continue", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        StepList(current = ui.step)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepList(current: RunStep) {
    val steps = RunStep.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step ->
            val index = steps.indexOf(step)
            val currentIndex = steps.indexOf(current)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    index < currentIndex -> Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Amber,
                        modifier = Modifier.size(20.dp)
                    )
                    index == currentIndex -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Amber
                    )
                    else -> Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index <= currentIndex)
                        MaterialTheme.colorScheme.onSurface
                    else
                        PaperDim
                )
            }
        }
    }
}
