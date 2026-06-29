package app.telegramedia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.telegramedia.telegram.TelegramService
import app.telegramedia.ui.theme.Coral
import app.telegramedia.ui.theme.Ink
import app.telegramedia.ui.theme.Teal
import app.telegramedia.ui.theme.Violet
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import org.koin.compose.koinInject
import java.io.File

private val avatarPalettes = listOf(
    listOf(Violet, Teal),
    listOf(Teal, Violet),
    listOf(Violet, Coral),
    listOf(Coral, Teal),
)

/** Deterministic gradient for an id; safe for negative Telegram chat ids. */
fun avatarColors(seed: Long) = avatarPalettes[Math.floorMod(seed, avatarPalettes.size.toLong()).toInt()]

private fun initialsOf(title: String) = title.split(' ')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }

/** Circular chat photo, downloaded on demand, with a gradient-initials fallback. */
@Composable
fun ChatAvatar(
    title: String,
    photoFileId: Int?,
    seed: Long,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val path = rememberSmallFilePath(photoFileId)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(avatarColors(seed))),
        contentAlignment = Alignment.Center,
    ) {
        val p = path
        if (p != null) {
            AsyncImage(
                model = remember(p) { File(p) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(initialsOf(title), color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Resolve a TDLib small-file (thumbnail/chat photo) to a local path, downloaded on
 * demand and re-fetched when [fileId] changes. Shared by [ChatAvatar] and library cards.
 */
@Composable
fun rememberSmallFilePath(fileId: Int?): String? {
    val telegram: TelegramService = koinInject()
    var path by remember(fileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(fileId) {
        path = if (fileId != null && fileId != 0) {
            runCatching { telegram.smallFilePath(fileId) }.getOrNull()
        } else null
    }
    return path
}
