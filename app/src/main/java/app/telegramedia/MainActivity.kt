package app.telegramedia

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.telegramedia.ui.AppRoot
import app.telegramedia.ui.theme.TelegramediaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark-themed, so force light (white) system-bar icons
        // regardless of the system light/dark setting. The default auto style picks
        // icon color from the system theme, making them dark (invisible on our dark
        // background) when the device is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            TelegramediaTheme {
                AppRoot()
            }
        }
    }
}
