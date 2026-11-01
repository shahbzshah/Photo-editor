package app.photopromptcodes.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.photopromptcodes.ui.theme.Amber
import app.photopromptcodes.ui.theme.Danger
import app.photopromptcodes.ui.theme.Mono
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.Paper
import app.photopromptcodes.ui.theme.PaperDim
import app.photopromptcodes.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Forced-update gate: the whole app until the update is installed.
 * No dismiss, no back door — the gate is rendered instead of the app
 * itself, so there is nowhere else to go. It stays up after the
 * installer is dismissed (the downloaded APK is re-launched from the
 * Update button) and disappears only when the new version runs.
 *
 * Fail-open lives in the caller: UpdateChecker.checkForUpdate returns
 * null when GitHub can't be reached, so the gate never appears on a
 * failed check — a network blip must never brick the app.
 */
@Composable
fun ForceUpdateGate(update: UpdateChecker.UpdateInfo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var dlRead by remember { mutableStateOf(0L) }
    var dlTotal by remember { mutableStateOf(-1L) }
    var dlError by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var installerLaunched by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NearBlack)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "UPDATE REQUIRED",
                color = Amber,
                fontFamily = Mono,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Update to continue",
                color = Paper,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Version ${update.versionName} is out. " +
                    "This version can't be used until you update.",
                color = PaperDim,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (update.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    update.notes.take(300),
                    color = PaperDim,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            if (needsPermission) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "One-time step: the system setting just opened — allow " +
                        "this app to install updates, come back here, and " +
                        "tap Update again.",
                    color = PaperDim,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            if (installerLaunched) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "The installer is open — finish the install there. " +
                        "If you closed it, tap Update again.",
                    color = PaperDim,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            if (dlError != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Download failed: $dlError",
                    color = Danger,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            if (downloading) {
                if (dlTotal > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (dlRead.toFloat() / dlTotal.toFloat()).coerceIn(0f, 1f)
                        },
                        color = Amber,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        color = Amber,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = {
                    if (!UpdateChecker.canInstallPackages(ctx)) {
                        needsPermission = true
                        UpdateChecker.openInstallPermissionSettings(ctx)
                        return@Button
                    }
                    needsPermission = false
                    // Already downloaded (installer was dismissed)? Just
                    // re-open the installer instead of downloading again.
                    if (UpdateChecker.launchInstall(ctx, update.versionCode)) {
                        installerLaunched = true
                        return@Button
                    }
                    dlError = null
                    dlRead = 0L
                    dlTotal = update.apkSize
                    downloading = true
                    scope.launch {
                        when (val result = UpdateChecker.downloadAndInstall(
                            ctx, update
                        ) { read, total ->
                            dlRead = read
                            if (total > 0) dlTotal = total
                        }) {
                            // Deliberately NOT clearing the gate: the
                            // installer was only launched. If he dismisses
                            // it, the gate is still here waiting.
                            is UpdateChecker.DownloadResult.Installed ->
                                installerLaunched = true
                            is UpdateChecker.DownloadResult.Cancelled -> Unit
                            is UpdateChecker.DownloadResult.Failed ->
                                dlError = result.reason
                        }
                        downloading = false
                    }
                },
                enabled = !downloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Amber,
                    contentColor = NearBlack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    if (downloading && dlTotal > 0) {
                        val pct = (dlRead * 100 / dlTotal).coerceIn(0, 100)
                        "Downloading… $pct%"
                    } else if (downloading) "Downloading…"
                    else "Update now",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (downloading) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { UpdateChecker.cancelDownload() }) {
                    Text("Cancel download", color = Amber)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                try {
                    ctx.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://github.com/shahbzshah/Photo-editor/releases/latest"
                            )
                        )
                    )
                } catch (_: Exception) {
                }
            }) {
                Text("Get the APK from GitHub", color = PaperDim)
            }
        }
    }
}
