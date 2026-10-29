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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-hosted auto-update via GitHub Releases.
 *
 * Checks https://api.github.com/repos/shahbzshah/Photo-editor/releases/latest
 * for a release tagged like "v3". The number after "v" is the versionCode.
 * If it's newer than the installed app, downloads the APK asset and
 * triggers the system installer.
 *
 * The download is bounded: an overall deadline, a stall detector (a
 * connection that trickles a few bytes forever is aborted), an exact
 * byte-size check against the release asset, and an APK magic check —
 * so a stalled or truncated download fails fast with a reason instead
 * of spinning the UI forever. The downloaded APK stays in cacheDir, so
 * a dismissed installer can be re-launched from the saved file.
 *
 * To publish an update: push code, build release APK, create a GitHub
 * release tagged "vN" (N = new versionCode) with the APK attached.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "shahbzshah/Photo-editor"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** Hard stop for one download attempt, however slow the connection. */
    private const val TOTAL_DEADLINE_MS = 10 * 60 * 1000L

    /**
     * Stall detection: if fewer than [STALL_MIN_BYTES] arrive inside any
     * [STALL_WINDOW_MS] window, the connection is trickling, not
     * downloading — abort instead of hanging. A genuinely slow but
     * moving connection (even ~10 KB/s) keeps passing.
     */
    private const val STALL_WINDOW_MS = 60 * 1000L
    private const val STALL_MIN_BYTES = 8 * 1024L

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val MIN_APK_BYTES = 1024 * 1024L

    /** Set by [cancelDownload]; checked by the in-flight download. */
    private val cancelRequested = AtomicBoolean(false)

    /** The connection currently transferring, so cancel can unblock it. */
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkApiUrl: String?,
        val apkSize: Long,
        val notes: String
    )

    sealed interface DownloadResult {
        /** The system installer was launched for the verified APK. */
        data object Installed : DownloadResult

        /** The user cancelled; the partial file was deleted. */
        data object Cancelled : DownloadResult

        /** The download could not complete; [reason] is safe to show. */
        data class Failed(val reason: String) : DownloadResult
    }

    /**
     * Whether Android will let this app hand an APK to the system installer.
     * On API 26+ the user must grant "Install unknown apps" for this app once,
     * even with REQUEST_INSTALL_PACKAGES declared in the manifest.
     */
    fun canInstallPackages(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26)
            ctx.packageManager.canRequestPackageInstalls()
        else true

    /** Opens the system screen where the user grants "Install unknown apps" for this app. */
    fun openInstallPermissionSettings(ctx: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** Ask an in-flight download to stop. Safe to call when nothing is downloading. */
    fun cancelDownload() {
        cancelRequested.set(true)
        try {
            activeConnection.getAndSet(null)?.disconnect()
        } catch (_: Exception) {
        }
    }

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
                setRequestProperty("User-Agent", "PhotoPromptCodes")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
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
            var apkApiUrl: String? = null
            var apkSize: Long = -1
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", null)
                    apkApiUrl = asset.optString("url", null)
                    apkSize = asset.optLong("size", -1)
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
                apkApiUrl = apkApiUrl,
                apkSize = apkSize,
                notes = json.optString("body", "")
            )
        } catch (e: Exception) {
            Log.d(TAG, "Update check failed: $e")
            null
        }
    }

    /**
     * Downloads the APK, verifies it, saves it locally and launches the
     * system installer. Tries the GitHub API asset endpoint first
     * (Accept: application/octet-stream), falls back to the
     * browser_download_url. Reports progress as (bytesRead, totalBytes)
     * where totalBytes is -1 when the size is unknown.
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): DownloadResult = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        val urls = listOfNotNull(info.apkApiUrl, info.apkUrl).distinct()
        var lastReason = "Download failed"
        for (startUrl in urls) {
            if (cancelRequested.get()) return@withContext DownloadResult.Cancelled
            when (val result = downloadOne(ctx, startUrl, info, onProgress)) {
                is DownloadResult.Installed -> return@withContext result
                is DownloadResult.Cancelled -> return@withContext result
                is DownloadResult.Failed -> {
                    lastReason = result.reason
                    Log.d(TAG, "Download failed for $startUrl: ${result.reason}")
                }
            }
        }
        DownloadResult.Failed(lastReason)
    }

    /**
     * Launches the system installer for an already-downloaded update file.
     * Returns false when there is no valid saved APK (nothing downloaded
     * yet, or the file failed verification).
     */
    fun launchInstall(ctx: Context, versionCode: Int): Boolean {
        return try {
            val file = File(ctx.cacheDir, "update-$versionCode.apk")
            if (!file.exists() || file.length() < MIN_APK_BYTES || !looksLikeApk(file)) {
                return false
            }
            val uri: Uri = FileProvider.getUriForFile(
                ctx,
                "app.photopromptcodes.fileprovider",
                file
            )
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(install)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Launch install failed: $e")
            false
        }
    }

    private fun downloadOne(
        ctx: Context,
        startUrl: String,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit
    ): DownloadResult {
        val finalFile = File(ctx.cacheDir, "update-${info.versionCode}.apk")
        val tmpFile = File(ctx.cacheDir, "update-${info.versionCode}.apk.part")
        try {
            var url = startUrl
            var conn: HttpURLConnection? = null
            // Manually follow redirects (up to MAX_REDIRECTS hops).
            for (hop in 0 until MAX_REDIRECTS) {
                if (cancelRequested.get()) return DownloadResult.Cancelled
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "PhotoPromptCodes")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                }
                activeConnection.set(c)
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    activeConnection.set(null)
                    if (location == null) {
                        return DownloadResult.Failed("Server redirect had no target")
                    }
                    url = if (location.startsWith("http")) location
                    else URL(URL(url), location).toString()
                } else {
                    conn = c
                    break
                }
            }
            val finalConn = conn
                ?: return DownloadResult.Failed("Too many redirects")
            try {
                if (finalConn.responseCode != 200) {
                    return DownloadResult.Failed(
                        "Server returned HTTP ${finalConn.responseCode}"
                    )
                }
                val contentLength = finalConn.contentLengthLong
                val totalBytes = when {
                    contentLength > 0 -> contentLength
                    info.apkSize > 0 -> info.apkSize
                    else -> -1L
                }

                val deadline = System.currentTimeMillis() + TOTAL_DEADLINE_MS
                var windowStart = System.currentTimeMillis()
                var windowBytes = 0L
                var readTotal = 0L
                val buffer = ByteArray(64 * 1024)
                finalConn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        while (true) {
                            if (cancelRequested.get()) {
                                return DownloadResult.Cancelled
                            }
                            val now = System.currentTimeMillis()
                            if (now > deadline) {
                                return DownloadResult.Failed(
                                    "Download timed out after 10 minutes"
                                )
                            }
                            if (now - windowStart > STALL_WINDOW_MS) {
                                if (windowBytes < STALL_MIN_BYTES) {
                                    return DownloadResult.Failed(
                                        "Connection stalled — try again on a stronger signal"
                                    )
                                }
                                windowStart = now
                                windowBytes = 0L
                            }
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            readTotal += n
                            windowBytes += n
                            onProgress(readTotal, totalBytes)
                        }
                    }
                }

                // Verify before trusting the file.
                if (info.apkSize > 0 && readTotal != info.apkSize) {
                    return DownloadResult.Failed(
                        "Incomplete download ($readTotal of ${info.apkSize} bytes)"
                    )
                }
                if (readTotal < MIN_APK_BYTES) {
                    return DownloadResult.Failed(
                        "Downloaded file too small (${readTotal} bytes)"
                    )
                }
                if (!looksLikeApk(tmpFile)) {
                    return DownloadResult.Failed(
                        "Downloaded file is not an app package"
                    )
                }
                if (!tmpFile.renameTo(finalFile)) {
                    // Same-directory rename should not fail; copy as fallback.
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                onProgress(readTotal, totalBytes)
                return if (launchInstall(ctx, info.versionCode)) {
                    DownloadResult.Installed
                } else {
                    DownloadResult.Failed("Could not open the installer")
                }
            } finally {
                finalConn.disconnect()
                activeConnection.set(null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: IOException) {
            return if (cancelRequested.get()) {
                tmpFile.delete()
                DownloadResult.Cancelled
            } else {
                Log.d(TAG, "Download failed: $e")
                tmpFile.delete()
                DownloadResult.Failed(
                    "Network error: ${(e.message ?: "connection failed").take(60)}"
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Download/install failed: $e")
            tmpFile.delete()
            return DownloadResult.Failed(
                "Download failed: ${(e.message ?: "unknown error").take(60)}"
            )
        }
    }

    /** True when the file starts with the ZIP/APK magic bytes "PK\x03\x04". */
    private fun looksLikeApk(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                var offset = 0
                while (offset < magic.size) {
                    val n = input.read(magic, offset, magic.size - offset)
                    if (n < 0) return false
                    offset += n
                }
                magic[0] == 0x50.toByte() &&
                    magic[1] == 0x4B.toByte() &&
                    magic[2] == 0x03.toByte() &&
                    magic[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }
}
