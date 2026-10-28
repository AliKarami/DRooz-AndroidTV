package com.alikarami.drooz.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.site.auth.LoginResult
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.StreamCatalog
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the throwaway debug UI needs from the site layer. */
interface DebugBackend {
    val isLoggedIn: Boolean
    suspend fun login(username: String, password: String, mobile: String?): LoginResult
    suspend fun title(ref: TitleRef): TitleDetail
    suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession
}

sealed interface DebugState {
    data object Loading : DebugState
    data class Login(val mobileHint: String?, val error: String?) : DebugState
    /** The real home screen (ui/home) is shown; it loads its own data. */
    data object Home : DebugState
    data class Title(val detail: TitleDetail) : DebugState
    data class Error(val message: String) : DebugState
}

/** First episode not marked played (across seasons), else the very first episode. */
fun firstUnplayed(catalog: StreamCatalog): Episode? {
    val all = catalog.main.flatMap { it.episodes }
    return all.firstOrNull { !it.played } ?: all.firstOrNull()
}

class DebugViewModel(private val backend: DebugBackend) : ViewModel() {
    private val _state = MutableStateFlow<DebugState>(DebugState.Loading)
    val state: StateFlow<DebugState> = _state

    private val _playback = MutableStateFlow<StreamSession?>(null)
    val playback: StateFlow<StreamSession?> = _playback

    init {
        _state.value = if (backend.isLoggedIn) DebugState.Home else DebugState.Login(null, null)
    }

    fun login(username: String, password: String, mobile: String?) = launch {
        _state.value = when (val r = backend.login(username, password, mobile)) {
            LoginResult.Success -> DebugState.Home
            is LoginResult.NeedsMobile -> DebugState.Login(mobileHint = r.hint, error = null)
            LoginResult.WrongCredentials -> DebugState.Login(mobileHint = null, error = "نام کاربری یا رمز عبور اشتباه است")
            is LoginResult.Failed -> DebugState.Login(mobileHint = null, error = r.message)
        }
    }

    fun backToHome() {
        _state.value = DebugState.Home
    }

    fun openTitle(ref: TitleRef) = launch {
        _state.value = DebugState.Loading
        _state.value = DebugState.Title(backend.title(ref))
    }

    /** Debug shortcut: open a title and immediately play its first unwatched episode. */
    fun openAndPlay(ref: TitleRef) = launch {
        val detail = backend.title(ref)
        _state.value = DebugState.Title(detail)
        val ep = firstUnplayed(detail.catalog) ?: return@launch
        _playback.value = backend.openStream(ep, ref, "original", 1080)
    }

    fun play(episode: Episode? = null) = launch {
        val detail = (_state.value as? DebugState.Title)?.detail ?: return@launch
        val ep = episode ?: firstUnplayed(detail.catalog) ?: return@launch
        _playback.value = backend.openStream(ep, detail.card.ref, "original", 1080)
    }

    fun playbackStarted() {
        _playback.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.value = DebugState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
