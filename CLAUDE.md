# WP Dialer — project notes for Claude

Windows Phone 8 (Metro) styled Android dialer. Package `com.fancyshark.wpdialer`,
app name **"Dialer 8"** everywhere the system shows it — launcher, settings,
permission/role dialogs (user decision 2026-08-06; avoids MS trademarks and
disambiguates the role picker). The in-app pivot header stays "PHONE"
(WP-authentic UI, not the app name). Contact email on the About screen:
fancyshark505@gmail.com. Kotlin + Jetpack Compose, single module `:app`.
Git identity: samerc / 9696877+samerc@users.noreply.github.com (NEVER
ai@bahriah.com — unrelated to the user's GitHub; history was rewritten to
purge it).

## Build & install

- Gradle is NOT on PATH: `C:\gradle\gradle-8.13\bin\gradle.bat`, with `JAVA_HOME=C:\jdk-21`
- Android SDK at `C:\android-sdk` (see `local.properties`, untracked); adb: `C:\android-sdk\platform-tools\adb.exe`
- Build: `$env:JAVA_HOME='C:\jdk-21'; & C:\gradle\gradle-8.13\bin\gradle.bat -p "<repo>" assembleDebug`
- Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Release: `assembleRelease` — R8 + resource shrinking, signed via untracked
  `keystore.properties` + `release.keystore` in the repo root (gitignored,
  NEVER commit; losing the keystore = losing the app's signing identity —
  keep an off-machine backup). ~2.7 MB vs 65 MB debug. `assembleReleaseTest`
  builds an R8 smoke-test variant (`.r8test` appId suffix, debug-signed)
  installable NEXT TO the daily app, so minification testing never requires
  uninstalling (= wiping) the real install.
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
- `screens/` — Dialpad (fixed-width digit slots, DTMF, WP8.1 call/save tiles,
  T9 smart dial: scrollable match list above keys, `DialpadBus` for hardware
  keys), HomePages (grouped "(n)" history rows, call circles, white context
  menus, CallDetailsScreen w/ durations; speed dial), ContactDetail (deduped
  phones, emails, event dates incl. note-dump parsing, website/company/
  nickname, per-app action icons w/ chooser overlay — actions swept from
  unaggregated sibling contacts too, ringtone picker, preferred SIM),
  New/EditContact (full parity: typed phones/emails/dates, photo, address,
  note; per-account diff saves), Search, Settings (toggles, reject templates,
  blocked list, Google-dialer guidance incl. disable flow + re-enable warning,
  about), AboutScreen ("Dialer 8", version, contact email).
- `call/` — `WpInCallService` (custom WP RemoteViews incoming banner — Selawik
  via layout fontFamily, round green/red buttons, day/night colors; CallStyle
  ongoing/missed w/ name lookup + accent; proximity wake lock; full-screen
  intent decides ringing UI — service only direct-launches for outgoing;
  banner suppressed while InCallActivity foreground), `CallManager` (multi-call
  swap/merge, reject-with-text, CallEndpoint routing, `userDismissedUi`),
  `InCallActivity` (gray panel w/ SIM label, add call/merge tiles, hardware
  CALL/ENDCALL/DTMF keys), `CallActionReceiver`. MainActivity auto-returns to
  the call UI on reopen + "tap to return to call" banner; T9/digit hardware
  keys; emergency numbers bypass SIM chooser.
