package app.photopromptcodes.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import app.photopromptcodes.notifications.ResultNotifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.automation.ChatGptDriver
import app.photopromptcodes.automation.EngineDriver
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
import app.photopromptcodes.ui.theme.SurfaceRaised
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class RunUi(
    val step: RunStep = RunStep.OPENING_ENGINE,
    val loginRequired: Boolean = false,
    val error: String? = null,
    val errorDebug: String? = null,
    val code: PromptCode? = null,
    val photoUri: Uri? = null,
    val notifyWhenReady: Boolean = false
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
        driver = ChatGptDriver().also {
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

    /** The user asked to be notified when this run's image lands. */
    fun requestNotify() {
        ui = ui.copy(notifyWhenReady = true)
    }

    /** Runs one rescue scan for a finished image that the watcher missed. */
    fun checkNow() {
        driver?.requestRescueScan()
    }

    private fun onEvent(event: DriverEvent) {
        when (event) {
            is DriverEvent.Step -> ui = ui.copy(step = event.step, error = null, errorDebug = null)
            DriverEvent.LoginRequired -> ui = ui.copy(loginRequired = true)
            is DriverEvent.Finished -> viewModelScope.launch {
                val id = stageId ?: return@launch
                val record = history.saveResult(id, ui.code?.code.orEmpty(), event.imageBytes)
                if (ui.notifyWhenReady) {
                    ResultNotifier.postReady(getApplication(), ui.code?.code.orEmpty())
                }
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
    onSignIn: () -> Unit,
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
            // While the run is past sign-in, the Developing Room covers the
            // WebView completely: ChatGPT keeps working underneath (it must
            // stay full-size to render), but the user never sees it. Before
            // that (opening / sign-in) the browser stays visible and the
            // old bottom card stays, since the user may need to tap in it.
            val showWaitingRoom = ui.error == null && !ui.loginRequired &&
                (ui.step == RunStep.ATTACHING_PHOTO ||
                    ui.step == RunStep.SENDING_PROMPT ||
                    ui.step == RunStep.GENERATING)
            var progressCollapsed by remember { mutableStateOf(false) }
            var notifyRequested by remember { mutableStateOf(false) }
            var notifyDenied by remember { mutableStateOf(false) }
            val appCtx = LocalContext.current
            val notifyLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    notifyRequested = true
                    vm.requestNotify()
                } else {
                    notifyDenied = true
                }
            }
            if (showWaitingRoom) {
                DevelopingRoom(
                    ui = ui,
                    notifyRequested = notifyRequested,
                    notifyDenied = notifyDenied,
                    onNotifyTap = {
                        if (!notifyRequested) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(
                                    appCtx, Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                notifyRequested = true
                                vm.requestNotify()
                            } else {
                                notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    },
                    onCheckNow = vm::checkNow
                )
            } else {
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
                    // Collapsible header (only when running, not on error/login)
                    if (ui.error == null && !ui.loginRequired) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { progressCollapsed = !progressCollapsed },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ui.step.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (progressCollapsed) "Show" else "Hide",
                                style = MaterialTheme.typography.labelMedium,
                                color = Amber
                            )
                        }
                        if (!progressCollapsed) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (!progressCollapsed) {
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
                            "ChatGPT isn't signed in. Sign in to continue.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onSignIn,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = NearBlack
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign in to ChatGPT", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = vm::continueAfterLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("I've signed in — Continue")
                        }
                    } else {
                        StepList(current = ui.step)
                    }
                    } // end if (!progressCollapsed)
                }
            }
            } // end else (old bottom card)
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

/**
 * The waiting experience while a run is past sign-in: the WebView keeps
 * working underneath (full-size, so ChatGPT renders and the harvester
 * sees real layout), but this opaque room is all the user sees — their
 * photo "developing", honest stage progress with elapsed seconds,
 * rotating notes, and sample looks to browse. No ChatGPT UI is shown.
 */
