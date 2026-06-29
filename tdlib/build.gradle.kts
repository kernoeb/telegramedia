plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.telegramedia.tdlib"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Only arm64-v8a is vendored (see tools/build-tdlib.md). Covers real devices
        // and the Apple-Silicon emulator; add x86_64 here only if you vendor its .so.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
