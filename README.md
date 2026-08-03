# WP Dialer

An Android phone (dialer) app styled after the Windows Phone 8 "Metro" design
language: pure-black background, oversized lowercase pivot headers, thin
typography, and the classic WP accent-color palette.

## Features

- **Pivot home screen** — `history` (call log) and `people` (contacts) pages,
  swipeable, with the WP application bar along the bottom (keypad, search,
  settings).
- **Keypad** — WP-style dialpad with DTMF key tones, long-press `0` for `+`,
  and `call` / `save` buttons.
- **People** — contact list with accent-colored letter-group tiles and the WP
  alphabet jump grid; contact profile page with call/text actions per number.
- **Contact sources** — contacts are read from the system contacts provider,
  which aggregates phone storage, Google (Gmail) accounts, and
  Microsoft/Outlook (OneDrive) accounts synced on the device. Edits are
  applied to the raw contact rows of each source account, so a OneDrive
  contact saves back to OneDrive, a Gmail contact back to Google, etc. New
  contacts offer a "save to" account picker (phone / Google / Outlook-OneDrive
  / any other sync account present).
- **Default dialer** — the app registers for the `ROLE_DIALER` role and
  implements an `InCallService`, so it owns the full call experience:
  - Metro **incoming call screen** (`answer` / `ignore`), shown over the lock
    screen via a full-screen-intent notification.
  - Metro **in-call screen** with timer, speaker / mute / hold toggles, DTMF
    keypad, and a red `end call` button.
- **Settings** — the full 20-color Windows Phone accent palette; the choice is
  persisted and applied everywhere.

## Building

Requirements: JDK 17+, Android SDK (compileSdk 35).

```
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install app-debug.apk`, then open the app and accept the "default phone
app" prompt so it can handle calls.

Minimum Android version: 14 (API 34). Target: Android 15 (API 35). The call
audio routing uses the modern `CallEndpoint` API and the incoming-call
notification uses `Notification.CallStyle`, both Android 14+.

## Structure

- `app/src/main/java/com/fancyshark/wpdialer/`
  - `MainActivity.kt` — navigation, permissions, default-dialer role request
  - `ui/` — Metro design system (colors, accents, button, pivot, app bar, tiles)
  - `screens/` — dialpad, history/people pages, contact profile, search, settings
  - `call/` — `InCallService`, call state manager, in-call/incoming UI
  - `data/` — contacts + call log repositories
