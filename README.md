# Telegramedia

An Android media player for video and audio shared in your Telegram chats. It talks to
Telegram through [TDLib](https://github.com/tdlib/td) and plays files with
[libmpv](https://mpv.io/) - so it handles practically any codec, container, and subtitle
format (SRT, ASS, PGS), and **streams files progressively** (playback starts before the
whole file is downloaded) via a small loopback HTTP server.

> **Unofficial client.** Telegramedia is a third-party app built on Telegram's open
> API/TDLib. It is **not affiliated with, endorsed by, or sponsored by Telegram.**

- **Stack:** Kotlin · Jetpack Compose · Koin (DI) · TDLib · libmpv · Coil
- **Platform:** Android, **arm64-v8a only** (`minSdk 26`, `targetSdk 37`)

## Demo mode vs. real mode

The app runs **out of the box in demo mode** - no Telegram account needed. When no
`secrets.properties` is present, dependency injection selects an in-memory
`FakeTelegramService` with sample chats and a bundled test clip:

- login code: **`12345`** (use **`00000`** to exercise the 2FA-password step)
- 2FA password: **`hunter2`**

Provide real Telegram API credentials (below) to switch to the live TDLib-backed
service.

## Prerequisites

- **JDK 17**
- **Android SDK** with `compileSdk 37` (build-tools + platform 37)
- A physical **arm64** device, or an **Apple-Silicon** emulator image (no x86_64 native
  lib is shipped - see [`tools/build-tdlib.md`](tools/build-tdlib.md) to add one)
- Gradle is provided via the committed wrapper (`./gradlew`, Gradle 9.4.1)
- **NDK 27.1.12297006** - only if you intend to rebuild TDLib; the native library is
  already vendored

## Telegram API credentials (real mode)

1. Sign in at <https://my.telegram.org> → **API development tools**.
2. Create an app and copy your **`api_id`** and **`api_hash`**.
3. Create `secrets.properties` in the repo root:
   ```sh
   cp secrets.properties.template secrets.properties
   ```
   Fill it in:
   ```properties
   TELEGRAM_API_ID=123456
   TELEGRAM_API_HASH=your_api_hash_here
   ```

`secrets.properties` is **gitignored** and must never be committed. Leaving it absent
keeps the app in demo mode. Note that your `api_hash` is compiled into any APK you
distribute (this is inherent to Telegram clients) - each distributor should use their
own credentials.

## Build & run

```sh
# Debug build to a connected device (works in demo mode with no secrets)
./gradlew installDebug

# Unsigned/release APK
./gradlew assembleRelease
```

### Signing a release (optional)

Release signing activates only when a `keystore.properties` is present (otherwise
`assembleRelease` produces an unsigned APK). To sign:

```sh
keytool -genkeypair -v -keystore release.keystore -alias telegramedia \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` in the repo root (also **gitignored**):

```properties
storeFile=release.keystore
storePassword=...
keyAlias=telegramedia
keyPassword=...
```

## Project layout

```
app/    Compose UI, player, DI, the Telegram service, the loopback stream server
tdlib/  Vendored TDLib JNI bindings (TdApi.java/Client.java) + libtdjni.so
tools/  build-tdlib.md - how the native TDLib lib is produced
```

The native TDLib library is **vendored** (`tdlib/src/main/jniLibs/arm64-v8a/libtdjni.so`
+ generated `TdApi.java`), so no separate native build is needed for a normal checkout.
See [`tools/build-tdlib.md`](tools/build-tdlib.md) to regenerate it.

## Known limitations

- The TDLib session database (`filesDir/tdlib`) is stored **without a
  `databaseEncryptionKey`**. The app sandbox protects it on a non-rooted device, but on
  a rooted/compromised device it (and the saved login phone number in DataStore) is
  readable. Documented, not yet hardened.
- The local stream server is **loopback-only** (`127.0.0.1`, ephemeral port). Stream
  routes use a random per-session token.
- Release builds are not minified/obfuscated (`isMinifyEnabled = false`).

## License

**GPL-3.0-or-later** - see [`LICENSE`](LICENSE). The project is GPL because the bundled
libmpv links a GPL build of FFmpeg. Third-party components and their licenses are listed
in [`NOTICE.md`](NOTICE.md).
