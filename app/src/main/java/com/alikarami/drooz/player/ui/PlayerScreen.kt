@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.ui

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.player.PlayerPhase
import com.alikarami.drooz.player.PlayerViewModel
import com.alikarami.drooz.player.engine.ExoPlaybackEngine
import com.alikarami.drooz.ui.common.BusySpinner
import com.alikarami.drooz.ui.common.DroozImage
import com.alikarami.drooz.ui.common.ImageSizes
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.theme.DroozColors

/**
 * The player: video (Media3 surface only, no controller, no ExoPlayer subtitles), the app's own
 * subtitles placed in the letterbox bars, and the loading / buffering / failed states.
 */
@Composable
fun PlayerScreen(vm: PlayerViewModel, engine: ExoPlaybackEngine) {
    val state by vm.state.collectAsStateWithLifecycle()
    val videoSize by engine.videoSize.collectAsStateWithLifecycle()
    val buffering by engine.buffering.collectAsStateWithLifecycle()

    // Back saves and exits while loading / failed. PlayerOverlayHost registers its own (later, so
    // higher-priority) BackHandler that closes panels / controls / the up-next card first.
    BackHandler { vm.close() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val containerW = constraints.maxWidth
        val containerH = constraints.maxHeight

        VideoSurface(engine, Modifier.fillMaxSize())

        when (val phase = state.phase) {
            PlayerPhase.Loading -> LoadingScreen(state.title, state.episodeLabel, state.backdropUrl)
            is PlayerPhase.Failed -> FailedScreen(phase.message, vm::retry)
            PlayerPhase.Playing -> PlayerOverlayHost(vm, engine, state, videoSize, buffering, containerW, containerH)
        }
    }
}

@Composable
private fun VideoSurface(engine: ExoPlaybackEngine, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                subtitleView?.visibility = View.GONE
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                isFocusable = false
                player = engine.exoPlayer
            }
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@Composable
private fun LoadingScreen(title: String, episodeLabel: String?, backdropUrl: String?) {
    Box(Modifier.fillMaxSize().background(DroozColors.Background)) {
        if (backdropUrl != null) {
            DroozImage(backdropUrl, ImageSizes.HERO_W, ImageSizes.HERO_H, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title.isNotEmpty()) {
                TitleText(title, MaterialTheme.typography.displayMedium, color = Color.White)
            }
            if (episodeLabel != null) {
                Text(episodeLabel, style = MaterialTheme.typography.titleLarge, color = DroozColors.TextSecondary)
            }
            BusySpinner(Modifier.padding(top = 16.dp), size = 48.dp, stroke = 4.dp, color = Color.White)
        }
    }
}

@Composable
private fun FailedScreen(message: String, onRetry: () -> Unit) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { retry.requestFocusSoon() }
    Box(Modifier.fillMaxSize().background(DroozColors.Background)) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message, style = MaterialTheme.typography.titleLarge, color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retry)) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}
