# Photo Prompt Codes — Android app

A native Android app for the 167 AI photo-editing prompt codes. Everything
runs **on-device**: no servers, no hosting, no SaaS, no accounts, 100% free
and open source.

**How it works:** pick a prompt-code card → choose a photo → tap **Edit
photo**. The app opens ChatGPT in a built-in browser, attaches your photo,
sends the code's full prompt, waits for the generated image, and saves the
result. You sign in to ChatGPT inside the app once; the login persists.

## Getting the APK (no Android Studio needed)

1. Create a GitHub repo and push this project (`main` branch).
2. Open the repo on GitHub → **Actions** → **Build APK** → latest run.
3. Download the `app-debug` artifact, unzip it, and install `app-debug.apk`
   on your phone (allow "Install unknown apps" when asked).

Every push to `main` builds a fresh APK automatically.

## First run: ChatGPT sign-in

1. Open the app, pick any code, choose a photo, tap **Edit photo**.
2. The built-in browser opens ChatGPT. If you aren't signed in, the app
   tells you — sign in right there in the browser view, then tap
   **Continue**.
3. Done. The sign-in cookie is kept by the app, so next time it just works.

## How an edit works

`RunScreen` → `ChatGptDriver` (all automation lives in
`automation/`, see below):

1. **Opening ChatGPT** — loads `https://chatgpt.com` in the WebView.
2. **Checking sign-in** — looks for the composer vs. the "Log in" button.
3. **Attaching photo** — taps the composer's attach button; the app's
   `onShowFileChooser` hook supplies your photo directly (no manual
   picking inside ChatGPT).
4. **Sending prompt** — types the code's full prompt into the message box
   (ProseMirror-aware: `execCommand('insertText')` with fallbacks) and
   taps send.
5. **Generating** — a `MutationObserver` watches for a new, fully-loaded
   image in the latest assistant message, downloads it **in-page** (so
   ChatGPT's auth cookies apply), and hands the bytes back over a
   `@JavascriptInterface` bridge.

Results land in **History** (thumbnail, code, date). Open one for the
before/after view with **Save to gallery** (goes to
Pictures/PhotoPromptCodes) and **Share**.

## When ChatGPT changes its website

All CSS selectors live in **one file**:
`app/src/main/java/app/photopromptcodes/automation/Selectors.kt`
— every element has 2–3 fallback selectors. If an edit breaks after a
ChatGPT redesign:

1. Open https://chatgpt.com in a desktop browser → DevTools → find the
   new selector for the broken element.
2. Add/replace it in `Selectors.kt`. Nothing else needs to change.

## Project structure

```
app/src/main/
├── assets/codes.json            # 167 codes (generated from the web app's codes.ts)
├── java/app/photopromptcodes/
│   ├── MainActivity.kt          # nav graph: gallery/detail/run/history/result
│   ├── automation/
│   │   ├── ChatGptDriver.kt     # WebView driver: login, attach, prompt, harvest
│   │   └── Selectors.kt         # ALL ChatGPT selectors — the fix point
│   ├── data/
│   │   ├── Code.kt / CodeRepository.kt   # prompt-code model + asset loading
│   │   └── HistoryRepository.kt          # file-based edit history
│   ├── ui/
│   │   ├── theme/Theme.kt       # darkroom theme (near-black, paper, amber)
│   │   ├── GalleryScreen.kt     # search + category chips + code cards
│   │   ├── CodeDetailScreen.kt  # full prompt + photo picker
│   │   ├── RunScreen.kt         # WebView + live progress steps + retry
│   │   ├── HistoryScreen.kt     # past edits
│   │   └── ResultDetailScreen.kt# before/after, save, share, delete
│   └── util/Media.kt            # MediaStore save + share sheet
└── res/values/themes.xml        # launch theme (no white flash)
```

## Local development

- Requirements: JDK 17, Android SDK (compileSdk 34).
- `./gradlew :app:assembleDebug` (the Gradle wrapper jar is committed).

## Tech

Kotlin · Gradle Kotlin DSL · Jetpack Compose + Material 3 · minSdk 26 ·
`app.photopromptcodes` · kotlinx.serialization · Coil · Navigation Compose.
No backend, no API keys, no third-party SDKs.

## Honest caveat

The ChatGPT web flow is written defensively (fallback selectors, per-step
timeouts, human-readable errors) but has **not been tested against the live
site yet** — ChatGPT's markup drifts. Watch the first real edit; if a step
fails, the error message says which one, and `Selectors.kt` is where the
fix goes.
