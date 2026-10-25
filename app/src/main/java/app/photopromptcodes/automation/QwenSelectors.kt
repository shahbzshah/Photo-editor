package app.photopromptcodes.automation

/**
 * THE fix point when Qwen Chat changes its markup.
 *
 * Every CSS selector the Qwen driver uses lives here, with fallbacks where
 * possible. The driver tries them in order
 * (document.querySelector(a) || querySelector(b) || ...).
 *
 * Mapped 2026-10-15 from live chat.qwen.ai (no login required for image
 * generation as guest; age gate on first use).
 */
object QwenSelectors {

    /** Age-gate modal "Continue" button (appears on first guest generation). */
    val ageGateContinue: List<String> = listOf(
        ".qwen-chat-v2-modal-footer-btns button.qwen-chat-v2-btn-black",
        ".ant-modal-footer button.qwen-chat-v2-btn-black",
        ".ant-modal button span.qwen-chat-v2-btn-content"
    )

    /** Whether the age-gate modal is currently visible. */
    val ageGateVisible: List<String> = listOf(
        ".ant-modal .age-confirmation-desc",
        ".ant-modal-title"
    )

    /** The "+" mode-select button in the composer. NOTE: it is a DIV with role="button", NOT a <button> element. */
    val modeButton: List<String> = listOf(
        "[aria-label=\"Select Mode\"]",
        "div[role=\"button\"][aria-label=\"Select Mode\"]",
        ".mode-select-open"
    )

    /** "Create Image" menu item in the mode dropdown. */
    val createImageItem: List<String> = listOf(
        ".qwen-chat-v2-dropdown-menu-item .mode-select-dropdown-item-name"
    )

    /** Chip showing image mode is active. */
    val imageModeChip: List<String> = listOf(
        ".mode-select-current-mode"
    )

    /** Hidden file input for photo upload. */
    val fileInput: List<String> = listOf(
        "input#filesUpload",
        "input[aria-label=\"Upload files\"]",
        "input[type=\"file\"]"
    )

    /** The prompt textarea. */
    val composer: List<String> = listOf(
        "textarea.message-input-textarea",
        "textarea[placeholder=\"How can I help you today?\"]",
        "textarea[placeholder=\"Ask Qwen\"]",
        "textarea.message-input-textarea-separation"
    )

    /** Send button (appears once textarea is non-empty). */
    val sendButton: List<String> = listOf(
        "button.send-button",
        "button[aria-label=\"Send\"]"
    )

    /** Assistant message images (generated results). */
    val resultImages: List<String> = listOf(
        ".message-item-assistant img",
        "[class*=\"assistant\"] img[src*=\"alicdn\"]",
        "img[src*=\"alicdn.com/imgextra\"]"
    )

    /** Download button on a generated image (unconfirmed — harvested via URL fallback). */
    val downloadButton: List<String> = listOf(
        "button[aria-label*=\"Download\"]",
        "[aria-label*=\"download\" i]"
    )

    /** Loading/generating indicator. NOTE: deliberately narrow — a broad
     *  [class*="loading"] match used to stay true forever (lazy-load and
     *  skeleton elements carry "loading" classes permanently), which made
     *  the driver wait the full 12 minutes even after the image was ready.
     *  The Stop button exists only while a generation is actually running. */
    val generating: List<String> = listOf(
        "button[aria-label=\"Stop\"]",
        ".message-item-assistant [class*=\"shimmer\"]"
    )
}
