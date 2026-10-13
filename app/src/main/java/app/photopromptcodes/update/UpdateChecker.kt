package app.photopromptcodes.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-hosted auto-update via GitHub Releases.
 *
 * Checks https://api.github.com/repos/shahbzshah/Photo-editor/releases/latest
 * for a release tagged like "v3". The number after "v" is the versionCode.
 * If it's newer than the installed app, downloads the APK asset and
 * triggers the system installer.
 *
 * To publish an update: push code, build release APK, create a GitHub
 * release tagged "vN" (N = new versionCode) with the APK attached.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "shahbzshah/Photo-editor"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** Returns UpdateInfo if a newer version exists, null otherwise. */
    suspend fun checkForUpdate(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentCode = ctx.packageManager
                .getPackageInfo(ctx.packageName, 0)
                .let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") it.versionCode
                }

            val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode != 200) {
                Log.d(TAG, "GitHub API returned ${conn.responseCode}")
                return@withContext null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name", "") // e.g. "v3"
            val latestCode = tag.trimStart('v', 'V').toIntOrNull() ?: return@withContext null
            if (latestCode <= currentCode) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", null)
                    break
                }
            }
            if (apkUrl == null) {
                Log.d(TAG, "No APK asset in latest release")
                return@withContext null
            }
            UpdateInfo(
                versionCode = latestCode,
                versionName = tag,
                apkUrl = apkUrl,
                notes = json.optString("body", "")
            )
        } catch (e: Exception) {
            Log.d(TAG, "Update check failed: $e")
            null
        }
    }

    /**
     * Downloads the APK to cache and launches the system installer.
     * Returns true if the installer was launched.
     */
    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/octet-stream")
                    connectTimeout = 30000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                }
                if (conn.responseCode != 200) return@withContext false

                val outFile = File(ctx.cacheDir, "update-${info.versionCode}.apk")
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val uri: Uri = FileProvider.getUriForFile(
                    ctx,
                    "app.photopromptcodes.fileprovider",
                    outFile
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(install)
                true
            } catch (e: Exception) {
                Log.d(TAG, "Download/install failed: $e")
                false
            }
        }
}
