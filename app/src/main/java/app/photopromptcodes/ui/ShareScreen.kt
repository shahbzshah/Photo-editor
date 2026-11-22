package app.photopromptcodes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShareUi(
    val code: PromptCode? = null,
    val photoUri: Uri? = null,
    val error: String? = null,
    val sent: Boolean = false,
    val targets: List<AiShare.Target> = emptyList(),
    val target: AiShare.Target = AiShare.CHATGPT,
    val noAppInstalled: Boolean = false,
    val missingTargets: List<AiShare.Target> = emptyList(),
    val openOnlyTargets: List<AiShare.Target> = emptyList(),
    val viaOpen: Boolean = false
)

class ShareViewModel(app: Application) : AndroidViewModel(app) {
    var ui by mutableStateOf(ShareUi())
        private set

    private var started = false
    private val history = HistoryRepository(app)

    fun ensureStarted(codeName: String, photoUriString: String, customPrompt: String? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val photoUri = Uri.parse(photoUriString)
            val code = if (codeName == "__custom__" && customPrompt != null) {
                PromptCode(
                    code = "/custom",
                    category = "Custom",
                    description = customPrompt.take(80),
                    prompt = "Edit this photo: $customPrompt Keep the subject's identity and pose recognizable unless the instruction says otherwise. Photorealistic."
                )
            } else {
                CodeRepository.load(ctx).firstOrNull { it.code == codeName }
            }
            if (code == null) {
                ui = ui.copy(error = "Code not found: $codeName")
                return@launch
            }
            app.photopromptcodes.data.RecentCodes.record(ctx, code.code)
            // Only shareable apps are offered as send targets; the saved
            // choice wins when it is still shareable, otherwise the first
            // shareable one. Installed-but-not-shareable apps (Qwen) are
            // listed separately with an Open button instead of a doomed
            // send; apps not on the phone at all get a Get-it button.
            val installed = AiShare.installedTargets(ctx)
            val hasApp = AiShare.TARGETS.any {
                it.packageName != null && AiShare.isInstalled(ctx, it)
            }
            val saved = AiShare.targetById(AiShare.savedTargetId(ctx))
            val chosen = if (installed.contains(saved)) saved else installed.first()
            val missing = AiShare.TARGETS.filter {
                it.packageName != null && !AiShare.isInstalled(ctx, it)
            }
            ui = ui.copy(
                code = code,
                photoUri = photoUri,
                targets = installed,
                target = chosen,
                noAppInstalled = !hasApp,
                missingTargets = missing,
                openOnlyTargets = AiShare.openOnlyTargets(ctx)
            )
            // Deliberately NO auto-send here: the chooser comes first.
            // v2.1.0 fired to ChatGPT the moment this screen opened, so
            // the user never saw the other options at all.
        }
    }

    /** Switching target sends to it right away — one tap, no second step. */
    fun selectTarget(target: AiShare.Target) {
        val ctx = getApplication<Application>()
        AiShare.saveTargetId(ctx, target.id)
        ui = ui.copy(target = target, error = null, sent = false, viaOpen = false)
        viewModelScope.launch { sendNow() }
    }

    /**
     * Opens an app that can't take a share (Qwen). The prompt is already
     * on the clipboard; the user attaches the photo inside the app.
     * Recorded in history like a send — the hand-off happened, just by
     * hand instead of by intent.
     */
    fun openTarget(target: AiShare.Target) {
        val ctx = getApplication<Application>()
        val code = ui.code ?: return
        AiShare.saveTargetId(ctx, target.id)
        AiShare.copyPromptToClipboard(ctx, code.prompt)
        if (!AiShare.openApp(ctx, target)) {
            ui = ui.copy(
                target = target,
                error = "Couldn't open ${target.label}."
            )
            return
        }
        ui = ui.copy(target = target, sent = true, viaOpen = true, error = null)
        viewModelScope.launch {
            try {
                val photo = ui.photoUri ?: return@launch
                val id = history.stageOriginal(code.code, photo)
                history.saveSent(id, code.code)
            } catch (_: Exception) {
            }
        }
    }

    /** Hands the photo + prompt to the chosen app, then records the send. */
    private suspend fun sendNow() {
        val ctx = getApplication<Application>()
        val code = ui.code ?: return
        val photo = ui.photoUri ?: return
        val target = ui.target
        try {
            val shared = withContext(Dispatchers.IO) {
                AiShare.sharedPhotoUri(ctx, photo)
            }
            if (!AiShare.sendToTarget(ctx, target, shared, code.prompt)) {
                ui = ui.copy(
                    error = "Couldn't open ${target.label} — it may not take " +
                        "photos from other apps. Try opening it and attaching " +
                        "the photo yourself."
                )
                return
            }
            ui = ui.copy(sent = true, viaOpen = false, error = null)
            // Record the hand-off in history (best effort — the share
            // already happened, so a history failure must not surface).
            try {
                val id = history.stageOriginal(code.code, photo)
                history.saveSent(id, code.code)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            ui = ui.copy(error = "Couldn't share the photo. Please retry.")
        }
    }

    fun sendAgain() {
        viewModelScope.launch { sendNow() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    code: String,
    photoUriString: String,
    customPrompt: String? = null,
    onBack: () -> Unit,
    onDone: () -> Unit,
    vm: ShareViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(code, photoUriString) { vm.ensureStarted(code, photoUriString, customPrompt) }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ui.photoUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = "Selected photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(16.dp))
            }

            if (ui.error != null) {
                Text(
                    "Something went wrong",
                    style = MaterialTheme.typography.titleMedium,
                    color = Danger
                )
                Spacer(Modifier.height(8.dp))
                Text(ui.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        // Retry the same kind of hand-off that failed:
                        // an open-only target is re-opened, not sent to.
                        if (ui.openOnlyTargets.contains(ui.target)) vm.openTarget(ui.target)
                        else vm.sendAgain()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = NearBlack
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Retry", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
                return@Column
            }

            if (ui.noAppInstalled) {
                Text(
                    "No AI app installed yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Install ChatGPT, Gemini or Qwen to edit photos, or open " +
                        "ChatGPT in your browser and attach the photo yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperDim
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { AiShare.openPlayStore(context, AiShare.CHATGPT) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Amber,
                        contentColor = NearBlack
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Get ChatGPT on Play Store", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { AiShare.openPlayStore(context, AiShare.GEMINI) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Get Gemini on Play Store")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { AiShare.openPlayStore(context, AiShare.QWEN) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Get Qwen on Play Store")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { AiShare.openBrowser(context, AiShare.CHATGPT) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open ChatGPT in browser instead")
                }
                return@Column
            }

            // Send-to chooser: only installed apps, plus Other.
            Text(
                "SEND TO",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ui.targets.forEach { target ->
                    val selected = target.id == ui.target.id
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) vm.selectTarget(target) },
                        label = { Text(target.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber,
                            selectedLabelColor = NearBlack,
                            containerColor = SurfaceDark,
                            labelColor = PaperDim
                        )
                    )
                }
            }
            // Apps not on the phone yet are shown, not hidden — each
            // with a one-tap way to install it.
            ui.missingTargets.forEach { target ->
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "${target.label} isn't installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { AiShare.openPlayStore(context, target) }
                    ) {
                        Text(
                            "Get it",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber
                        )
                    }
                }
            }
            // Installed but can't take a share (Qwen): open it instead.
            // The prompt is on the clipboard; the photo is attached
            // inside the app.
            ui.openOnlyTargets.forEach { target ->
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "${target.label} can't take photos directly",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { vm.openTarget(target) }
                    ) {
                        Text(
                            "Open ${target.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            val target = ui.target
            Text(
                when {
                    ui.sent && ui.viaOpen -> "Opened ${target.label}"
                    ui.sent -> "Sent to ${target.label}"
                    else -> "Ready to send"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    ui.viaOpen -> {
                        "Attach the photo inside ${target.label} yourself, " +
                            "then paste the prompt — it's already on your " +
                            "clipboard. The finished photo stays in " +
                            "${target.label} — save it from there."
                    }
                    target.packageName == null -> {
                        "Pick an app, then tap Send there to make the edit. The " +
                            "finished photo stays in that app — save it from there."
                    }
                    else -> {
                        "Tap Send in the ${target.label} app to make the edit. The " +
                            "finished photo stays in ${target.label} — save it from there."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your prompt is already on the clipboard — paste it in if the box " +
                    "comes up empty.",
                style = MaterialTheme.typography.bodySmall,
                color = PaperDim
            )
            Spacer(Modifier.height(16.dp))

            ui.code?.let { c ->
                Text(
                    when {
                        ui.viaOpen -> "PROMPT COPIED FOR ${target.label.uppercase()}"
                        target.packageName == null -> "PROMPT TO SEND"
                        else -> "PROMPT SENT TO ${target.label.uppercase()}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PaperDim,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = c.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Button(
                onClick = { if (ui.viaOpen) vm.openTarget(target) else vm.sendAgain() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = NearBlack
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        ui.viaOpen -> "Open ${target.label}"
                        target.packageName == null -> "Choose app"
                        else -> "Send to ${target.label}"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            var copied by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    ui.code?.let {
                        AiShare.copyPromptToClipboard(context, it.prompt)
                        copied = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (copied) "Prompt copied" else "Copy prompt")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
