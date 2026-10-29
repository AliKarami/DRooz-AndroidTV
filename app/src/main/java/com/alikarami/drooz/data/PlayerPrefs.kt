package com.alikarami.drooz.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** FA_DUB is per-playback only: it is never stored as the global choice. */
enum class SubtitleChoice { FA, EN, DUAL, FA_DUB, OFF }

enum class SubtitleColor(val argb: Long) {
    WHITE(0xFFFFFFFF), YELLOW(0xFFFFFF00), CYAN(0xFF7FE0FF), GREEN(0xFFA6F28C)
}

data class SubtitleStyle(
    val sizePercent: Int = 100,
    val color: SubtitleColor = SubtitleColor.YELLOW,
    val backgroundOpacity: Int = 75,
)

data class PlayerSettings(
    val subtitle: SubtitleChoice = SubtitleChoice.FA,
    val style: SubtitleStyle = SubtitleStyle(),
    val audioBoost: Boolean = true,
)

val SUBTITLE_SIZES = 60..200 step 10
val BACKGROUND_OPACITIES = listOf(0, 25, 50, 75, 100)

/** Global player settings. */
interface PlayerPrefs {
    val settings: Flow<PlayerSettings>
    suspend fun update(transform: (PlayerSettings) -> PlayerSettings)
}

fun playerPrefsStore(context: Context): DataStore<Preferences> = context.applicationContext.playerPrefsDataStore

private val Context.playerPrefsDataStore: DataStore<Preferences> by preferencesDataStore("player_prefs")

class DataStorePlayerPrefs(private val store: DataStore<Preferences>) : PlayerPrefs {
    private val subtitleKey = stringPreferencesKey("subtitle_choice")
    private val colorKey = stringPreferencesKey("subtitle_color")
    private val sizeKey = intPreferencesKey("subtitle_size")
    private val opacityKey = intPreferencesKey("subtitle_bg_opacity")
    private val boostKey = booleanPreferencesKey("audio_boost")

    override val settings: Flow<PlayerSettings> = store.data.map { read(it) }

    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        store.edit { p ->
            val current = read(p)
            val next = transform(current)
            // FA_DUB is per-playback only: keep the previously stored choice.
            val choice = if (next.subtitle == SubtitleChoice.FA_DUB) current.subtitle else next.subtitle
            p[subtitleKey] = choice.name
            p[colorKey] = next.style.color.name
            p[sizeKey] = next.style.sizePercent
            p[opacityKey] = next.style.backgroundOpacity
            p[boostKey] = next.audioBoost
        }
    }

    private fun read(p: Preferences): PlayerSettings {
        val d = PlayerSettings()
        return PlayerSettings(
            subtitle = p[subtitleKey].toEnumOrNull<SubtitleChoice>()?.takeIf { it != SubtitleChoice.FA_DUB } ?: d.subtitle,
            style = SubtitleStyle(
                sizePercent = p[sizeKey]?.takeIf { it in SUBTITLE_SIZES } ?: d.style.sizePercent,
                color = p[colorKey].toEnumOrNull<SubtitleColor>() ?: d.style.color,
                backgroundOpacity = p[opacityKey]?.takeIf { it in BACKGROUND_OPACITIES } ?: d.style.backgroundOpacity,
            ),
            audioBoost = p[boostKey] ?: d.audioBoost,
        )
    }

    private inline fun <reified E : Enum<E>> String?.toEnumOrNull(): E? =
        this?.let { n -> enumValues<E>().firstOrNull { it.name == n } }
}
