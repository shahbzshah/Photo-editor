package app.photopromptcodes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.photopromptcodes.data.CodeRepository
import app.photopromptcodes.data.HistoryRepository
import app.photopromptcodes.data.PromptCode
import app.photopromptcodes.share.AiShare
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Danger
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Batch in the share-to-AI world: there is no background driver any
 * more, so each photo is handed to the chosen AI app one at a time.
 * Send one, come back, send the next. Skipped photos just move on.
 * The target is the one remembered from the last share (change it on
 * the single-photo screen).
 */
data class BatchUi(
    val code: PromptCode? = null,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val sentCount: Int = 0,
    val skippedCount: Int = 0,
    val error: String? = null,
    val isDone: Boolean = false,
    val target: AiShare.Target = AiShare.CHATGPT,
    val targetInstalled: Boolean = true
)

class BatchRunViewModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf(BatchUi())
        private set

    private var started = false
    private val history = HistoryRepository(app)
    private var photoQueue: List<Uri> = emptyList()

    fun ensureStarted(codeName: String, photoUriStrings: List<String>) {
        if (started) return
        started = true
        photoQueue = photoUriStrings.map { Uri.parse(it) }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val code = CodeRepository.load(ctx).firstOrNull { it.code == codeName }
            if (code == null) {
                ui = ui.copy(error = "Code not found: $codeName")
                return@launch
            }
            app.photopromptcodes.data.RecentCodes.record(ctx, code.code)
            val installed = AiShare.installedTargets(ctx)
            val saved = AiShare.targetById(AiShare.savedTargetId(ctx))
            val chosen = if (installed.contains(saved)) saved else installed.first()
            ui = ui.copy(
                code = code,
                total = photoQueue.size,
                target = chosen,
                targetInstalled = chosen.packageName == null ||
                    AiShare.isInstalled(ctx, chosen)
            )
        }
    }

    /** Sends the current photo to the chosen AI app and moves to the next. */
    fun sendCurrent() {
        val ctx = getApplication<Application>()
        val photo = photoQueue.getOrNull(ui.currentIndex) ?: return
        val code = ui.code ?: return
        val target = ui.target
        viewModelScope.launch {
            try {
                val shared = withContext(Dispatchers.IO) {
                    AiShare.sharedPhotoUri(ctx, photo)
                }
                if (!AiShare.sendToTarget(ctx, target, shared, code.prompt)) {
                    ui = ui.copy(error = "Couldn't open ${target.label}.")
                    return@launch
                }
                try {
                    val id = history.stageOriginal(code.code, photo)
                    history.saveSent(id, code.code)
                } catch (_: Exception) {
                }
                advance(sent = true)
            } catch (_: Exception) {
                ui = ui.copy(error = "Couldn't read photo ${ui.currentIndex + 1}.")
            }
        }
    }

    fun skipCurrent() = advance(sent = false)

    private fun advance(sent: Boolean) {
        val next = ui.currentIndex + 1
        ui = ui.copy(
            currentIndex = next,
            sentCount = ui.sentCount + if (sent) 1 else 0,
            skippedCount = ui.skippedCount + if (sent) 0 else 1,
            error = null,
            isDone = next >= photoQueue.size
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchRunScreen(
    code: String,
    photoUriStrings: List<String>,
    onBack: () -> Unit,
    onDone: () -> Unit,
    vm: BatchRunViewModel = viewModel()
) {
    LaunchedEffect(code) { vm.ensureStarted(code, photoUriStrings) }
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
                        "${ui.sentCount} of ${ui.total} photos sent to ${ui.target.label}" +
                            if (ui.skippedCount > 0) " (${ui.skippedCount} skipped)" else "",
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
                            onClick = { vm.sendCurrent() },
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
                !ui.targetInstalled -> {
                    Text(
                        "${ui.target.label} app isn't installed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Batch sends each photo to ${ui.target.label}. Install it from " +
                            "the Play Store first, or pick a different app on the " +
                            "single-photo screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(16.dp))
                    val ctx = LocalContext.current
                    Button(
                        onClick = { AiShare.openPlayStore(ctx, ui.target) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = NearBlack
                        )
                    ) {
                        Text("Get ${ui.target.label} on Play Store", fontWeight = FontWeight.SemiBold)
                    }
                }
                ui.currentIndex < photoUriStrings.size -> {
                    Text(
                        "Photo ${ui.currentIndex + 1} of ${ui.total} — sending to ${ui.target.label}",
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
                    Text(
                        "Tap Send, finish the edit in ${ui.target.label}, then come " +
                            "back here for the next photo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.sendCurrent() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = NearBlack
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send to ${ui.target.label}", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.skipCurrent() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip this photo")
                    }
                }
            }
        }
    }
}
