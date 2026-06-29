# Building the vendored TDLib native library

TDLib has **no Maven artifact** - the JNI interface (`TdApi.java` + `Client.java` +
`libtdjni.so`) is built from C++ source. The build outputs are **vendored** into the
`:tdlib` module, so a normal clone builds the app without running any of this. You only
need this if you want to update TDLib or add another ABI.

The native lib is built from <https://github.com/tdlib/td> → `example/android/`.

## Recipe

Clone TDLib (shallow), then from `example/android/`:

1. **OpenSSL** (static, `no-shared`):
   ```sh
   ./build-openssl.sh "$ANDROID_SDK_ROOT" 27.1.12297006
   ```
2. **TDLib** (`TDLIB_INTERFACE=Java`, `ANDROID_STL=c++_static` → a single
   `libtdjni.so` per ABI, no separate `libc++`/`libssl` `.so`):
   ```sh
   ./build-tdlib.sh "$ANDROID_SDK_ROOT" 27.1.12297006
   ```

Requirements: **JDK 17** on `PATH` (for `jar`/`javadoc`) and **NDK 27.1.12297006**
installed via the SDK manager. A Telegram `api_id`/`api_hash` (from
<https://my.telegram.org>) is needed to actually run the resulting app, not to build.

### Local adjustments to the upstream scripts

- **Restrict ABIs** to the ones you ship. This project vendors **`arm64-v8a` only**
  (covers real devices and the Apple-Silicon emulator). Edit the `for ABI in …` loops
  in both `build-openssl.sh` and `build-tdlib.sh` accordingly. Add `x86_64` if you need
  an Intel emulator build.
- **Drop the `php` requirement**: remove `php` from `check-environment.sh`'s tool list
  and guard the `php AddIntDef.php` line in `build-tdlib.sh` with `if which php`.
  `AddIntDef.php` only adds cosmetic `@IntDef` lint annotations; `TdApi.java` compiles
  fine without it.

## Vendoring the outputs

Outputs land in `example/android/tdlib/`. Copy them into the `:tdlib` module:

| Build output | Destination |
| --- | --- |
| `libs/<abi>/libtdjni.so` | `tdlib/src/main/jniLibs/<abi>/libtdjni.so` |
| `java/org/drinkless/tdlib/TdApi.java` | `tdlib/src/main/java/org/drinkless/tdlib/TdApi.java` |
| `java/org/drinkless/tdlib/Client.java` | `tdlib/src/main/java/org/drinkless/tdlib/Client.java` |

If you change the set of ABIs, keep `tdlib/build.gradle.kts` (`abiFilters`) and
`app/build.gradle.kts` (`ndk { abiFilters }`) in sync with what you vendored.

> The vendored `libtdjni.so` statically links OpenSSL 1.1.1w and zlib. OpenSSL 1.1.1 is
> end-of-life - consider rebuilding against OpenSSL 3.x when you next regenerate it.
