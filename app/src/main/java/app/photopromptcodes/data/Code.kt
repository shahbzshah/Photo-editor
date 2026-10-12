package app.photopromptcodes.data

import kotlinx.serialization.Serializable

@Serializable
data class PromptCode(
    val code: String,
    val category: String,
    val description: String,
    val prompt: String
)
