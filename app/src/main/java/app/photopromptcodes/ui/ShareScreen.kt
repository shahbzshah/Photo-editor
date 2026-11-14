package app.photopromptcodes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
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
import app.photopromptcodes.share.ChatGptShare
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
    val chatGptInstalled: Boolean = true
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
            ui = ui.copy(
                code = code,
                photoUri = photoUri,
                chatGptInstalled = ChatGptShare.isInstalled(ctx)
            )
            if (!ui.chatGptInstalled) return@launch
            sendNow()
        }
    }

    /** Hands the photo + prompt to the ChatGPT app, then records the send. */
    private suspend fun sendNow() {
        val ctx = getApplication<Application>()
        val code = ui.code ?: return
        val photo = ui.photoUri ?: return
        try {
            val shared = withContext(Dispatchers.IO) {
                ChatGptShare.sharedPhotoUri(ctx, photo)
            }
            if (!ChatGptShare.sendToChatGpt(ctx, shared, code.prompt)) {
                ui = ui.copy(error = "Couldn't open the ChatGPT app.")
                return
            }
            ui = ui.copy(sent = true, error = null)
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
                    onClick = { vm.sendAgain() },
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

            if (!ui.chatGptInstalled) {
                Text(
                    "ChatGPT app isn't installed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This version hands your photo and prompt to the ChatGPT app — " +
                        "no sign-in inside this app, no waiting here. Install ChatGPT, " +
                        "or open it in your browser and attach the photo yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperDim
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { ChatGptShare.openPlayStore(context) },
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
                    onClick = { ChatGptShare.openBrowser(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open in browser instead")
                }
                return@Column
            }

            Text(
                if (ui.sent) "Sent to ChatGPT" else "Sending to ChatGPT…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap Send in the ChatGPT app to make the edit. The finished photo " +
                    "stays in ChatGPT — save it from there.",
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
                    "PROMPT SENT TO CHATGPT",
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
                onClick = { vm.sendAgain() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = NearBlack
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open ChatGPT", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            var copied by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    ui.code?.let {
                        ChatGptShare.copyPromptToClipboard(context, it.prompt)
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
