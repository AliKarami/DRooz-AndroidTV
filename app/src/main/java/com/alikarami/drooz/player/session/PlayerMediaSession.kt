@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.session

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import okhttp3.OkHttpClient

/** Backdrops are wide; the now-playing surface shows them small. */
private const val ARTWORK_MAX_PX = 1280

/**
 * The system's view of the player (Assistant "pause", CEC / remote media keys the activity did not take,
 * the Google TV now-playing card). Lives between the activity's onStart and onStop only: a hidden player
 * has no session, so nothing can resume it or keep its decoders. No service, no notification.
 *
 * Media buttons that reach it are only those the activity did not consume (the player consumes every
 * event of the keys it acts on, see isPlayerMediaKey); they act on their first down only, so a repeat
 * or a framework double-tap ("next") never fires.
 */
class PlayerMediaSession(
    context: Context,
    player: Player,
    client: OkHttpClient,
    controls: SessionControls,
    /** Opened by the now-playing card: this player screen (singleTop, so it is brought back, not stacked). */
    sessionActivity: PendingIntent,
) {
    private val sessionPlayer = SessionPlayer(player, controls)

    private val session: MediaSession = MediaSession.Builder(context, sessionPlayer)
        .setId(SESSION_ID)
        .setSessionActivity(sessionActivity)
        .setBitmapLoader(
            CacheBitmapLoader(
                DataSourceBitmapLoader.Builder(context)
                    .setDataSourceFactory(OkHttpDataSource.Factory(client))
                    .setMaximumOutputDimension(ARTWORK_MAX_PX)
                    .build()
            )
        )
        .setCallback(object : MediaSession.Callback {
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent,
            ): Boolean {
                val e = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    ?: return false
                return when (val b = mediaButton(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount)) {
                    is MediaButton.Press -> {
                        sessionPlayer.press(b.key)
                        true
                    }
                    MediaButton.Ignore -> true
                    MediaButton.NotOurs -> false
                }
            }
        })
        .build()

    fun update(info: SessionInfo) = sessionPlayer.update(info)

    fun release() = session.release()

    private companion object {
        const val SESSION_ID = "drooz-player"
    }
}
