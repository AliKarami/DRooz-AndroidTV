package com.alikarami.drooz.ui.debug

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.DroozApp
import com.alikarami.drooz.R
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.home.HomeScreen
import com.alikarami.drooz.ui.home.HomeViewModel
import com.alikarami.drooz.ui.home.HomeViewModelFactory
import com.alikarami.drooz.ui.theme.DroozTheme
import com.alikarami.drooz.ui.title.PlayRequest
import com.alikarami.drooz.ui.title.TitleScreen
import com.alikarami.drooz.ui.title.TitleViewModel
import com.alikarami.drooz.ui.title.TitleViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Glue until Task 9's navigation: home → title pages as a simple back stack. Each entry owns a
 * ViewModelStore, cleared when the entry is popped.
 */
class TitleStack : ViewModel() {
    data class Entry(val id: Long, val ref: TitleRef, val featuredBackdrop: String?)

    val entries = mutableStateListOf<Entry>()
    private val stores = HashMap<Long, ViewModelStore>()
    private var nextId = 0L

    fun push(ref: TitleRef, featuredBackdrop: String?) {
        entries += Entry(nextId++, ref, featuredBackdrop)
    }

    fun pop(): Entry? = entries.removeLastOrNull()?.also { stores.remove(it.id)?.clear() }

    fun owner(entry: Entry): ViewModelStoreOwner {
        val store = stores.getOrPut(entry.id) { ViewModelStore() }
        return object : ViewModelStoreOwner { override val viewModelStore = store }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: DebugViewModel by viewModels {
        val graph = (application as DroozApp).graph
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DebugViewModel(object : DebugBackend {
                override val isLoggedIn get() = graph.auth.isLoggedIn
                override suspend fun login(username: String, password: String, mobile: String?) =
                    graph.auth.login(username, password, mobile)
                override suspend fun title(ref: TitleRef) = graph.repository.title(ref)
                override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) =
                    graph.repository.openStream(episode, ref, lang, height)
            }) as T
        }
    }

    private val homeVm: HomeViewModel by viewModels { HomeViewModelFactory((application as DroozApp).graph) }
    private val titles: TitleStack by viewModels()
    private var playJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as DroozApp).graph
        setContent {
            DebugApp(vm) {
                DroozTheme {
                    val holder = rememberSaveableStateHolder()
                    val top = titles.entries.lastOrNull()
                    if (top == null) {
                        HomeScreen(homeVm, onOpen = { titles.push(it, homeVm.featuredBackdrop(it)) }, onExit = ::finish)
                    } else {
                        holder.SaveableStateProvider("title:${top.id}") {
                            BackHandler { titles.pop()?.let { holder.removeState("title:${it.id}") } }
                            val titleVm = remember(top.id) {
                                ViewModelProvider(titles.owner(top), TitleViewModelFactory(graph, top.ref))[TitleViewModel::class.java]
                            }
                            val played by graph.titleInfo.playedBackdrops.collectAsStateWithLifecycle()
                            TitleScreen(
                                top.ref, titleVm,
                                onPlay = ::play,
                                onOpenTitle = { titles.push(it, homeVm.featuredBackdrop(it)) },
                                featuredBackdropHash = top.featuredBackdrop,
                                playedBackdrops = played,
                            )
                        }
                    }
                }
            }
        }
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

    /** Title page play request → stream session → the debug player (Plan 3 replaces the player). */
    private fun play(request: PlayRequest) {
        if (playJob?.isActive == true) return
        val graph = (application as DroozApp).graph
        playJob = lifecycleScope.launch {
            try {
                val session = graph.siteApi.openStream(request.episode, request.ref, request.lang, 1080)
                session.backdropPath?.let { graph.titleInfo.remember(request.ref, it) }
                startActivity(DebugPlayerActivity.intent(this@MainActivity, session))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.title_play_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
