package app.telegramedia.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.telegramedia.feature.auth.AuthScreen
import app.telegramedia.feature.auth.AuthViewModel
import app.telegramedia.feature.library.LibraryScreen
import app.telegramedia.feature.sources.SourcePickerScreen
import app.telegramedia.player.PlayerScreen
import app.telegramedia.telegram.AuthState
import app.telegramedia.ui.theme.Ink
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable private object LibraryRoute
@Serializable private object SourcePickerRoute
@Serializable private data class PlayerRoute(
    val streamId: Int,
    val title: String,
    val resumeKey: Long,
    val sizeBytes: Long,
)

@Composable
fun AppRoot() {
    val authViewModel: AuthViewModel = koinViewModel()
    val state by authViewModel.authState.collectAsStateWithLifecycle()
    val authed = state is AuthState.Ready

    Crossfade(
        targetState = authed,
        animationSpec = tween(350),
        modifier = Modifier.fillMaxSize().background(Ink),
        label = "authGate",
    ) { isAuthed ->
        if (isAuthed) AuthedApp(onLogout = { authViewModel.logOut() }) else AuthScreen(authViewModel)
    }
}

@Composable
private fun AuthedApp(onLogout: () -> Unit) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onPlay = { item ->
                    navController.navigate(PlayerRoute(item.streamId, item.title, item.messageId, item.sizeBytes))
                },
                onEditSources = { navController.navigate(SourcePickerRoute) },
                onLogout = onLogout,
            )
        }
        composable<SourcePickerRoute> {
            SourcePickerScreen(onDone = { navController.popBackStack() })
        }
        composable<PlayerRoute> { entry ->
            val route = entry.toRoute<PlayerRoute>()
            PlayerScreen(
                streamId = route.streamId,
                title = route.title,
                resumeKey = route.resumeKey,
                sizeBytes = route.sizeBytes,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
