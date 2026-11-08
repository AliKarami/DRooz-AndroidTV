package com.alikarami.drooz.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alikarami.drooz.DroozApp
import com.alikarami.drooz.player.engine.ExoPlaybackEngine
import com.alikarami.drooz.player.session.PlayerMediaSession
import com.alikarami.drooz.player.session.SessionControls
import com.alikarami.drooz.player.session.sessionInfo
import com.alikarami.drooz.player.ui.PlayerScreen
import com.alikarami.drooz.ui.theme.DroozTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Compose host for [PlayerScreen]. The engine is built inside the view-model factory so it shares the
 * view model's lifetime (the view model releases it in onCleared), even across a recreation.
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = PlayerArgs.from(intent)
        if (args == null) {
            finish()
            return
        }
        val graph = (application as DroozApp).graph
        val vm: PlayerViewModel by viewModels {
            viewModelFactory {
                initializer {
                    PlayerViewModel(
                        args,
                        ExoPlaybackEngine(applicationContext, graph.mediaClient),
                        graph.siteApi, graph.titleInfo, graph.titlePrefs, graph.watchStore, graph.playerPrefs,
                    )
                }
            }
        }
        val engine = vm.engine as ExoPlaybackEngine
        lifecycleScope.launch {
            vm.keepScreenOn.collect { on ->
                if (on) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        lifecycleScope.launch { vm.exits.collect { finish() } }
        setContent { DroozTheme { PlayerScreen(vm, engine) } }
        this.vm = vm
        this.client = graph.mediaClient
    }

    private var vm: PlayerViewModel? = null
    private var client: OkHttpClient? = null
    private var session: PlayerMediaSession? = null
    private var sessionInfoJob: Job? = null

    override fun onStart() {
        super.onStart()
        val vm = vm ?: return
        vm.onStart()
        startSession(vm)
    }

    // A configuration change the manifest does not list recreates the activity: the view model and the
    // playback carry on, so it is not a trip to the background (no pause, no decoder release).
    // The session goes first: nothing may command the player once it is being suspended.
    override fun onStop() {
        stopSession()
        if (!isChangingConfigurations) vm?.onStop()
        super.onStop()
    }

    /**
     * The media session exists only while the player is visible (see [PlayerMediaSession]); its commands
     * go through the view model, like the remote's.
     */
    private fun startSession(vm: PlayerViewModel) {
        if (session != null) return
        val engine = vm.engine as ExoPlaybackEngine
        val controls = object : SessionControls {
            override fun togglePlay() = vm.togglePlay()
            override fun play() = vm.play()
            override fun pause() = vm.pause()
            override fun seekBy(deltaMs: Long) = vm.seekBy(deltaMs)
            override fun seekTo(ms: Long) = vm.seekTo(ms)
            override fun playNext() = vm.playNext()
            override fun stop() = vm.close()
        }
        val reopen = PendingIntent.getActivity(
            this, 0, Intent(intent).setClass(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = PlayerMediaSession(this, engine.exoPlayer, client ?: return, controls, reopen)
        session = s
        sessionInfoJob = lifecycleScope.launch {
            vm.state.map(::sessionInfo).distinctUntilChanged().collect { s.update(it) }
        }
    }

    private fun stopSession() {
        sessionInfoJob?.cancel()
        sessionInfoJob = null
        session?.release()
        session = null
    }

    companion object {
        fun intent(context: Context, args: PlayerArgs): Intent =
            args.toIntent(Intent(context, PlayerActivity::class.java))
    }
}
