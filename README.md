# klic-mobile-android

Native Android client for **Klic** — Kotlin + Jetpack Compose, talking to `klic-server` for auth,
chat, and call signaling, and to LiveKit for audio/video media.

## Requirements
- Android Studio (Ladybug+), JDK 17, Android SDK 35
- An emulator or device on API 26+

## Run
Open the folder in Android Studio and let Gradle sync, then Run. By default the app targets the
**live server** `https://api.89.34.230.2.sslip.io` (see `data/Network.kt`). To use a local backend,
set `BASE_HTTP = "http://10.0.2.2:3000"` (emulator → host) and start `klic-server` first.

CLI: `./gradlew :app:installDebug`

## Stack
- **Jetpack Compose** + Material 3 (dark-first theme)
- **Retrofit + OkHttp + kotlinx.serialization** — REST
- **socket.io-client** — realtime messaging + call signaling
- **livekit-android** — audio/video rooms
- **DataStore** — token storage (move to EncryptedSharedPreferences before release)

## Structure

```
app/src/main/java/com/klic/app/
├── KlicApplication.kt      # manual DI container (repository, socket, callManager)
├── MainActivity.kt         # Compose nav host (auth gate → home → chat → call)
├── ui/theme/               # Color, Type (TikTok Sans), Theme (dark)
├── ui/components/          # PillButton, KlicTextField, CircleControl
├── data/                   # Models, KlicApi (Retrofit), TokenStore, KlicRepository, Network
├── realtime/SocketService  # mirrors server events
├── calling/CallManager     # LiveKit room + mic/camera toggles
└── feature/                # KlicViewModel + auth · conversations · chat · call screens
res/font/                   # curated TikTok Sans subset
res/mipmap-anydpi-v26/      # adaptive launcher icon
design/icons/{Bold,Line}    # brand SVG source of truth
scripts/generate-icons.sh   # SVG → vector drawables
```

## Design system
Dark-first. Background `#0E0F16`, primary **punch-red `#ED122B`**, TikTok Sans. Buttons fully
rounded and flat — no shadows, strokes, or emoji (`ui/components/Components.kt`).

## Icons
The brand set lives in `design/icons/`. For M0, screens use Material icons (mic, videocam, call…)
so they tint cleanly. Run `scripts/generate-icons.sh` to convert the brand SVGs into vector
drawables (`R.drawable.ic_<variant>_<name>`), then swap the Material icons for them.

## Calling & push (M3)
- **FCM** delivers incoming-call and message pushes (`calling/KlicMessagingService.kt`).
- A high-priority **full-screen-intent** notification + `IncomingCallActivity` rings the call over the
  lock screen (`calling/CallNotifications.kt`, `calling/IncomingCallActivity.kt`).
- **LiveKit video** is rendered by `calling/CallVideo.kt`.

To make push actually fire:
1. Create a **Firebase project**, add an Android app with package `com.klic.app`, and replace the
   placeholder **`app/google-services.json`** with the real one.
2. Put the Firebase **service-account JSON** on the server and set `FCM_SERVICE_ACCOUNT_PATH` in
   `klic-server/.env`.

LiveKit room/track APIs in `calling/CallManager.kt` + `CallVideo.kt` target the current SDK — adjust
if your installed version differs.

## Releases & in-app updates (no Play Store)
Releases are published here on GitHub; the APK is attached to each tagged release. The app
self-updates from these releases — **Settings → App updates → Check** reads the latest GitHub
release, and **Download & install** fetches the APK and hands it to the system installer
(`update/AppUpdater.kt`). The new APK must be signed with the **same key** as the installed one
(the project's debug key) to update in place.

Cut a release with `./release.sh [version]` — it bumps the Android version (and syncs the iOS
version in `../klic-mobile-ios/project.yml`), builds the APK, tags `vX.Y.Z`, and runs
`gh release create` with the APK attached. Android and iOS are kept on the **same version**.

Notes:
- The published APK is a **debug build** (unminified; signed with the debug key) — fine for
  testing, not a Play Store artifact.
- **iOS is not distributed here.** There is no way for an iOS app to self-install/update outside
  the App Store; iOS OTA testing is via **TestFlight**. (An unsigned IPA cannot be installed on a
  device, so we don't publish one.)

## Roadmap
Done: M1 auth/friends · M2 read receipts + FCM push · M3 calling (full-screen incoming + LiveKit video).
Next: Telecom ConnectionService, call history, group calls, typing indicators.
