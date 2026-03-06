package tech.torlando.ara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tech.torlando.ara.ui.theme.PresetTheme

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ara_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_NICKNAME = stringPreferencesKey("nickname")
        private val KEY_HUB_NAME = stringPreferencesKey("hub_name")
        private val KEY_ANNOUNCE_INTERVAL = intPreferencesKey("announce_interval")
        private val KEY_HUB_GREETING = stringPreferencesKey("hub_greeting")
        private val KEY_HUB_DEFAULT_ROOM = stringPreferencesKey("hub_default_room")
        private val KEY_HUB_DEFAULT_TOPIC = stringPreferencesKey("hub_default_topic")
        private val KEY_HUB_DEFAULT_MODES = stringPreferencesKey("hub_default_modes")
    }

    val theme: Flow<PresetTheme> = context.dataStore.data.map { prefs ->
        try {
            PresetTheme.valueOf(prefs[KEY_THEME] ?: PresetTheme.ARA.name)
        } catch (_: IllegalArgumentException) {
            PresetTheme.ARA
        }
    }

    val darkMode: Flow<DarkModeOption> = context.dataStore.data.map { prefs ->
        try {
            DarkModeOption.valueOf(prefs[KEY_DARK_MODE] ?: DarkModeOption.SYSTEM.name)
        } catch (_: IllegalArgumentException) {
            DarkModeOption.SYSTEM
        }
    }

    val nickname: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_NICKNAME] ?: ""
    }

    val hubName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HUB_NAME] ?: "Ara Hub"
    }

    val announceInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ANNOUNCE_INTERVAL] ?: 0
    }

    val hubGreeting: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HUB_GREETING] ?: ""
    }

    val hubDefaultRoom: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HUB_DEFAULT_ROOM] ?: ""
    }

    val hubDefaultTopic: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HUB_DEFAULT_TOPIC] ?: ""
    }

    val hubDefaultModes: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HUB_DEFAULT_MODES] ?: "+nt"
    }

    suspend fun setTheme(theme: PresetTheme) {
        context.dataStore.edit { it[KEY_THEME] = theme.name }
    }

    suspend fun setDarkMode(option: DarkModeOption) {
        context.dataStore.edit { it[KEY_DARK_MODE] = option.name }
    }

    suspend fun setNickname(nickname: String) {
        context.dataStore.edit { it[KEY_NICKNAME] = nickname }
    }

    suspend fun setHubName(name: String) {
        context.dataStore.edit { it[KEY_HUB_NAME] = name }
    }

    suspend fun setAnnounceInterval(seconds: Int) {
        context.dataStore.edit { it[KEY_ANNOUNCE_INTERVAL] = seconds }
    }

    suspend fun setHubGreeting(greeting: String) {
        context.dataStore.edit { it[KEY_HUB_GREETING] = greeting }
    }

    suspend fun setHubDefaultRoom(room: String) {
        context.dataStore.edit { it[KEY_HUB_DEFAULT_ROOM] = room }
    }

    suspend fun setHubDefaultTopic(topic: String) {
        context.dataStore.edit { it[KEY_HUB_DEFAULT_TOPIC] = topic }
    }

    suspend fun setHubDefaultModes(modes: String) {
        context.dataStore.edit { it[KEY_HUB_DEFAULT_MODES] = modes }
    }

    suspend fun getHubName(): String = context.dataStore.data.first()[KEY_HUB_NAME] ?: "Ara Hub"
    suspend fun getHubGreeting(): String = context.dataStore.data.first()[KEY_HUB_GREETING] ?: ""
    suspend fun getHubDefaultRoom(): String = context.dataStore.data.first()[KEY_HUB_DEFAULT_ROOM] ?: ""
    suspend fun getHubDefaultTopic(): String = context.dataStore.data.first()[KEY_HUB_DEFAULT_TOPIC] ?: ""
    suspend fun getHubDefaultModes(): String = context.dataStore.data.first()[KEY_HUB_DEFAULT_MODES] ?: "+nt"
}

enum class DarkModeOption(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}
