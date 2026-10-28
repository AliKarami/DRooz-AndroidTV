package com.alikarami.drooz.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.DroozApp
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: DebugViewModel by viewModels {
        val graph = (application as DroozApp).graph
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DebugViewModel(object : DebugBackend {
                override val isLoggedIn get() = graph.auth.isLoggedIn
                override suspend fun login(username: String, password: String, mobile: String?) =
                    graph.auth.login(username, password, mobile)
                override suspend fun home() = graph.repository.home()
                override suspend fun title(ref: TitleRef) = graph.repository.title(ref)
                override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) =
                    graph.repository.openStream(episode, ref, lang, height)
            }) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DebugApp(vm) }
        // Debug builds only: `adb shell am start ... --es debug_user U --es debug_pass P --es debug_mobile M`
        // logs in without typing on the TV keyboard.
        val user = intent.getStringExtra("debug_user")
        val pass = intent.getStringExtra("debug_pass")
        if (BuildConfig.DEBUG && savedInstanceState == null && user != null && pass != null) {
            vm.login(user, pass, intent.getStringExtra("debug_mobile"))
        }
        // Debug builds only: `--es debug_play /post/series/5735053/pluribus/` opens and plays a title.
        val playPath = intent.getStringExtra("debug_play")
        if (BuildConfig.DEBUG && savedInstanceState == null && playPath != null) {
            TitleRef.fromPath(playPath)?.let(vm::openAndPlay)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.playback.collect { session ->
                    if (session != null) {
                        vm.playbackStarted()
                        startActivity(DebugPlayerActivity.intent(this@MainActivity, session))
                    }
                }
            }
        }
    }
}
