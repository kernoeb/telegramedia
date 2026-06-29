package app.telegramedia.di

import app.telegramedia.BuildConfig
import app.telegramedia.data.SettingsStore
import app.telegramedia.feature.auth.AuthViewModel
import app.telegramedia.feature.library.LibraryViewModel
import app.telegramedia.feature.sources.SourcePickerViewModel
import app.telegramedia.telegram.FakeTelegramService
import app.telegramedia.telegram.TdLibTelegramService
import app.telegramedia.telegram.TelegramService
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { SettingsStore(androidContext()) }

    single<TelegramService> {
        if (BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank()) {
            TdLibTelegramService(
                context = androidContext(),
                apiId = BuildConfig.TELEGRAM_API_ID,
                apiHash = BuildConfig.TELEGRAM_API_HASH,
                settings = get(),
            )
        } else {
            FakeTelegramService(androidContext())
        }
    }

    viewModel { AuthViewModel(get()) }
    viewModel { LibraryViewModel(get(), get()) }
    viewModel { SourcePickerViewModel(get(), get()) }
}
