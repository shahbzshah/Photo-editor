package app.photopromptcodes.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Saves image bytes to the shared photo gallery (Pictures/PhotoPromptCodes).
 * No permission needed on API 29+; the manifest requests
 * WRITE_EXTERNAL_STORAGE (maxSdkVersion 28) for older devices.
 */
fun saveImageToGallery(context: Context, bytes: ByteArray, displayName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoPromptCodes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return null
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: run {
                resolver.delete(uri, null, null)
                return null
            }
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        return null
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    return uri
}

/** Shares an image file via the system share sheet (FileProvider). */
fun shareImage(context: Context, file: File, mimeType: String = "image/png") {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val copy = File(sharedDir, file.name)
    file.copyTo(copy, overwrite = true)
    val uri = FileProvider.getUriForFile(
        context,
        "app.photopromptcodes.fileprovider",
        copy
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share edited photo"))
}
