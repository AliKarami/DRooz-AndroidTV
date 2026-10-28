package com.alikarami.drooz.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.DroozApp
import com.alikarami.drooz.ui.login.LoginPreview
import com.alikarami.drooz.ui.theme.DroozTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as DroozApp).graph
    private var debug: DebugLaunch? = null
    private val appVm: AppViewModel by viewModels { AppViewModelFactory(graph, debug) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Lets Compose see the IME's insets (Android 14 TV: otherwise the decor consumes them); a TV has no
        // system bars to draw under.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preview = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_PREVIEW_LOGIN) else null
        // Read before appVm is first touched: its factory takes them (a recreated activity keeps its VM).
        debug = if (BuildConfig.DEBUG && savedInstanceState == null) debugLaunch(intent) else null
        setContent {
            DroozTheme {
                if (preview != null) {
                    LoginPreview(withMobile = preview == "mobile")
                } else {
                    DroozNavHost(graph, appVm, rememberNavController(), onExit = ::finish)
                }
            }
        }
    }

    private companion object {
        /**
         * Debug builds only:
         * - `--es debug_user U --es debug_pass P [--es debug_mobile M]` logs in without the TV keyboard;
         * - `--es debug_play /post/series/5735053/pluribus/` opens a title and plays it;
         * - `--es debug_preview_login empty|mobile` shows the login form whose submit only toasts (the
         *   session is untouched).
         */
        const val EXTRA_PREVIEW_LOGIN = "debug_preview_login"

        fun debugLaunch(intent: Intent): DebugLaunch? {
            val user = intent.getStringExtra("debug_user")
            val pass = intent.getStringExtra("debug_pass")
            val login = if (user != null && pass != null) DebugLogin(user, pass, intent.getStringExtra("debug_mobile")) else null
            val play = intent.getStringExtra("debug_play")
            return if (login == null && play == null) null else DebugLaunch(login, play)
        }
    }
}
