package app.photopromptcodes.ui

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.photopromptcodes.data.CodeRepository
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photopromptcodes.PpcApplication
import app.photopromptcodes.data.HistoryRepository
import app.photopromptcodes.update.UpdateChecker
import androidx.compose.foundation.text.selection.SelectionContainer
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.ui.theme.SurfaceDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSignIn: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chatgptIn by remember { mutableStateOf(isLoggedInFlag(ctx)) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            // --- ChatGPT account ---
            Text(
                "CHATGPT ACCOUNT",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Refresh sign-in state when returning from the login screen
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        chatgptIn = isLoggedInFlag(ctx)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "ChatGPT",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (chatgptIn) "Signed in. Best quality."
                                else "Sign in required. Best quality.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PaperDim
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (chatgptIn) "●" else "○",
                            color = if (chatgptIn) Amber else PaperDim,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (chatgptIn) {
                        OutlinedButton(
                            onClick = { showSignOutConfirm = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign out of ChatGPT")
                        }
                    } else {
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
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.height(24.dp))

            // --- Storage ---
            Text(
                "STORAGE",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Clear edit history",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Removes all past edits from the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear history")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- About ---
            Text(
                "ABOUT",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Photo Prompt Codes",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    val settingsVersion = rememberVersionName(ctx)
                    var settingsCodeCount by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(Unit) {
                        settingsCodeCount = withContext(Dispatchers.IO) {
                            CodeRepository.load(ctx).size
                        }
                    }
                    Text(
                        "Version $settingsVersion • ${settingsCodeCount ?: "…"} prompt codes",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(4.dp))
                    var downloadCount by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(Unit) {
                        downloadCount = withContext(Dispatchers.IO) {
                            try {
                                val url = java.net.URL(
                                    "https://api.github.com/repos/shahbzshah/Photo-editor/releases?per_page=100"
                                )
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.setRequestProperty("Accept", "application/vnd.github+json")
                                conn.connectTimeout = 10000
                                conn.readTimeout = 10000
                                if (conn.responseCode != 200) return@withContext null
                                val json = conn.inputStream.bufferedReader().readText()
                                val arr = org.json.JSONArray(json)
                                var total = 0
                                for (i in 0 until arr.length()) {
                                    val assets = arr.getJSONObject(i).optJSONArray("assets") ?: continue
                                    for (j in 0 until assets.length()) {
                                        total += assets.getJSONObject(j).optInt("download_count", 0)
                                    }
                                }
                                total
                            } catch (_: Exception) { null }
                        }
                    }
                    Text(
                        if (downloadCount != null) "Total downloads: $downloadCount"
                        else "Total downloads: …",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onAbout) {
                            Text("About", color = Amber)
                        }
                        TextButton(onClick = onPrivacy) {
                            Text("Privacy Policy", color = Amber)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- Updates ---
            var updateState by remember { mutableStateOf("idle") } // idle|checking|available|none|downloading|done|error
            var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
            Text(
                "UPDATES",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "App updates",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (updateState) {
                            "checking" -> "Checking for updates…"
                            "available" -> "Version ${updateInfo?.versionName} is available."
                            "none" -> "You're on the latest version."
                            "downloading" -> "Downloading update…"
                            "done" -> "Download complete — tap to install."
                            "error" -> "Download failed. Try the browser instead."
                            else -> "Check for new versions of the app."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    if (updateInfo?.notes?.isNotBlank() == true && updateState == "available") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            updateInfo!!.notes.take(300),
                            style = MaterialTheme.typography.bodySmall,
                            color = PaperDim
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    when (updateState) {
                        "error" -> OutlinedButton(
                            onClick = {
                                val url = "https://github.com/shahbzshah/Photo-editor/releases/latest"
                                try {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url)
                                        )
                                    )
                                } catch (_: Exception) { }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open in browser", color = Amber)
                        }
                        "available" -> {
                            val canInstall = UpdateChecker.canInstallPackages(ctx)
                            Button(
                                onClick = {
                                    if (!canInstall) {
                                        // First tap: take him to the system toggle.
                                        // After he allows it, tapping again downloads & installs.
                                        UpdateChecker.openInstallPermissionSettings(ctx)
                                    } else {
                                        updateState = "downloading"
                                        scope.launch {
                                            val ok = UpdateChecker.downloadAndInstall(
                                                ctx, updateInfo!!
                                            )
                                            updateState = if (ok) "done" else "error"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Amber,
                                    contentColor = NearBlack
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (canInstall) "Download & install"
                                    else "Allow app installs",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!canInstall) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "One-time step: Android asks you to allow this app " +
                                        "to install updates. Turn it on, come back here, " +
                                        "and tap the button again.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PaperDim
                                )
                            }
                        }
                        "checking", "downloading" -> {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = Amber,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        else -> OutlinedButton(
                            onClick = {
                                updateState = "checking"
                                scope.launch {
                                    val info = UpdateChecker.checkForUpdate(ctx)
                                    if (info != null) {
                                        updateInfo = info
                                        updateState = "available"
                                    } else {
                                        updateState = "none"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Check for updates")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- Diagnostics ---
            Text(
                "DIAGNOSTICS",
                style = MaterialTheme.typography.labelSmall,
                color = PaperDim
            )
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val crashCount = remember {
                        PpcApplication.listReports(ctx).size
                    }
                    Text(
                        "Crash reports",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (crashCount == 0) "No crashes recorded."
                        else "$crashCount report(s) saved on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PaperDim
                    )
                    if (crashCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        var cleared by remember { mutableStateOf(false) }
                        var showReport by remember { mutableStateOf<String?>(null) }
                        val latestReport = remember {
                            PpcApplication.listReports(ctx).firstOrNull()?.readText()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showReport = latestReport },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("View report")
                            }
                            OutlinedButton(
                                onClick = {
                                    latestReport?.let { text ->
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Photo Prompt Codes crash report")
                                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                                        }
                                        ctx.startActivity(android.content.Intent.createChooser(intent, "Share crash report"))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Share report")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                PpcApplication.clearReports(ctx)
                                cleared = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (cleared) "Cleared" else "Clear crash reports")
                        }
                        showReport?.let { reportText ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showReport = null },
                                title = { Text("Crash report", fontSize = 16.sp) },
                                text = {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(400.dp)
                                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                reportText,
                                                fontFamily = Mono,
                                                fontSize = 11.sp,
                                                color = Paper
                                            )
                                        }
                                    }
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = { showReport = null }) {
                                        Text("Close", color = Amber)
                                    }
                                },
                                containerColor = SurfaceDark
                            )
                        }
                    }
                }
            }
        }
    }

    // Sign-out confirmation
    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign out of ChatGPT?") },
            text = { Text("You'll need to sign in again before generating edits.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    // Clear cookies + flag
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    clearLoggedInFlag(ctx)
                    chatgptIn = false
                }) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear-history confirmation
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes every past edit from the app. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            HistoryRepository(ctx).clearAll()
                        }
                    }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
