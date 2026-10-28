package com.alikarami.drooz.site.auth

import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginParserTest {
    @Test fun formPage() = assertEquals(LoginPage.Form, LoginParser.parse(fixture("login_form.html")))

    @Test fun needsMobilePage() {
        val p = LoginParser.parse(fixture("login_needs_mobile.html")) as LoginPage.NeedsMobile
        assertEquals("000000000000000000000000000000001800000000", p.recaptchaPass)
        assertEquals("0912***0000", p.mobileHint)
    }

    @Test fun wrongCredentials() = assertEquals(LoginPage.WrongCredentials, LoginParser.parse(fixture("login_wrong.html")))

    @Test fun success() = assertEquals(LoginPage.Redirecting, LoginParser.parse(fixture("login_success.html")))

    @Test fun unknownPageYieldsFailedWithAlertText() {
        val html = "<html><body><div class='alert alert-danger'>حساب شما مسدود است</div></body></html>"
        assertEquals(LoginPage.Unknown("حساب شما مسدود است"), LoginParser.parse(html))
    }

    @Test fun formWithUnrecognisedAlertIsUnknown() {
        val html = "<form method='post'><div class='alert alert-danger'>کد امنیتی را وارد کنید</div>" +
            "<input name='username'><input name='password'><input name='captcha'></form>"
        assertEquals(LoginPage.Unknown("کد امنیتی را وارد کنید"), LoginParser.parse(html))
    }
}
