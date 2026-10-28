package com.alikarami.drooz.site.auth

import org.jsoup.Jsoup

sealed interface LoginPage {
    data object Form : LoginPage
    data class NeedsMobile(val recaptchaPass: String, val mobileHint: String) : LoginPage
    data object WrongCredentials : LoginPage
    data object Redirecting : LoginPage
    data class Unknown(val alertText: String?) : LoginPage
}

/** Classifies the HTML returned by `/user/login/`. Only alerts inside the form are login messages. */
object LoginParser {
    private const val REDIRECTING = "در حال انتقال"
    private const val WRONG = "نام کاربری یا رمز عبور اشتباه"
    private const val COMPLETE_MOBILE = "شماره موبایل"

    fun parse(html: String): LoginPage {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form")
        val alert = (form ?: doc).select(if (form != null) ".alert" else ".alert-danger")
            .firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }

        if (alert != null && alert.contains(REDIRECTING)) return LoginPage.Redirecting
        if (alert != null && alert.contains(WRONG)) return LoginPage.WrongCredentials
        if (form == null) return LoginPage.Unknown(alert)

        val names = form.select("input[name]").map { it.attr("name") }.toSet()
        val recaptcha = form.selectFirst("input[name=recaptchaPass]")?.attr("value").orEmpty()
        val mobile = form.selectFirst("input[name=mobile]")
        if (mobile != null && recaptcha.isNotEmpty() && (alert == null || alert.contains(COMPLETE_MOBILE))) {
            return LoginPage.NeedsMobile(recaptcha, mobile.attr("placeholder"))
        }
        if (alert == null && names == setOf("username", "password")) return LoginPage.Form
        return LoginPage.Unknown(alert)
    }
}
