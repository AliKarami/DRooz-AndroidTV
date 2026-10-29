package com.alikarami.drooz.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.HomePageCache
import com.alikarami.drooz.data.SessionEvents
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** App-level navigation the screens don't decide themselves. */
enum class AppNav { ToLogin }

/** Debug-build launch shortcuts (MainActivity reads them from the intent on a fresh start only). */
data class DebugLaunch(val login: DebugLogin? = null, val playPath: String? = null)
data class DebugLogin(val username: String, val password: String, val mobile: String?)

/**
 * Login vs Home at launch, and the single trip to Login when the session is lost mid-browse. Also holds
 * the [debug] launch shortcuts: a debug login starts at Login (even when logged in: AuthService logs the
 * old session out first) and is handed to the first Login entry; a debug play waits until Home is reached.
 */
class AppViewModel(
    auth: AuthApi,
    events: SessionEvents,
    debug: DebugLaunch? = null,
    private val homeCache: HomePageCache? = null,
    private val titleInfo: TitleInfoRepository? = null,
) : ViewModel() {
    val startDestination: Any = if (auth.isLoggedIn && debug?.login == null) Home else Login

    private var debugLogin: DebugLogin? = debug?.login
    private var debugPlay: TitleRef? = debug?.playPath?.let(TitleRef::fromPath)
    /** A debug play must not start before the login it was launched with has succeeded. */
    private var loginPending = startDestination == Login

    /** The debug credentials, once (the Login entry fills and submits them). */
    fun takeDebugLogin(): DebugLogin? = debugLogin.also { debugLogin = null }

    /** The debug title to open and play, once, and only after any pending login. */
    fun takeDebugPlay(): TitleRef? = if (loginPending) null else debugPlay.also { debugPlay = null }

    private val _navigation = Channel<AppNav>(Channel.BUFFERED)
    val navigation: Flow<AppNav> = _navigation.receiveAsFlow()

    /** True from a lost session until the next login: further failures (other rows, heroes) are the same event. */
    private var routedToLogin = false

    init {
        viewModelScope.launch {
            events.lost.collect {
                forgetSession()
                if (!routedToLogin) {
                    routedToLogin = true
                    _navigation.send(AppNav.ToLogin)
                }
            }
        }
    }

    /** Nothing cached for one account (home page, title pages with their played marks, backdrops) outlives its session. */
    private fun forgetSession() {
        homeCache?.clear()
        titleInfo?.clear()
    }

    fun onLoggedIn() {
        // The next home must come from the new session, never from a page cached before it.
        forgetSession()
        routedToLogin = false
        loginPending = false
    }
}

class AppViewModelFactory(private val graph: AppGraph, private val debug: DebugLaunch?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppViewModel(graph.authApi, graph.sessionEvents, debug, graph.homeCache, graph.titleInfo) as T
}
