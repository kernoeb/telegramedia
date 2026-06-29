package app.telegramedia.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.telegramedia.data.SettingsStore
import app.telegramedia.telegram.MediaItem
import app.telegramedia.telegram.TelegramService
import app.telegramedia.telegram.TgChat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A media item plus the source chat it came from. */
data class LibraryEntry(val item: MediaItem, val sourceId: Long, val sourceTitle: String)

class LibraryViewModel(
    private val telegram: TelegramService,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _all = MutableStateFlow<List<LibraryEntry>>(emptyList())
    private val _sources = MutableStateFlow<List<TgChat>>(emptyList())
    private val _loading = MutableStateFlow(false)
    private val _hasSelection = MutableStateFlow(true)

    private val _query = MutableStateFlow("")
    private val _sourceFilter = MutableStateFlow<Long?>(null)

    val sources: StateFlow<List<TgChat>> = _sources.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val hasSelection: StateFlow<Boolean> = _hasSelection.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()
    val sourceFilter: StateFlow<Long?> = _sourceFilter.asStateFlow()

    val items: StateFlow<List<LibraryEntry>> =
        combine(_all, _query, _sourceFilter) { all, q, filter ->
            all.filter { entry ->
                (filter == null || entry.sourceId == filter) &&
                    (q.isBlank() || entry.item.title.contains(q, ignoreCase = true))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            settings.selectedChatIds.collect { reload(it) }
        }
    }

    private suspend fun reload(ids: Set<Long>) {
        _hasSelection.value = ids.isNotEmpty()
        if (ids.isEmpty()) {
            _all.value = emptyList()
            _sources.value = emptyList()
            return
        }
        _loading.value = true
        try {
            telegram.loadChats()
            val byId = telegram.chats.value.associateBy { it.id }
            val srcs = ids.mapNotNull { byId[it] }
            _sources.value = srcs
            // Drop a source filter that no longer exists.
            if (_sourceFilter.value !in srcs.map { it.id }) _sourceFilter.value = null
            val entries = coroutineScope {
                srcs.map { chat ->
                    async {
                        runCatching { telegram.loadChatMedia(chat.id) }.getOrDefault(emptyList())
                            .map { LibraryEntry(it, chat.id, chat.title) }
                    }
                }.awaitAll().flatten()
            }
            // Channel/posting order (oldest first). messageId increases with time
            // within a chat, so ascending = the order items were posted.
            _all.value = entries.sortedBy { it.item.messageId }
        } finally {
            // Always clear the spinner — a thrown loadChats() must not pin it forever.
            _loading.value = false
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setSourceFilter(id: Long?) { _sourceFilter.value = id }
}
