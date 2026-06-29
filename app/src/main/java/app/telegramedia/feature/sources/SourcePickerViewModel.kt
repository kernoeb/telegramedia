package app.telegramedia.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.telegramedia.data.SettingsStore
import app.telegramedia.telegram.TelegramService
import app.telegramedia.telegram.TgChat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SourcePickerViewModel(
    private val telegram: TelegramService,
    private val settings: SettingsStore,
) : ViewModel() {

    val chats: StateFlow<List<TgChat>> = telegram.chats

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    init {
        viewModelScope.launch { _selected.value = settings.selectedChatIds.first() }
        viewModelScope.launch {
            _loading.value = true
            // finally so a thrown loadChats() can't leave the picker spinning forever.
            try {
                telegram.loadChats()
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggle(id: Long) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun setQuery(q: String) { _query.value = q }

    /** Persist the selection. Uses an app-scoped write so popping the screen
     *  (which clears this ViewModel) can't cancel the DataStore write mid-flight. */
    fun save() {
        settings.updateSelectedChatIds(_selected.value)
    }
}
