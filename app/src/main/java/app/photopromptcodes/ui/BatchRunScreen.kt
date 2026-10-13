package app.photopromptcodes.ui

import android.app.Application
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.automation.ChatGptDriver
import app.photopromptcodes.automation.DriverEvent
import app.photopromptcodes.automation.RunStep
import app.photopromptcodes.data.CodeRepository
import app.photopromptcodes.data.HistoryRepository
import app.photopromptcodes.data.PromptCode
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Danger
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

data class BatchUi(
    val step: RunStep = RunStep.OPENING_CHATGPT,
    val loginRequired: Boolean = false,
    val error: String? = null,
    val code: PromptCode? = null,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val completedIds: List<String> = emptyList(),
    val failedCount: Int = 0,
    val isDone: Boolean = false
)

class BatchRunViewModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf(BatchUi())
        private set

    private var driver: ChatGptDriver? = null
    private var started = false
    private val history = HistoryRepository(app)
    private var photoQueue: List<Uri> = emptyList()
    private var stageId: String? = null

    fun attachWebView(wv: WebView) {
        if (driver != null) return
        driver = ChatGptDriver().also {
            it.setListener(::onEvent)
            it.attach(wv)
        }
    }

    fun ensureStarted(codeName: String, photoUriStrings: List<String>, customPrompt: String? = null) {
        if (started) return
        started = true
        photoQueue = photoUriStrings.map { Uri.parse(it) }
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
            app.photopromptcodes.data.RecentCodes.record(getApplication(), code.code)
            ui = ui.copy(code = code, total = photoQueue.size)
            startNext()
        }
    }

    private fun startNext() {
        val idx = ui.currentIndex
        if (idx >= photoQueue.size) {
            ui = ui.copy(isDone = true)
            return
        }
        val photoUri = photoQueue[idx]
        viewModelScope.launch {
            stageId = try {
                history.stageOriginal(ui.code?.code ?: "batch", photoUri)
            } catch (e: Exception) {
                ui = ui.copy(error = "Couldn't read photo ${idx + 1}. Skipping.")
                ui = ui.copy(currentIndex = idx + 1, failedCount = ui.failedCount + 1)
                startNext()
                return@launch
            }
            ui = ui.copy(step = RunStep.OPENING_CHATGPT, error = null, loginRequired = false)
            driver?.start(photoUri, ui.code?.prompt ?: "")
                ?: run { ui = ui.copy(error = "Browser not ready. Please retry.") }
        }
    }

    fun retry() {
        ui = ui.copy(error = null, loginRequired = false, step = RunStep.OPENING_CHATGPT)
        val photoUri = photoQueue.getOrNull(ui.currentIndex) ?: return
        driver?.start(photoUri, ui.code?.prompt ?: "")
    }

    fun skipCurrent() {
        val idx = ui.currentIndex
        ui = ui.copy(currentIndex = idx + 1, failedCount = ui.failedCount + 1, error = null)
        startNext()
    }

    fun continueAfterLogin() {
        ui = ui.copy(loginRequired = false)
        driver?.notifyLoginCompleted()
    }

    private fun onEvent(event: DriverEvent) {
        when (event) {
            is DriverEvent.Step -> ui = ui.copy(step = event.step, error = null)
            DriverEvent.LoginRequired -> ui = ui.copy(loginRequired = true)
            is DriverEvent.Finished -> viewModelScope.launch {
                val id = stageId ?: return@launch
                val record = history.saveResult(id, ui.code?.code.orEmpty(), event.imageBytes)
                val idx = ui.currentIndex
                ui = ui.copy(
                    currentIndex = idx + 1,
                    completedIds = ui.completedIds + record.id
                )
                startNext()
            }
            is DriverEvent.Failed -> ui = ui.copy(error = event.message)
        }
    }

    override fun onCleared() {
        driver?.destroy()
        driver = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRunScreen(
    code: String,
    photoUriStrings: List<String>,
    customPrompt: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
    vm: BatchRunViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(code) { vm.ensureStarted(code, photoUriStrings, customPrompt) }
    val ui = vm.ui

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ui.total > 0) "Batch ${ui.currentIndex.coerceAtMost(ui.total)}/${ui.total}" else "Batch edit",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (ui.total > 0 && !ui.isDone) {
                LinearProgressIndicator(
                    progress = { ui.currentIndex.toFloat() / ui.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Amber,
                    trackColor = SurfaceDark
                )
                Spacer(Modifier.height(16.dp))
            }

            when {
                ui.isDone -> {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Amber,
                        modifier = Modifier.padding(24.dp)
                    )
                    Text(
                        "Batch complete!",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Paper,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${ui.completedIds.size} of ${ui.total} photos edited" +
                            if (ui.failedCount > 0) " (${ui.failedCount} failed)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = NearBlack
                        )
                    ) {
                        Text("View in History", fontWeight = FontWeight.SemiBold)
                    }
                }
                ui.error != null -> {
                    Text(
                        ui.error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Danger
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.retry() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = NearBlack
                            )
                        ) { Text("Retry") }
                        Button(
                            onClick = { vm.skipCurrent() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceDark,
                                contentColor = Paper
                            )
                        ) { Text("Skip this photo") }
                    }
                }
                ui.loginRequired -> {
                    Text(
                        "Please sign in to ChatGPT to continue the batch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.continueAfterLogin() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = NearBlack
                        )
                    ) { Text("Continue") }
                }
                else -> {
                    if (ui.currentIndex < photoUriStrings.size) {
                        Text(
                            "Editing photo ${ui.currentIndex + 1} of ${ui.total}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PaperDim
                        )
                        Spacer(Modifier.height(12.dp))
                        AsyncImage(
                            model = photoUriStrings.getOrNull(ui.currentIndex),
                            contentDescription = "Current photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    CircularProgressIndicator(color = Amber)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        ui.step.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                }
            }

            // WebView (hidden, drives automation)
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).also { vm.attachWebView(it) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
            )
        }
    }
}
