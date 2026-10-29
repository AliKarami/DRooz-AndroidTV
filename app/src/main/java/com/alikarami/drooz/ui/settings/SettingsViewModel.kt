package com.alikarami.drooz.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.BACKGROUND_OPACITIES
import com.alikarami.drooz.data.CacheCleaner
import com.alikarami.drooz.data.PlayerPrefs
import com.alikarami.drooz.data.PlayerSettings
import com.alikarami.drooz.data.SUBTITLE_SIZES
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleColor
import com.alikarami.drooz.data.TitlePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val player: PlayerSettings,
    val defaultDub: Boolean,
    val username: String?,
    val version: String,
    val cacheCleared: Boolean,
    val confirmLogout: Boolean,
    val busy: Boolean,
)

sealed interface SettingsEvent { data object LoggedOut : SettingsEvent }

/** Global player settings, default dub, cache clearing and logout. Prefs failures never crash the screen. */
class SettingsViewModel(
    private val playerPrefs: PlayerPrefs,
    private val titlePrefs: TitlePrefs,
    private val auth: AuthApi,
    private val cache: CacheCleaner,
    version: String,
) : ViewModel() {
    private data class Local(
        val defaultDub: Boolean = false,
        val cacheCleared: Boolean = false,
        val confirmLogout: Boolean = false,
        val busy: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val username: String? = runCatching { auth.username }.getOrNull()

    val uiState: StateFlow<SettingsUiState> = combine(playerPrefs.settings, local) { p, l ->
        SettingsUiState(p, l.defaultDub, username, version, l.cacheCleared, l.confirmLogout, l.busy)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        SettingsUiState(PlayerSettings(), false, username, version, false, false, false),
    )

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val on = try { titlePrefs.defaultDub() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            local.update { it.copy(defaultDub = on) }
        }
    }

    fun setSubtitle(choice: SubtitleChoice) {
        if (choice == SubtitleChoice.FA_DUB) return
        updatePlayer { it.copy(subtitle = choice) }
    }

    fun stepSize(delta: Int) {
        val sizes = SUBTITLE_SIZES.toList()
        updatePlayer { s ->
            val i = sizes.indexOf(s.style.sizePercent).takeIf { it >= 0 } ?: sizes.indexOf(100)
            val next = sizes[(i + delta).coerceIn(0, sizes.lastIndex)]
            s.copy(style = s.style.copy(sizePercent = next))
        }
    }

    fun setColor(c: SubtitleColor) = updatePlayer { it.copy(style = it.style.copy(color = c)) }

    fun setBackground(opacity: Int) {
        if (opacity !in BACKGROUND_OPACITIES) return
        updatePlayer { it.copy(style = it.style.copy(backgroundOpacity = opacity)) }
    }

    fun setAudioBoost(on: Boolean) = updatePlayer { it.copy(audioBoost = on) }

    fun setDefaultDub(on: Boolean) {
        local.update { it.copy(defaultDub = on) }
        viewModelScope.launch { safely { titlePrefs.setDefaultDub(on) } }
    }

    fun clearCache() {
        if (local.value.busy) return
        local.update { it.copy(busy = true, cacheCleared = false) }
        viewModelScope.launch {
            val ok = safely { cache.clear() }
            local.update { it.copy(busy = false, cacheCleared = ok) }
        }
    }

    /** Ignored while a clear or a logout is under way: a second logout must not start mid-flight. */
    fun requestLogout() = local.update { if (it.busy) it else it.copy(confirmLogout = true) }
    fun cancelLogout() = local.update { it.copy(confirmLogout = false) }

    /** Logs out and always emits [SettingsEvent.LoggedOut]: a failed site request still ends the local session. */
    fun confirmLogout() {
        if (local.value.busy) return
        local.update { it.copy(confirmLogout = false, busy = true) }
        viewModelScope.launch {
            safely { auth.logout() }
            local.update { it.copy(busy = false) }
            _events.send(SettingsEvent.LoggedOut)
        }
    }

    private fun updatePlayer(transform: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.launch { safely { playerPrefs.update(transform) } }
    }

    private suspend inline fun safely(block: () -> Unit): Boolean =
        try { block(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
}

class SettingsViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(graph.playerPrefs, graph.titlePrefs, graph.authApi, graph.cacheCleaner, BuildConfig.VERSION_NAME) as T
}
