package app.photopromptcodes.data

import kotlinx.serialization.Serializable

@Serializable
data class PromptCode(
    val code: String,
    val category: String,
    val description: String,
    val prompt: String
) {
    /** Asset path for the sample "after" image, e.g. file:///android_asset/samples/portalshot.jpg */
    val sampleAsset: String
        get() = "file:///android_asset/samples/${code.trimStart('/')}.jpg"

    companion object {
        /** Asset path for the shared "before" portrait. */
        const val BASE_SAMPLE_ASSET = "file:///android_asset/samples/_base.jpg"
    }
}
