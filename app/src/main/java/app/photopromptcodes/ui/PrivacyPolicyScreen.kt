package app.photopromptcodes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.photopromptcodes.ui.theme.NearBlack
import app.photopromptcodes.ui.theme.PaperDim

private val SECTIONS = listOf(
    "What this app does" to
        "Photo Prompt Codes helps you apply AI photo-editing prompts to your own photos. " +
        "You pick a prompt code, choose a photo, and the app sends both to ChatGPT (via chatgpt.com) " +
        "which generates the edited image. The result is saved on your device.",
    "Information we collect" to
        "The app does not run its own servers and does not send your data to us. " +
        "• Photos you choose are sent to ChatGPT (OpenAI) so it can generate the edit. " +
        "• Your ChatGPT login session (cookies) stays on your device, inside the app's WebView. " +
        "• Edit history (your photos and results) is stored only on your device.",
    "Information we do NOT collect" to
        "We do not create accounts, do not track you, do not use analytics, and do not sell or share " +
        "any personal information. There is nothing to opt out of because nothing leaves your device " +
        "except what you send to ChatGPT to generate an edit.",
    "Third parties" to
        "ChatGPT (OpenAI) processes the photo and prompt you send when you generate an edit. " +
        "OpenAI's own privacy policy applies to that processing: https://openai.com/policies/privacy-policy",
    "Your control" to
        "You can delete your edit history at any time from Settings → Clear history. " +
        "You can sign out of ChatGPT from Settings → ChatGPT account, which clears the login session " +
        "from the device.",
    "Children" to
        "This app is not directed at children under 13.",
    "Contact" to
        "Questions about this policy: contact the developer through the app's store listing."
)

@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy") },
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
                .padding(20.dp)
        ) {
            Text(
                "Photo Prompt Codes — Privacy Policy",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Last updated: October 2026",
                style = MaterialTheme.typography.bodySmall,
                color = PaperDim
            )
            Spacer(Modifier.height(20.dp))
            SECTIONS.forEach { (title, body) ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PaperDim
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
