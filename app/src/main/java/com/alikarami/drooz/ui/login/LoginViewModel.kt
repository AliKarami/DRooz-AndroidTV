package com.alikarami.drooz.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.site.ServerAddress
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.auth.LoginResult
import com.alikarami.drooz.ui.common.safeLaunch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl

data class LoginUiState(
    /** The site address, typed by the user (e.g. `example.com`); prefilled with the saved one. */
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val mobile: String = "",
    /** The site's masked number (e.g. `09xx***1234`) once it asks for the full mobile; null before that. */
    val mobileHint: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

object LoginStrings {
    const val WRONG_CREDENTIALS = "نام کاربری یا رمز عبور اشتباه است"
    const val NETWORK = "اتصال به سایت برقرار نشد؛ دوباره تلاش کنید"
    const val BLOCKED = "سایت درخواست را موقتاً مسدود کرد؛ کمی بعد دوباره تلاش کنید"
    fun httpError(code: Int) = "سایت با خطا پاسخ داد (کد $code)؛ کمی بعد دوباره تلاش کنید"
    const val EMPTY_SERVER = "آدرس سایت را وارد کنید"
    const val BAD_SERVER = "آدرس سایت معتبر نیست"
    const val EMPTY_FIELDS = "نام کاربری و رمز عبور را وارد کنید"
    const val EMPTY_MOBILE = "شماره موبایل را وارد کنید"
    const val FAILED = "ورود انجام نشد"
}

/** The site's two-step login; field values stay in the view model so the mobile step keeps them. */
class LoginViewModel(private val auth: AuthApi, private val server: ServerAddress) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState(server = server.current?.displayAddress() ?: ""))
    val uiState: StateFlow<LoginUiState> = _state

    private val _loggedIn = Channel<Unit>(Channel.BUFFERED)
    val loggedIn: Flow<Unit> = _loggedIn.receiveAsFlow()

    fun onServer(v: String) = _state.update { it.copy(server = v, error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onMobile(v: String) = _state.update { it.copy(mobile = v, error = null) }

    fun submit() {
        val s = _state.value
        if (s.busy) return
        if (s.server.isBlank()) {
            _state.update { it.copy(error = LoginStrings.EMPTY_SERVER) }
            return
        }
        val address = SiteConfig.normalize(s.server.toLatinDigits())
        if (address == null) {
            _state.update { it.copy(error = LoginStrings.BAD_SERVER) }
            return
        }
        val user = s.username.trim()
        if (user.isEmpty() || s.password.isEmpty()) {
            _state.update { it.copy(error = LoginStrings.EMPTY_FIELDS) }
            return
        }
        val mobile = s.mobile.toLatinDigits().filter { !it.isWhitespace() }.takeIf { it.isNotEmpty() }
        if (s.mobileHint != null && mobile == null) {
            _state.update { it.copy(error = LoginStrings.EMPTY_MOBILE) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        // A new address drops the old site's session (see FileServerAddress) before this login starts.
        server.set(address)
        viewModelScope.safeLaunch(onError = { e -> _state.update { it.copy(busy = false, error = message(e)) } }) {
            when (val r = auth.login(user, s.password, mobile)) {
                LoginResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    _loggedIn.send(Unit)
                }
                is LoginResult.NeedsMobile -> _state.update { it.copy(busy = false, mobileHint = r.hint) }
                LoginResult.WrongCredentials -> _state.update { it.copy(busy = false, error = LoginStrings.WRONG_CREDENTIALS) }
                is LoginResult.Failed -> _state.update { it.copy(busy = false, error = r.message) }
            }
        }
    }

    /** Only a real connection failure is reported as one: a site error or a block is not the user's network. */
    private fun message(e: Throwable): String = when (e) {
        is SiteException.NetworkError -> LoginStrings.NETWORK
        is SiteException.HttpError -> LoginStrings.httpError(e.code)
        is SiteException.Blocked -> LoginStrings.BLOCKED
        else -> LoginStrings.FAILED
    }
}

/** `https://host/` → `host` (the scheme is shown only when it is not https). */
internal fun HttpUrl.displayAddress(): String {
    val s = toString().removeSuffix("/")
    return if (scheme == "https") s.removePrefix("https://") else s
}

/** Persian/Arabic-Indic digits → ASCII (a phone number typed on a Persian keyboard). */
internal fun String.toLatinDigits(): String = map { c ->
    when (c) {
        in '۰'..'۹' -> '0' + (c - '۰')
        in '٠'..'٩' -> '0' + (c - '٠')
        else -> c
    }
}.joinToString("")

class LoginViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginViewModel(graph.authApi, graph.serverAddress) as T
}
