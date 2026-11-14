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
 * Hands a photo + prompt to an AI app instead of driving one inside our
 * own WebView. No sign-in, no cookies, no automation — the chosen app
 * owns the whole edit. The edited image stays there; the user saves it
 * from that app.
 *
 * Targets (only installed ones are offered):
 * - ChatGPT (com.openai.chatgpt) — edits photos.
 * - Qwen (com.tongyi.intl) — Alibaba's consumer app, photo editing is
 *   one of its headline jobs.
 * - Qwen Studio (ai.qwenlm.chat.android) — the chat.qwen.ai app, also
 *   edits images.
 * - Other apps — the system share sheet, for anything else installed
 *   (Gemini, Grok, …).
 *
 * The prompt is also copied to the clipboard on every share: if the app
 * takes the photo but not the text, the user pastes it in one tap.
 */
object AiShare {
    data class Target(
        val id: String,
        val label: String,
        val packageName: String?,
        val playStoreUrl: String?,
        val webUrl: String?
    )

    val CHATGPT = Target(
        id = "chatgpt",
        label = "ChatGPT",
        packageName = "com.openai.chatgpt",
        playStoreUrl = "https://play.google.com/store/apps/details?id=com.openai.chatgpt",
        webUrl = "https://chatgpt.com"
    )
    val QWEN = Target(
        id = "qwen",
        label = "Qwen",
        packageName = "com.tongyi.intl",
        playStoreUrl = "https://play.google.com/store/apps/details?id=com.tongyi.intl",
        webUrl = "https://qwen.ai"
    )
    val QWEN_STUDIO = Target(
        id = "qwen-studio",
        label = "Qwen Studio",
        packageName = "ai.qwenlm.chat.android",
        playStoreUrl = "https://play.google.com/store/apps/details?id=ai.qwenlm.chat.android",
        webUrl = "https://chat.qwen.ai"
    )
    val OTHER = Target(
        id = "other",
        label = "Other apps",
        packageName = null,
        playStoreUrl = null,
        webUrl = null
    )

    val TARGETS = listOf(CHATGPT, QWEN, QWEN_STUDIO, OTHER)

    private const val PREFS = "ppc_prefs"
    private const val KEY_TARGET = "share_target"

    fun targetById(id: String?): Target =
        TARGETS.firstOrNull { it.id == id } ?: CHATGPT

    fun savedTargetId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TARGET, CHATGPT.id) ?: CHATGPT.id

    fun saveTargetId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TARGET, id).apply()
    }

    fun isInstalled(context: Context, target: Target): Boolean {
        val pkg = target.packageName ?: return true // "Other" is always there
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            return true
        } catch (_: PackageManager.NameNotFoundException) {
            // Fall through to the explicit-intent probe below.
        }
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            setPackage(pkg)
        }
        return context.packageManager.resolveActivity(probe, 0) != null
    }

    /** Installed app targets plus "Other" — the chooser shows exactly these. */
    fun installedTargets(context: Context): List<Target> =
        TARGETS.filter { it.packageName == null || isInstalled(context, it) }

    /**
     * Copies the photo into our shared cache dir (FileProvider) unless it
     * is already one of ours, so the receiving app always gets a URI it
     * can read — whatever provider the photo came from.
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
            cm.setPrimaryClip(ClipData.newPlainText("AI prompt", prompt))
        } catch (_: Exception) {
        }
    }

    /**
     * Sends the photo + prompt to the target app. "Other" opens the system
     * share sheet instead. Returns false when the app is not there to
     * receive it. The share is fired from the ViewModel's Application
     * context, so FLAG_ACTIVITY_NEW_TASK is required — without it Android
     * throws and the share dies before the app ever opens.
     */
    fun sendToTarget(context: Context, target: Target, photoUri: Uri, prompt: String): Boolean {
        copyPromptToClipboard(context, prompt)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, photoUri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            target.packageName?.let { setPackage(it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launch = if (target.packageName == null) {
            Intent.createChooser(intent, "Send to…")
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        } else {
            intent
        }
        return try {
            context.startActivity(launch)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Play Store listing; falls back to the web listing if no store app. */
    fun openPlayStore(context: Context, target: Target) {
        val pkg = target.packageName ?: return
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$pkg")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(target.playStoreUrl))
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            } catch (_: Exception) {
            }
        }
    }

    /** The target's web chat in the user's browser — photo attached by hand. */
    fun openBrowser(context: Context, target: Target) {
        val url = target.webUrl ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Exception) {
        }
    }
}
