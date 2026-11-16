package app.photopromptcodes.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

/**
 * True when some app on the device answers the camera intent. Some
 * tablets ship with no camera app at all — the Lenovo TB310FU
 * (2026-11-16) has none, so launching TakePicture unguarded crashes
 * with ActivityNotFoundException. Check this before launching, and
 * fall back to the gallery picker when it is false.
 */
fun hasCameraApp(context: Context): Boolean {
    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    return context.packageManager.resolveActivity(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY
    ) != null
}
