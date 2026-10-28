package com.alikarami.drooz.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.SessionEvents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** App-level navigation the screens don't decide themselves. */
enum class AppNav { ToLogin }

/** Login vs Home at launch, and the single trip to Login when the session is lost mid-browse. */
class AppViewModel(auth: AuthApi, events: SessionEvents) : ViewModel() {
    val startDestination: Any = if (auth.isLoggedIn) Home else Login

    private val _navigation = Channel<AppNav>(Channel.BUFFERED)
    val navigation: Flow<AppNav> = _navigation.receiveAsFlow()

    /** True from a lost session until the next login: further failures (other rows, heroes) are the same event. */
    private var routedToLogin = false

    init {
        viewModelScope.launch {
            events.lost.collect {
                if (!routedToLogin) {
                    routedToLogin = true
                    _navigation.send(AppNav.ToLogin)
                }
            }
        }
    }

    fun onLoggedIn() {
        routedToLogin = false
    }
}

class AppViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph.authApi, graph.sessionEvents) as T
}
