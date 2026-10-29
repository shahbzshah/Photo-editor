package app.photopromptcodes.automation

/**
 * THE fix point when ChatGPT changes its markup.
 *
 * Every CSS selector the driver uses lives here, with 2-3 fallbacks per
 * element where possible. The driver tries them in order
 * (document.querySelector(a) || querySelector(b) || ...).
 *
 * If an edit breaks after a ChatGPT redesign:
 *  1. Open https://chatgpt.com in a desktop browser, open DevTools.
 *  2. Find the new selector for the broken element (composer, attach
 *     button, send button, or the generated <img>).
 *  3. Add/replace it in the list below — no other file needs to change.
 */
object Selectors {

    /** Elements that only exist when the user is LOGGED OUT. */
    val loginIndicators: List<String> = listOf(
        "a[href*=\"auth/login\"]",
        "a[href*=\"auth.openai.com/log-in\"]",
        "[data-testid=\"login-button\"]"
    )

    /**
     * The message composer. ChatGPT uses a ProseMirror contenteditable div.
     * (Fallback: any contenteditable inside the composer form.)
     */
    val composer: List<String> = listOf(
        "#prompt-textarea",
        "div#prompt-textarea[contenteditable=\"true\"]",
        "form div[contenteditable=\"true\"]"
    )

    /** The "+" / attach button in the composer toolbar. */
    val attachButton: List<String> = listOf(
        "[data-testid=\"composer-plus-btn\"]",
        "button[aria-label=\"Attach files\"]",
        "button[aria-label*=\"Attach\"]"
    )

    /**
     * If tapping the attach button opens a menu instead of the file picker,
     * the driver clicks the menu item whose visible text contains one of
     * these (case-insensitive). First match wins.
     */
    val attachMenuTextHints: List<String> = listOf(
        "upload from computer",
        "photos & files",
        "photos",
        "camera",
        "files",
        "upload",
        "attach"
    )

    /**
     * The send button. Current markup (verified Oct 2026) is a single
     * button carrying id="composer-submit-button", data-testid="send-button"
     * and aria-label="Send prompt" — and it is DISABLED until the composer
     * holds text, so the driver must poll for an enabled one rather than
     * looking once. Older/alternate forms are kept as fallbacks; the
     * driver also falls back to sending with the Enter key, so a future
     * markup change degrades instead of failing outright.
     */
    val sendButton: List<String> = listOf(
        "#composer-submit-button",
        "button[data-testid=\"send-button\"]",
        "button[data-composer-submit]",
        "button[aria-label=\"Send prompt\"]",
        "button[aria-label=\"Send message\"]",
        "button[aria-label^=\"Send\"]",
        "form button[type=\"submit\"]"
    )

    /**
     * Assistant message images. The freshly generated image lands inside the
     * newest assistant message. The driver snapshots existing ones first, then
     * watches for a NEW <img> that is fully loaded.
     */
    val generatedImage: List<String> = listOf(
        "[data-message-author-role=\"assistant\"] img",
        "main img[src^=\"https://\"]"
    )
}
