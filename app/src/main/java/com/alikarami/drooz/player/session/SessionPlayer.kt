@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.session

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.alikarami.drooz.player.ui.SEEK_STEP_MS

/** The view model's actions, as the media session may use them. */
interface SessionControls {
    fun togglePlay()
    fun play()
    fun pause()
    /** Snapped to keyframes, like the remote's seeks. */
    fun seekBy(deltaMs: Long)
    fun seekTo(ms: Long)
    fun playNext()
    /** Save and exit, like Back. */
    fun stop()
}

/**
 * The ExoPlayer as the media session sees it: state and position read through, every command routed to
 * [controls] (the view model: wantsPlay, keyframe snapping, saving) instead of the ExoPlayer. Advertises
 * only [sessionCommands]; the metadata is the screen's title / episode label / backdrop ([update]).
 * Main thread only, like the ExoPlayer.
 */
class SessionPlayer(player: Player, private val controls: SessionControls) : ForwardingPlayer(player) {
    private var info = SessionInfo()
    private var commands: Player.Commands = commandsOf(info)
    private var metadata: MediaMetadata = metadataOf(info)
    /** Each listener the session added, and the filter it was registered through. */
    private val filters = mutableMapOf<Player.Listener, Player.Listener>()

    fun update(newInfo: SessionInfo) {
        if (newInfo == info) return
        info = newInfo
        val newCommands = commandsOf(newInfo)
        val newMetadata = metadataOf(newInfo)
        val commandsChanged = newCommands != commands
        val metadataChanged = newMetadata != metadata
        commands = newCommands
        metadata = newMetadata
        for (l in filters.keys.toList()) {
            if (commandsChanged) l.onAvailableCommandsChanged(commands)
            if (metadataChanged) l.onMediaMetadataChanged(metadata)
        }
    }

    /** A media button that reached the session; acts only through an advertised command. */
    fun press(key: MediaKey) {
        when (key) {
            MediaKey.TOGGLE -> if (can(Player.COMMAND_PLAY_PAUSE)) controls.togglePlay()
            MediaKey.PLAY -> play()
            MediaKey.PAUSE -> pause()
            MediaKey.BACK -> seekBack()
            MediaKey.FORWARD -> seekForward()
            MediaKey.NEXT -> seekToNext()
            MediaKey.STOP -> stop()
        }
    }

    private fun can(command: Int) = commands.contains(command)

    // ---- what the session reads ----

    override fun getAvailableCommands(): Player.Commands = commands
    override fun isCommandAvailable(command: Int): Boolean = can(command)
    override fun getMediaMetadata(): MediaMetadata = metadata
    override fun getSeekBackIncrement(): Long = SEEK_STEP_MS
    override fun getSeekForwardIncrement(): Long = SEEK_STEP_MS

    // The ExoPlayer's own available-commands / metadata events would undo ours: they pass a filter.
    override fun addListener(listener: Player.Listener) {
        if (listener in filters) return
        val filter = SessionListenerFilter(listener, { commands }, { metadata })
        filters[listener] = filter
        super.addListener(filter)
    }

    override fun removeListener(listener: Player.Listener) {
        filters.remove(listener)?.let { super.removeListener(it) }
    }

    // ---- commands: through the view model, never straight to the ExoPlayer ----

    override fun play() {
        if (can(Player.COMMAND_PLAY_PAUSE)) controls.play()
    }

    override fun pause() {
        if (can(Player.COMMAND_PLAY_PAUSE)) controls.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()

    override fun seekBack() {
        if (can(Player.COMMAND_SEEK_BACK)) controls.seekBy(-SEEK_STEP_MS)
    }

    override fun seekForward() {
        if (can(Player.COMMAND_SEEK_FORWARD)) controls.seekBy(SEEK_STEP_MS)
    }

    override fun seekTo(positionMs: Long) {
        if (can(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) controls.seekTo(positionMs)
    }

    // One item only: an index-and-position seek is a seek in it.
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekTo(positionMs)

    override fun seekToNext() {
        if (can(Player.COMMAND_SEEK_TO_NEXT)) controls.playNext()
    }

    override fun seekToNextMediaItem() = seekToNext()

    override fun stop() {
        if (can(Player.COMMAND_STOP)) controls.stop()
    }

    // Not advertised; a stray call must not touch the engine (prepare would undo a suspend).
    override fun prepare() = Unit
    override fun seekToDefaultPosition() = Unit
    override fun seekToDefaultPosition(mediaItemIndex: Int) = Unit
    override fun seekToPrevious() = Unit
    override fun seekToPreviousMediaItem() = Unit

    private companion object {
        fun commandsOf(info: SessionInfo): Player.Commands =
            Player.Commands.Builder().addAll(*sessionCommands(info.controllable, info.hasNext).toIntArray()).build()

        fun metadataOf(info: SessionInfo): MediaMetadata = MediaMetadata.Builder()
            .setTitle(info.title)
            .setDisplayTitle(info.title)
            .setSubtitle(info.subtitle)
            .setArtist(info.subtitle)
            .setArtworkUri(info.artworkUrl?.let(Uri::parse))
            .setMediaType(if (info.subtitle != null) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    }
}
