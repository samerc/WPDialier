# WP Dialer — project notes for Claude

Windows Phone 8 (Metro) styled Android dialer. Package `com.fancyshark.wpdialer`,
app label "Phone". Kotlin + Jetpack Compose, single module `:app`.

## Build & install

- Gradle is NOT on PATH: `C:\gradle\gradle-8.13\bin\gradle.bat`, with `JAVA_HOME=C:\jdk-21`
- Android SDK at `C:\android-sdk` (see `local.properties`, untracked); adb: `C:\android-sdk\platform-tools\adb.exe`
- Build: `$env:JAVA_HOME='C:\jdk-21'; & C:\gradle\gradle-8.13\bin\gradle.bat -p "<repo>" assembleDebug`
- Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Test device: user's OnePlus 10 Pro (NE2213, Android 16) over USB. Screenshots via
  `adb shell screencap` + `adb pull` (PowerShell `>` redirection corrupts binary).
  Downscale screenshots before Reading them (System.Drawing) to save context.
- minSdk 34 (user decision — enables CallEndpoint + CallStyle APIs), target 35.

## Workflow rules (user-established)

- **After each verified feature batch: commit and push to `main`**
  (`https://github.com/samerc/WPDialier.git`). Meaningful commit messages.
- Every destructive action in the app must show a Metro confirm overlay first.
- Anything user-customizable belongs in the Settings screen.
- UI changes are checked against real WP8 reference screenshots (kept in
  `%TEMP%\wp_refs\` during sessions; re-fetch from GSMArena WP8 review if gone).
- User-supplied WP 8.1 reference screenshots live in `C:\Users\Administrator\Pictures\wp`
  (phone app video stills: history w/ call circles + white context menus,
  details page w/ durations, speed dial number list, in-call gray panel,
  settings toggles). All 8 observations from them are implemented.
- User cannot be asked to do manual device steps for end users — prefer deep
  links (e.g. the Google Phone notification settings shortcut in Settings).

## Architecture

- `MainActivity.kt` — single-activity nav via a `backStack` state list (`Screen`
  sealed interface), permission + ROLE_DIALER handling, SIM-aware `placeCall`
  (contact pref → global pref → CHOOSE A SIM overlay), confirm overlays.
- `ui/` — Metro design system: `Metro` object (theme-aware color getters driven
  by `Metro.light`), `metroTilt` press effect, `MetroButton`, `MetroTextBox`
  (bordered style — ALL inputs use the 2dp-foreground-border look), `Pivot`
  (headers pan with pager offset), `MetroAppBar`, `Haptics`, `Fonts` (Selawik,
  default), `ContactTile` (photo or centered initials; group tiles are bold).
- `screens/` — Dialpad (digits in fixed-width slots, DTMF tones, haptics),
  HomePages (history: grouped "(n)" rows, right-edge call circle, long-press
  white context menu, CallDetailsScreen w/ durations; speed dial = starred
  contact tiles + plain numbers from AppPrefs), ContactDetail (merged call/text
  rows, per-contact history, preferred SIM, pin/share/delete), New/EditContact,
  Search, Settings (WP toggle switches, reject-template editor, blocked list,
  Google-dialer notification deep link).
- `call/` — `WpInCallService` (CallStyle incoming/ongoing/missed notifications),
  `CallManager` (multi-call: primary + waiting/held, swap, reject-with-text,
  CallEndpoint audio routing), `InCallActivity`, `CallActionReceiver`.
- `data/` — `Repo` (contacts/call log/blocking/pretty formatting), `ContactEditor`
  (per-account raw-contact editing — writes go back to the owning account;
  read-only accounts like WhatsApp filtered from "save to"), `Sims`/`SimPrefs`,
  `AppPrefs` (reject templates, global SIM, light theme, tilt), `Countries`
  (E.164 via libphonenumber), `Geo` (geocoding), osmdroid map picker.

## Gotchas learned the hard way

- PowerShell text pipelines corrupt non-ASCII source chars — use `[System.IO.File]`
  APIs with UTF-8 encoding for scripted edits; never `Get-Content | Set-Content`.
- JSON-escaped `\uXXXX` in tool commands decodes to control chars — avoid.
- Compose `Icons.Filled.X` are extension properties: need individual imports;
  fully-qualified references don't resolve. Same for Modifier extensions.
- Android 11+ package visibility: querying other packages (e.g. Google Dialer
  detection in Settings) requires a `<queries>` manifest entry.
- Google Phone posts its own call notifications even when not default dialer;
  Settings has a deep-link section to disable them (can't be automated).
- Edit-contact screen still lacks parity with create (no emails/dates/photo
  editing) — the main known gap, on the roadmap.
