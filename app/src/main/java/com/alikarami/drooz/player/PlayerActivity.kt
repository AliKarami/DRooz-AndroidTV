package com.alikarami.drooz.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alikarami.drooz.DroozApp
import com.alikarami.drooz.R
import com.alikarami.drooz.player.engine.ExoPlaybackEngine
import com.alikarami.drooz.player.ui.PlayerScreen
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.ui.theme.DroozTheme
import kotlinx.coroutines.launch

/**
 * Compose host for [PlayerScreen]. The engine is built inside the view-model factory so it shares the
 * view model's lifetime (the view model releases it in onCleared), even across a recreation.
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = PlayerArgs.from(intent)
        if (args == null) {
            Toast.makeText(this, R.string.player_old_entry, Toast.LENGTH_SHORT).show()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { vm.exits.collect { finish() } }
        setContent { DroozTheme { PlayerScreen(vm, engine) } }
        this.vm = vm
    }

    private var vm: PlayerViewModel? = null

    override fun onStop() {
        vm?.onStop()
        super.onStop()
    }

    companion object {
        fun intent(context: Context, args: PlayerArgs): Intent =
            args.toIntent(Intent(context, PlayerActivity::class.java))

        /** Temporary until Task 12 moves the title page to [PlayerArgs]: opens the player, which finishes at once. */
        @Deprecated("A StreamSession carries no TitleRef; use intent(context, PlayerArgs)")
        fun intent(context: Context, @Suppress("UNUSED_PARAMETER") session: StreamSession): Intent =
            Intent(context, PlayerActivity::class.java)
    }
}
