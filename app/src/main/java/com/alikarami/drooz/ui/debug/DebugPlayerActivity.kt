package com.alikarami.drooz.ui.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import android.util.Log
import com.alikarami.drooz.BuildConfig
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.alikarami.drooz.DroozApp
import com.alikarami.drooz.site.model.StreamSession

/** Minimal Media3 player for the end-to-end check. The real player is Plan 3. */
class DebugPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as DroozApp).graph
        val url = requireNotNull(intent.getStringExtra(EXTRA_URL))
        val subtitle = intent.getStringExtra(EXTRA_SUB_FA)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(graph.mediaClient)))
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .apply {
                if (subtitle != null) {
                    setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setLanguage("fa")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()))
                }
            }
            .build()
        if (BuildConfig.DEBUG) exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT || it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
                    val f = g.getTrackFormat(0)
                    Log.d("DroozPlayer", "track type=${g.type} lang=${f.language} mime=${f.sampleMimeType} " +
                        "selected=${g.isSelected} supported=${g.isTrackSupported(0)}")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("DroozPlayer", "error ${error.errorCodeName}", error)
            }
            override fun onCues(cueGroup: CueGroup) {
                if (cueGroup.cues.isNotEmpty()) Log.d("DroozPlayer", "cue: ${cueGroup.cues.first().text}")
            }
        })
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        setContentView(PlayerView(this).apply { this.player = exo; keepScreenOn = true })
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_SUB_FA = "sub_fa"

        fun intent(context: Context, session: StreamSession): Intent =
            Intent(context, DebugPlayerActivity::class.java)
                .putExtra(EXTRA_URL, session.masterUrl)
                .putExtra(EXTRA_SUB_FA, session.subtitles.firstOrNull { it.label == "Farsi" }?.url)
    }
}
