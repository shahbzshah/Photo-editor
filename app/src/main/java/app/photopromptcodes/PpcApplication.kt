package app.photopromptcodes

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught crashes, saves a report to internal storage
 * (files/crashes/), then lets the system handle the crash as usual.
 * Reports are viewable in Settings → Diagnostics.
 */
class PpcApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashReport(this, throwable)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val MAX_REPORTS = 20

        fun crashDir(ctx: Context): File =
            File(ctx.filesDir, "crashes").apply { mkdirs() }

        fun listReports(ctx: Context): List<File> =
            crashDir(ctx).listFiles()
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()

        fun clearReports(ctx: Context) {
            crashDir(ctx).listFiles()?.forEach { it.delete() }
        }

        private fun saveCrashReport(ctx: Context, t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val report = buildString {
                appendLine("Photo Prompt Codes crash report")
                appendLine("Time: $stamp")
                appendLine("App version: 1.0 (1)")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine()
                append(sw.toString())
            }
            val dir = crashDir(ctx)
            // Keep only the newest MAX_REPORTS
            dir.listFiles()?.sortedBy { it.lastModified() }
                ?.dropLast(MAX_REPORTS - 1)
                ?.forEach { it.delete() }
            File(dir, "crash_$stamp.txt").writeText(report)
        }
    }
}
