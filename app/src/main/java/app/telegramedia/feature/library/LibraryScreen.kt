package app.telegramedia.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.telegramedia.telegram.MediaItem
import app.telegramedia.telegram.MediaKind
import app.telegramedia.ui.components.rememberSmallFilePath
import app.telegramedia.ui.theme.Ink
import app.telegramedia.ui.theme.InkCard
import app.telegramedia.ui.theme.Teal
import app.telegramedia.ui.theme.TextMuted
import app.telegramedia.ui.theme.TextSecondary
import app.telegramedia.ui.theme.Violet
import app.telegramedia.util.formatHms
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onPlay: (MediaItem) -> Unit,
    onEditSources: () -> Unit,
    onLogout: () -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val hasSelection by viewModel.hasSelection.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    var searchOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("Telegramedia", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search", tint = TextSecondary)
                    }
                    IconButton(onClick = onEditSources) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Sources", tint = TextSecondary)
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Log out", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ink,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !hasSelection -> EmptyState(onEditSources)
                else -> Column(Modifier.fillMaxSize()) {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Search videos") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (sources.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = sourceFilter == null,
                                onClick = { viewModel.setSourceFilter(null) },
                                label = { Text("All") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet),
                            )
                            sources.forEach { src ->
                                FilterChip(
                                    selected = sourceFilter == src.id,
                                    onClick = { viewModel.setSourceFilter(src.id) },
                                    label = { Text(src.title, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Violet),
                                )
                            }
                        }
                    }
                    if (loading && items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Violet)
                        }
                    } else if (items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No videos found in the selected sources.", color = TextMuted)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(160.dp),
                            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // messageId is only unique within a chat; the library
                            // aggregates several chats, so key by source+message to
                            // avoid LazyGrid's duplicate-key crash on cross-chat collisions.
                            items(items, key = { "${it.sourceId}:${it.item.messageId}" }) { entry ->
                                LibraryCard(entry, showSource = sourceFilter == null, onClick = { onPlay(entry.item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onEditSources: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            "Pick the channels, groups and chats you want to watch videos from.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.size(20.dp))
        TextButton(onClick = onEditSources) {
            Icon(Icons.Outlined.Tune, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Choose sources")
        }
    }
}

@Composable
private fun LibraryCard(entry: LibraryEntry, showSource: Boolean, onClick: () -> Unit) {
    val item = entry.item
    val thumbPath = rememberSmallFilePath(item.thumbnailFileId)
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(InkCard).clickable(onClick = onClick).padding(bottom = 10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.35f), Teal.copy(alpha = 0.25f)))),
            contentAlignment = Alignment.Center,
        ) {
            thumbPath?.let { path ->
                AsyncImage(
                    model = remember(path) { File(path) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(
                if (item.kind == MediaKind.AUDIO) Icons.Filled.MusicNote else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
            if (item.durationMs > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Ink.copy(alpha = 0.75f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(formatHms(item.durationMs), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            minLines = 2, // reserve two lines so 1- and 2-line cards are the same height
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
        )
        if (showSource) {
            Text(
                entry.sourceTitle,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}
