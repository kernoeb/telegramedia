package app.telegramedia

import android.app.Application
import app.telegramedia.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class TelegramediaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@TelegramediaApp)
            modules(appModule)
        }
    }
}