@Composable
private fun DevelopingRoom(
    ui: RunUi,
    notifyRequested: Boolean,
    notifyDenied: Boolean,
    onNotifyTap: () -> Unit,
    onCheckNow: () -> Unit
) {
    val ctx = LocalContext.current
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }
    var rotate by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            rotate++
        }
    }
    var allCodes by remember { mutableStateOf(emptyList<PromptCode>()) }
    LaunchedEffect(Unit) {
        allCodes = try {
            CodeRepository.load(ctx)
        } catch (_: Exception) {
            emptyList()
        }
    }
    var zoomed by remember { mutableStateOf<PromptCode?>(null) }
    var lastCheckAt by remember { mutableStateOf(-1000) }
    val showCheckNow = ui.step == RunStep.GENERATING && elapsed >= 90 &&
        elapsed - lastCheckAt >= 20

    val code = ui.code
    val ideas = remember(allCodes, code?.code) {
        val rest = allCodes.filter { it.code != code?.code }
        if (rest.isEmpty()) emptyList()
        else {
            val step = maxOf(1, rest.size / 6)
            (0 until 6).map { rest[(it * step) % rest.size] }.distinct()
        }
    }
    val idea = if (ideas.isEmpty()) null else ideas[rotate % ideas.size]
    val lines = remember(code) {
        buildList {
            add("Your original photo stays untouched — the edit is a brand-new image.")
            add("Every prompt asks ChatGPT to keep faces natural: no smoothing, no reshaping.")
            add("Tip: photos with the subject in clear light come out cleanest.")
            code?.let { add("Sent to ChatGPT: “${it.prompt.take(90)}…”") }
        }
    }
    val line = if (lines.isEmpty()) "" else lines[rotate % lines.size]
    val progress = when (ui.step) {
        RunStep.OPENING_ENGINE -> 0.08f
        RunStep.CHECKING_LOGIN -> 0.18f
        RunStep.ATTACHING_PHOTO -> 0.32f
        RunStep.SENDING_PROMPT -> 0.45f
        RunStep.GENERATING -> 0.55f + (minOf(elapsed, 90) / 90f) * 0.40f
        RunStep.DONE -> 1f
    }

    // Slow zoom + breathing light sweep: the photo "developing".
    val infinite = rememberInfiniteTransition(label = "develop")
    val zoom by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "zoom"
    )
    val sheen by infinite.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sheen"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    }
            ) {
                ui.photoUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = sheen),
                                    Color.Transparent
                                ),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(600f, 600f)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f)
                                )
                            )
                        )
                )
            }
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = code?.code ?: "",
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    color = Amber,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = code?.description ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ui.step.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${elapsed}s",
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Amber,
                    trackColor = SurfaceRaised
                )
                Crossfade(targetState = line, label = "rotating-line") { current ->
                    Text(
                        text = current,
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                }
                Crossfade(targetState = idea, label = "idea") { current ->
                    if (current != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "MORE LOOKS TO TRY",
                                style = MaterialTheme.typography.labelSmall,
                                color = PaperDim
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceRaised)
                                    .clickable { zoomed = current }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = current.sampleAsset,
                                    contentDescription = "Sample: ${current.code}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = current.code,
                                        fontFamily = Mono,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Amber,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = current.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PaperDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = onNotifyTap,
                    enabled = !notifyRequested,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (notifyRequested) "You'll be notified when it's ready"
                        else "Notify me when ready"
                    )
                }
                if (showCheckNow) {
                    Button(
                        onClick = {
                            lastCheckAt = elapsed
                            onCheckNow()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = NearBlack
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check for the image now", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (notifyDenied) {
                    Text(
                        text = "Notifications are off — stay on this screen and the result will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger
                    )
                }
            }
        }
    }

    zoomed?.let { z ->
        Dialog(onDismissRequest = { zoomed = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceRaised)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = z.sampleAsset,
                        contentDescription = "Sample: ${z.code}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = z.code,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        color = Amber,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = z.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { zoomed = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = NearBlack
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
