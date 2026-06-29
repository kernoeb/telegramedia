# Third-party notices

Telegramedia is licensed under the **GNU General Public License v3.0** (see
[`LICENSE`](LICENSE)). The project is GPLv3 because it bundles a build of
**libmpv/FFmpeg** configured with `--enable-gpl --enable-version3` (verified from
the shipped `libavutil` binary, which reports *"GPL version 3 or later"*).

It bundles or depends on the following third-party components.

## Bundled native libraries

### TDLib (Telegram Database Library)
- Files: `tdlib/src/main/java/org/drinkless/tdlib/{Client.java,TdApi.java}`,
  `tdlib/src/main/jniLibs/<abi>/libtdjni.so`
- Copyright © 2014-2026 Aliaksei Levin, Arseny Smirnov
- License: **Boost Software License 1.0** - <https://www.boost.org/LICENSE_1_0.txt>
- Source: <https://github.com/tdlib/td>. See [`tools/build-tdlib.md`](tools/build-tdlib.md)
  for how `libtdjni.so` was produced.

`libtdjni.so` statically links:
- **OpenSSL 1.1.1w** - OpenSSL License + original SSLeay License -
  <https://www.openssl.org/source/license-openssl-ssleay.txt>
- **zlib** - zlib License - <https://zlib.net/zlib_license.html>

### libmpv (media player core)
- Maven artifact: `dev.jdtech.mpv:libmpv:1.0.0`
- Build: <https://github.com/jarnedemeulemeester/libmpv-android>
- **mpv** is licensed LGPL v2.1+; the **FFmpeg** it links here is built with
  `--enable-gpl --enable-version3` → **GPL v2-or-later / v3-compatible**, which is
  why this project as distributed is GPLv3.
- FFmpeg © the FFmpeg developers - <https://www.ffmpeg.org/legal.html>
- Linked external libraries in this build: **dav1d** (BSD-2-Clause),
  **libxml2** (MIT), **mbedTLS** (Apache-2.0).

## Apache-2.0 dependencies

The following are licensed under the **Apache License 2.0**
(<https://www.apache.org/licenses/LICENSE-2.0>):

- AndroidX: `core-ktx`, `activity-compose`, `lifecycle-*`, `navigation-compose`,
  `datastore-preferences`
- Jetpack Compose (BOM): `ui`, `ui-graphics`, `material3`, `material-icons-extended`,
  `ui-tooling*`
- Kotlin standard library and the Android Gradle Plugin
- kotlinx: `kotlinx-coroutines-android`, `kotlinx-serialization-json`
- Koin: `io.insert-koin:koin-android`, `koin-androidx-compose`
- Coil 3: `io.coil-kt.coil3:coil-compose`, `coil-network-okhttp`
- OkHttp (`com.squareup.okhttp3`, pulled transitively by Coil)

## Trademark

"Telegram" is a trademark of Telegram Messenger Inc. Telegramedia is an
**unofficial** third-party client built on Telegram's open TDLib/API and is **not
affiliated with, endorsed by, or sponsored by Telegram**.
