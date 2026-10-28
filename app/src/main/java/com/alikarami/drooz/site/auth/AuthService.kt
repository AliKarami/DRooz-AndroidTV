package com.alikarami.drooz.site.auth

import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.http.PersistentCookieJar
import com.alikarami.drooz.site.http.SiteHttp

sealed interface LoginResult {
    data object Success : LoginResult
    data class NeedsMobile(val hint: String) : LoginResult
    data object WrongCredentials : LoginResult
    data class Failed(val message: String) : LoginResult
}

/**
 * The site's two-step login: username+password, then (for a new device) the full mobile number
 * together with the `recaptchaPass` token from the first response.
 */
class AuthService(
    private val http: SiteHttp,
    private val jar: PersistentCookieJar,
    private val creds: CredentialStore,
) {
    val isLoggedIn: Boolean get() = jar.cookie(SESSION_COOKIE) != null

    /** The current session cookie value; changes whenever a login succeeds. */
    val sessionToken: String? get() = jar.cookie(SESSION_COOKIE)

    suspend fun login(username: String, password: String, mobile: String?): LoginResult {
        // Like a browser, load the form first: the site pairs its `sf` session cookie with the
        // `recaptchaPass` token, and posting without one makes the mobile step fail (HTTP 502).
        http.get(SiteConfig.LOGIN_PATH, requiresAuth = false)
        val base = listOf("username" to username, "password" to password)
        val result = when (val first = post(base)) {
            is LoginPage.NeedsMobile -> {
                if (mobile.isNullOrBlank()) return LoginResult.NeedsMobile(first.mobileHint)
                toResult(post(base + listOf("recaptchaPass" to first.recaptchaPass, "mobile" to mobile)))
            }
            else -> toResult(first)
        }
        if (result == LoginResult.Success) {
            creds.save(Credentials(username, password, mobile))
            reportDevice()
        }
        return result
    }

    /**
     * Renews an expired session with the stored credentials; the `sf` device cookie is kept.
     * Returns false only when the site refuses the login; network/HTTP failures propagate.
     */
    suspend fun relogin(): Boolean {
        val c = creds.load() ?: return false
        return login(c.username, c.password, c.mobile) == LoginResult.Success
    }

    suspend fun logout() {
        runCatching { http.get("/user/logout/", requiresAuth = false) }
        jar.clear(keep = setOf(DEVICE_COOKIE))
        creds.clear()
    }

    private suspend fun post(fields: List<Pair<String, String>>): LoginPage =
        LoginParser.parse(http.postMultipart(SiteConfig.LOGIN_PATH, fields).body)

    private fun toResult(page: LoginPage): LoginResult = when (page) {
        LoginPage.Redirecting -> if (isLoggedIn) LoginResult.Success else LoginResult.Failed("ورود انجام نشد")
        LoginPage.WrongCredentials -> LoginResult.WrongCredentials
        is LoginPage.NeedsMobile -> LoginResult.Failed("شماره موبایل پذیرفته نشد")
        LoginPage.Form -> LoginResult.Failed("ورود انجام نشد")
        is LoginPage.Unknown -> LoginResult.Failed(page.alertText ?: "پاسخ ناشناخته از سایت")
    }

    /** The website reports the screen once; we report the Streamer's 4K panel. */
    private suspend fun reportDevice() {
        try {
            http.get("/json/device/?width=3840&height=2160&pixelRatio=1", requiresAuth = false)
        } catch (_: SiteException) {
        }
    }

    private companion object {
        const val SESSION_COOKIE = "cf"
        const val DEVICE_COOKIE = "sf"
    }
}
