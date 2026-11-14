package app.photopromptcodes.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a photo + prompt to the ChatGPT app instead of driving ChatGPT
 * inside our own WebView. No sign-in, no cookies, no automation — the
 * ChatGPT app owns the whole edit. The edited image stays there; the user
 * saves it from ChatGPT.
 *
 * The prompt is also copied to the clipboard on every share: if ChatGPT
 * takes the photo but not the text, the user pastes it in one tap.
 */
object ChatGptShare {
    const val PACKAGE_NAME = "com.openai.chatgpt"
    const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=$PACKAGE_NAME"
    const val WEB_URL = "https://chatgpt.com"

    fun isInstalled(context: Context): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            }
            return true
        } catch (_: PackageManager.NameNotFoundException) {
            // Fall through to the explicit-intent probe below.
        }
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            setPackage(PACKAGE_NAME)
        }
        return context.packageManager.resolveActivity(probe, 0) != null
    }

    /**
     * Copies the photo into our shared cache dir (FileProvider) unless it
     * is already one of ours, so ChatGPT always gets a URI it can read —
     * whatever provider the photo came from.
     */
    fun sharedPhotoUri(context: Context, source: Uri): Uri {
        if (source.authority == "app.photopromptcodes.fileprovider") return source
        val file = File(
            context.cacheDir,
            "shared/share_${System.currentTimeMillis()}.jpg"
        )
        file.parentFile?.mkdirs()
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read the selected photo.")
        return FileProvider.getUriForFile(
            context,
            "app.photopromptcodes.fileprovider",
            file
        )
    }

    fun copyPromptToClipboard(context: Context, prompt: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ChatGPT prompt", prompt))
        } catch (_: Exception) {
        }
    }

    /**
     * Sends the photo + prompt to the ChatGPT app. Returns false when the
     * app is not there to receive it (installed check raced, or it was
     * removed). Call from the main thread — startActivity is cheap; the
     * photo copy happens in [sharedPhotoUri] before this.
     */
    fun sendToChatGpt(context: Context, photoUri: Uri, prompt: String): Boolean {
        copyPromptToClipboard(context, prompt)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, photoUri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            setPackage(PACKAGE_NAME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Play Store listing; falls back to the web listing if no store app. */
    fun openPlayStore(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$PACKAGE_NAME")
                )
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL))
                )
            } catch (_: Exception) {
            }
        }
    }

    /** chatgpt.com in the user's browser — photo must be attached by hand. */
    fun openBrowser(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL)))
        } catch (_: Exception) {
        }
    }
}
