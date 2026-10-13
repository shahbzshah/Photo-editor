# Play Store listing — Photo Prompt Codes (draft, 2026-10-13)

Prepared for Megah. Nothing here is published; edit freely before it goes into Play Console.

## App name (30 chars max on Play)
`Photo Prompt Codes` (18) — or `Photo Prompt Codes: AI Edits` (30, exact fit)

## Short description (80 chars max)
Turn 167 viral AI photo prompts into one-tap ChatGPT edits. (59)

## Full description (4000 chars max)
You know those viral AI photo prompts — /goldenhour, /85mmportrait, /cloneeffect, /mirrorportal? The ones everyone posts but nobody can remember?

Photo Prompt Codes puts all 167 of them in your pocket.

Pick a code, pick a photo, and the app sends it straight to ChatGPT for you — the edit comes back right inside the app. No copying prompts, no switching apps, no typing.

WHAT YOU GET
• 167 curated viral photo-editing prompts, browsable by category
• One-tap run: photo + prompt sent to ChatGPT automatically
• Favorites — star your best results and find them fast
• History — every edit you've made, in one place
• Works with your own ChatGPT account (sign in once, inside the app)

No servers, no subscriptions, no accounts with us. Your photos stay on your device and go only to ChatGPT, which you control.

Made for creators who live on their phone camera.

## What's new (first release notes)
v1.0 — Initial release. 167 prompt codes, one-tap ChatGPT edits, favorites, history, onboarding.

## Category
Photography

## Content rating
Everyone (no user-generated sharing inside the app; output depends on the user's own ChatGPT use)

## Asset checklist (Play Console requirements)
- [ ] App icon 512×512 — HAVE source (golden aperture icon in mipmap-xxxhdpi is 192×192; upscale/regenerate a 512 master before upload)
- [ ] Feature graphic 1024×500 — NEEDED (banner for the store page; can generate from the icon + tagline)
- [ ] Phone screenshots (min 2, up to 8; 1080×1920 or similar) — NEEDED (home gallery, run screen, result screen)
- [ ] Privacy policy URL — GAP: policy exists in-app (Settings → About → Privacy Policy) but Play Console requires a PUBLIC URL. Free fix: publish the same text as a page on GitHub Pages from this repo (no server cost, fits the no-hosting setup). Say the word and I'll generate the page.
- [ ] $25 one-time Google Play developer account — on your side

## Notes
- Package name and signing key are locked together: every future update must be signed with the same release.keystore. The file lives at app/release.keystore (gitignored, on the build machine only) — keep a backup copy off-machine.
- The build.gradle.kts currently holds the keystore passwords in plaintext. Before the repo is pushed to GitHub, move them out (gradle.properties or keytool password change) — the repo is public.