- D-pad/flip support: `MetroIndication` (LocalIndication) draws accent focus
  outlines app-wide; custom controls have explicit focus states.
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
- Google Phone posts its own call notifications even when not default dialer
  (ColorOS binds it as the type-2 system in-call service on every call).
  ColorOS locks its notification toggles AND blocks adb pm revoke/appops —
  the only fix is disabling the app (Settings section guides users; the
  user's device has it disabled via `pm disable-user`). A reminder section
  warns to re-enable before uninstalling.
- ColorOS also blocks `adb shell pm grant` (SecurityException) — to grant
  permissions to a test install, drive the app's own permission dialogs
  with input taps instead.
- Notification RemoteViews resolve `?android:attr` theme colors against the
  APP theme, not the notification surface — use explicit values/values-night
  colors. Custom fonts work via android:fontFamily in the layout XML.
- The ColorOS notification template draws a ~32dp app-icon badge that can't
  be moved/resized; 44dp buttons kept for tappability (32dp was too small).
- Telegram's raw contacts carry no phone row, so Android often fails to
  aggregate them — profile loading sweeps sibling contacts' app-action rows
  by number match (label preferred over DATA1, which can be an app user ID).
- Adaptive-icon foreground art is offset for the safe zone — notifications
  need the separate centered `ic_notification` drawable.

## Phase 1 closed (2026-08-04); phase 2 in progress

Phase 2 shipped so far (all on-device verified, pushed through 7b9d9c0):
- Privacy policy (PRIVACY.md, linked from About; store-listing URL)
- Full localization: all ~240 strings externalized (per-area
  strings_*.xml), complete fr + ar translations, localeConfig + in-app
  language picker (Settings, overlay style), RTL support (offset{} is
  already direction-aware — do NOT manually mirror; keypads forced LTR;
  phone numbers wrapped in LRI/PDI isolates inside Repo.pretty —
  display-only, never compare pretty() output to raw numbers)
- Settings language + text-replies are overlay pickers (crowding rule:
  big lists go in overlays)
- Whole-app bug-hunt (4 parallel subsystem reviews, 22 fixes): see
  commit 7b9d9c0 — conference-children filtering, waiting-call
  notification races, read-only raw-contact protection in the editor,
  explicit missed-call PendingIntent, SaveableStateHolder for
  per-screen state, back-pop refresh, push dedupe, block confirm,
  day-bounded history grouping, MetroIndication reset, allowBackup off

Shipped 2026-08-06 (launch-prep batch):
- One-handed use (44d80bc): Settings toggles for bottom-anchored
  history/search (T9-style reverseLayout) + WP10M slide-down reach
  (flick down on the app bar; 28dp threshold because ColorOS steals
  long swipes near the bottom edge for system one-handed mode).
- Release build: R8 + resource shrinking + profileinstaller, 2.74 MB
  vs 65 MB debug; `releaseTest` variant for side-by-side R8 testing.
- Sibling app-action sweep now cached (Repo.sweepRows, 60s TTL).
- T9 digit forms precomputed once per contact (PreparedDialEntry) —
  was recomputing every name per keystroke (150ms janks at ~2k
  contacts, now 61ms worst frame).
- First-run setup wizard (SetupWizardScreen): role first (its grant
  carries the phone permissions), then leftover permissions, then
  full-screen-intent health check; every step skippable; skipping the
  role shows a calm home banner — NEVER an auto role dialog on launch
  (nag-loops are a Play-review red flag). Existing installs are
  grandfathered (role held => setup_done). Settings has "run
  first-time setup again".
- Stress-tested with +684 fake contacts / +300 call rows (tagged
  account_type stress.test, purged after): release cold start
  303-363ms, history fling 0.08% janky frames, profile w/ app actions
  <900ms. Debug-build numbers are 3-5x worse — never profile on debug.
- Play-submission prep: targetSdk/compileSdk 36 (all back handling is
  dispatcher-based so predictive back is safe), `bundleRelease` AAB
  (3.5 MB; language splits disabled — in-app picker needs all locales
  installed), crash journal (WpApplication + data/CrashLog, local
  file only per privacy policy) + About "report a problem" mailto —
  subject/body must ride IN the mailto URI, Gmail drops SENDTO
  extras. Store assets + en/fr/ar listing text in `store/`.

Known deferred: SIM chooser lost on recreation mid-dial; osmdroid
pause forwarding; type tables / kindLabel localize display-only via
screens/TypeLabels.kt (keys stay English as data — never localize
the keys).

Still untested on hardware: add call/merge/conference live calls,
in-call SIM label, D-pad on the tester's Cat S22 Flip. Remaining
phase-2 candidates: live-tile widget, settings backup/restore
(explicit export — auto-backup now disabled), release signing +
versioning + Play permission declarations for launch.
