# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Telegramedia is an Android media player for video/audio shared in Telegram chats. It talks to
Telegram via **TDLib** (vendored JNI) and plays files with **libmpv** (handles arbitrary codecs +
SRT/ASS/PGS subtitles). Files are **streamed progressively** through a loopback HTTP server, so
playback starts before the whole file downloads.

- **Stack:** Kotlin · Jetpack Compose · Koin (DI) · TDLib · libmpv · Coil
- **Platform:** Android, **arm64-v8a only** (`minSdk 26`, `targetSdk/compileSdk 37`)
- **License:** GPL-3.0-or-later (bundled libmpv links a GPL FFmpeg build)

## Build & run

**You MUST build with JDK 17.** The machine default (JDK 25) is rejected by Gradle/AGP. Use
Zulu 17 at `/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home` (set `JAVA_HOME` or
`org.gradle.java.home`). `compileSdk 37` is a preview-channel platform - install it with
`sdkmanager --channel=3 "platforms;android-37.0"` if missing.

```sh
./gradlew installDebug      # debug build to a connected arm64 device (runs in demo mode w/o secrets)
./gradlew assembleRelease   # unsigned release APK (signed only if keystore.properties present)
```

There are no unit/instrumented tests in this repo.

## Demo mode vs. real mode

DI (`di/AppModule.kt`) selects the `TelegramService` implementation by whether Telegram credentials
were compiled in (`BuildConfig.TELEGRAM_API_ID != 0`):

- **No `secrets.properties`** → `FakeTelegramService`: in-memory sample chats + bundled
  `app/src/main/assets/demo.mkv`. Login: any phone → code `12345` (use `00000` to reach the 2FA step;
  password `hunter2`). This lets the whole app build/run with no Telegram account.
- **`secrets.properties` present** (copy from `secrets.properties.template`, fill
  `TELEGRAM_API_ID`/`TELEGRAM_API_HASH` from my.telegram.org) → `TdLibTelegramService`. The api_id/
  api_hash are the developer's app creds, baked into the APK - end users still log in with phone/QR.

To test the player path against the fake while keeping real creds around: temporarily
`mv secrets.properties secrets.properties.bak`.

## Architecture

Two Gradle modules: **`:app`** (UI, player, DI, stream server, Telegram service) and **`:tdlib`**
(vendored TDLib JNI bindings + native lib). The app is built around two swappable interface seams:

### `TelegramService` (`telegram/TelegramService.kt`) - the only seam between UI and Telegram
The whole app talks to this interface; `FakeTelegramService` and `TdLibTelegramService` are
interchangeable. It exposes auth (`authState: StateFlow<AuthState>` + phone/code/password/QR
methods), chats, `loadChatMedia(chatId)`, and `streamFile(streamId, sizeBytes): Flow<FileStreamState>`
(emits a playable URL the moment it's ready, then mpv streams). `TdLibTelegramService` drives the
TDLib auth state machine and maps TDLib messages to the app's `MediaItem`/`TgChat` models.

`:tdlib` module wraps the raw JNI: `TdlibClient.kt` is a coroutine/Flow wrapper over
`org.drinkless.tdlib.Client`; `TdApi.java`/`Client.java`/`libtdjni.so` are **vendored, not from
Maven** (TDLib has no Maven artifact - see `tools/build-tdlib.md` to regenerate).

### `MediaEngine` (`player/MediaEngine.kt`) - the playback seam
`MpvMediaEngine` wraps `dev.jdtech.mpv.MPVLib` (libmpv AAR, Findroid's). `PlayerScreen` hosts a
`SurfaceView` and drives play/pause/seek, audio + subtitle track pickers, and speed.

### Progressive streaming (`stream/HttpStreamServer.kt`)
A localhost-only HTTP/1.1 server with `Range` support; mpv opens `http://127.0.0.1:<port>/<key>`
and reads bytes as they arrive. Source-agnostic via the `StreamSource` interface: `TdlibStreamSource`
(real - drives TDLib `DownloadFile`/`GetFile`, seeks by re-downloading at an offset) and a static
source for the fake/complete-file path. Native FFmpeg sockets bypass Android cleartext policy.

### UI / navigation (`ui/AppRoot.kt`)
Compose with type-safe `@Serializable` nav routes. `AppRoot` gates on `AuthState.Ready`: unauth →
`AuthScreen`, auth → NavHost over `LibraryRoute` → `SourcePickerRoute` / `PlayerRoute`. The home
screen is an **aggregated video Library** across user-selected chats (not per-chat) - `SourcePicker`
multi-selects chats, `SettingsStore` (DataStore) persists the selection + subtitle scale + resume
positions. ViewModels are Koin `viewModel { }`; screens get them via `koinViewModel()`.

## Conventions & gotchas

- **Chat IDs are negative** (channels/supergroups ~ `-100…`). Any `% array.size` must use
  `Math.floorMod` (a plain `%` crashed avatar coloring on first real login).
- **mpv resume seek:** set `start` via `setOptionString("start", ...)` *before* `loadfile` - never
  pass `start=` as a `loadfile` argument (it lands in the integer `index` slot and the file never
  loads → infinite spinner).
- **mpv `release()` must be synchronous** - destroying on a background thread races the next
  `PlayerScreen`'s `MPVLib.create` and leaves the second player dead/black.
- **mpv surface lifecycle:** `attachSurface` sets `vo=gpu`, `detachSurface` sets `vo=null`
  (mpv-android pattern) - otherwise video is black after the screen turns off and back on.
- **HttpStreamServer:** flush response headers *immediately* after writing them; passing the known
  `sizeBytes` through avoids a blocking TDLib `GetFile` on the connection thread. Both were root
  causes of the recurring "infinite loader on open".
- **Storage:** TDLib caches downloaded files forever; bounded via `trimCache()` →
  `OptimizeStorage` (called on auth Ready and on player exit). `CACHE_CAP_BYTES` in
  `TdLibTelegramService`.

## Verifying on emulator

SurfaceView video is **not screenshottable** (`adb screencap` shows the hw-composited surface as
black) and the swiftshader emulator ANRs under load. Trust mpv logcat (`MediaCodec started`,
`Starting playback`, no error lines) and a real arm64 device for actual pixels. Headless emulator:
`emulator -avd tgmedia -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`.

## Versions & secrets

Dependency versions are centralized in `gradle/libs.versions.toml` (AGP 9.2.1, Kotlin 2.4.0,
Compose BOM 2026.06.00, Koin 4.2.2, Coil 3.5.0, libmpv 1.0.0). **AGP 9 has built-in Kotlin** - do
NOT apply `org.jetbrains.kotlin.android` (it errors); only the compose + serialization compiler
plugins are applied. `secrets.properties` and `keystore.properties` are gitignored and read at
configure time in `app/build.gradle.kts`.
