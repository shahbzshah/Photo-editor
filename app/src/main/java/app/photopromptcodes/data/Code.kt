package app.photopromptcodes.data

import kotlinx.serialization.Serializable

@Serializable
data class PromptCode(
    val code: String,
    val category: String,
    val description: String,
    val prompt: String,
    /** Release that added this code, e.g. "2.4.0" — null for the original 281. The gallery treats the newest stamped batch as "new". */
    val addedIn: String? = null
) {
    /** Asset path for the sample "after" image, e.g. file:///android_asset/samples/portalshot.jpg */
    val sampleAsset: String
        get() = "file:///android_asset/samples/${code.trimStart('/')}.jpg"

    companion object {
        /** Asset path for the shared "before" portrait. */
        const val BASE_SAMPLE_ASSET = "file:///android_asset/samples/_base.jpg"
    }
}
