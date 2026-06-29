package app.telegramedia.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Persisted user settings: which chats feed the library, and subtitle sizing. */
class SettingsStore(private val context: Context) {

    // Persists fire-and-forget writes (e.g. from a screen being dismissed) on a
    // scope that outlives any ViewModel, so they aren't cancelled mid-write.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val selectedKey = stringSetPreferencesKey("selected_chat_ids")
    private val subtitleScaleKey = floatPreferencesKey("subtitle_scale")
    private val brightnessKey = floatPreferencesKey("player_brightness")
    private val pendingPhoneKey = stringPreferencesKey("pending_phone")

    // distinctUntilChanged: DataStore emits on EVERY write (incl. position saves),
    // so without it these would re-emit the same value and trigger needless reloads.
    val selectedChatIds: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[selectedKey].orEmpty().mapNotNull(String::toLongOrNull).toSet()
    }.distinctUntilChanged()

    suspend fun setSelectedChatIds(ids: Set<Long>) {
        context.dataStore.edit { it[selectedKey] = ids.map(Long::toString).toSet() }
    }

    /** Fire-and-forget persist that survives the caller's scope being cancelled. */
    fun updateSelectedChatIds(ids: Set<Long>) {
        ioScope.launch { setSelectedChatIds(ids) }
    }

    /** Subtitle scale multiplier; default 0.8 (smaller than mpv's default). */
    val subtitleScale: Flow<Float> =
        context.dataStore.data.map { it[subtitleScaleKey] ?: DEFAULT_SUBTITLE_SCALE }.distinctUntilChanged()

    suspend fun setSubtitleScale(scale: Float) {
        context.dataStore.edit { it[subtitleScaleKey] = scale }
    }

    /** Player brightness 0..1, or -1 = follow system (not yet set). */
    val playerBrightness: Flow<Float> =
        context.dataStore.data.map { it[brightnessKey] ?: -1f }.distinctUntilChanged()

    suspend fun setPlayerBrightness(value: Float) {
        context.dataStore.edit { it[brightnessKey] = value }
    }

    // Single source of truth for the per-video key, so save/load can't drift.
    private fun posKey(key: Long) = longPreferencesKey("pos_$key")

    /** Resume position per video, keyed by a stable message id. 0 = start over. */
    suspend fun savePosition(key: Long, positionMs: Long) {
        context.dataStore.edit { it[posKey(key)] = positionMs }
    }

    suspend fun loadPosition(key: Long): Long =
        context.dataStore.data.first()[posKey(key)] ?: 0L

    /** Fire-and-forget position save that survives the player screen being disposed. */
    fun savePositionAsync(key: Long, positionMs: Long) {
        ioScope.launch { savePosition(key, positionMs) }
    }

    /** The phone number the user last submitted for login, so a cold start into the
     *  code-entry step can still show which number the code was sent to. */
    suspend fun setPendingPhone(phone: String) {
        context.dataStore.edit { it[pendingPhoneKey] = phone }
    }

    suspend fun loadPendingPhone(): String? =
        context.dataStore.data.first()[pendingPhoneKey]?.ifBlank { null }

    suspend fun clearPendingPhone() {
        context.dataStore.edit { it.remove(pendingPhoneKey) }
    }

    companion object {
        const val DEFAULT_SUBTITLE_SCALE = 0.8f
    }
}
