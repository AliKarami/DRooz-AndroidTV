package com.alikarami.drooz.player.session;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import java.util.List;
import java.util.function.Supplier;

/**
 * A listener the media session registered on [SessionPlayer], as the ExoPlayer sees it: every event passes
 * through unchanged except available commands and metadata, which are replaced by the session player's own.
 *
 * Java on purpose: Kotlin's {@code by} delegation does not forward Java default methods, and every
 * Player.Listener method is one, so a Kotlin delegate silently dropped play / pause / seek / state events
 * and the system's now-playing state never changed. Each method is forwarded explicitly; the unit test
 * fails if a media3 upgrade adds one that is not.
 */
final class SessionListenerFilter implements Player.Listener {
    private final Player.Listener target;
    private final Supplier<Player.Commands> commands;
    private final Supplier<MediaMetadata> metadata;

    SessionListenerFilter(Player.Listener target, Supplier<Player.Commands> commands, Supplier<MediaMetadata> metadata) {
        this.target = target;
        this.commands = commands;
        this.metadata = metadata;
    }

    @Override public void onEvents(Player a0, Player.Events a1) { target.onEvents(a0, a1); }
    @Override public void onTimelineChanged(Timeline a0, int a1) { target.onTimelineChanged(a0, a1); }
    @Override public void onMediaItemTransition(MediaItem a0, int a1) { target.onMediaItemTransition(a0, a1); }
    @Override public void onTracksChanged(Tracks a0) { target.onTracksChanged(a0); }
    @Override public void onMediaMetadataChanged(MediaMetadata a0) { target.onMediaMetadataChanged(metadata.get()); }
    @Override public void onPlaylistMetadataChanged(MediaMetadata a0) { target.onPlaylistMetadataChanged(a0); }
    @Override public void onIsLoadingChanged(boolean a0) { target.onIsLoadingChanged(a0); }
    @SuppressWarnings("deprecation")
    @Override public void onLoadingChanged(boolean a0) { target.onLoadingChanged(a0); }
    @Override public void onAvailableCommandsChanged(Player.Commands a0) { target.onAvailableCommandsChanged(commands.get()); }
    @Override public void onTrackSelectionParametersChanged(TrackSelectionParameters a0) { target.onTrackSelectionParametersChanged(a0); }
    @SuppressWarnings("deprecation")
    @Override public void onPlayerStateChanged(boolean a0, int a1) { target.onPlayerStateChanged(a0, a1); }
    @Override public void onPlaybackStateChanged(int a0) { target.onPlaybackStateChanged(a0); }
    @Override public void onPlayWhenReadyChanged(boolean a0, int a1) { target.onPlayWhenReadyChanged(a0, a1); }
    @Override public void onPlaybackSuppressionReasonChanged(int a0) { target.onPlaybackSuppressionReasonChanged(a0); }
    @Override public void onIsPlayingChanged(boolean a0) { target.onIsPlayingChanged(a0); }
    @Override public void onRepeatModeChanged(int a0) { target.onRepeatModeChanged(a0); }
    @Override public void onShuffleModeEnabledChanged(boolean a0) { target.onShuffleModeEnabledChanged(a0); }
    @Override public void onPlayerError(PlaybackException a0) { target.onPlayerError(a0); }
    @Override public void onPlayerErrorChanged(PlaybackException a0) { target.onPlayerErrorChanged(a0); }
    @SuppressWarnings("deprecation")
    @Override public void onPositionDiscontinuity(int a0) { target.onPositionDiscontinuity(a0); }
    @SuppressWarnings("deprecation")
    @Override public void onPositionDiscontinuity(Player.PositionInfo a0, Player.PositionInfo a1, int a2) { target.onPositionDiscontinuity(a0, a1, a2); }
    @Override public void onPlaybackParametersChanged(PlaybackParameters a0) { target.onPlaybackParametersChanged(a0); }
    @Override public void onSeekBackIncrementChanged(long a0) { target.onSeekBackIncrementChanged(a0); }
    @Override public void onSeekForwardIncrementChanged(long a0) { target.onSeekForwardIncrementChanged(a0); }
    @Override public void onMaxSeekToPreviousPositionChanged(long a0) { target.onMaxSeekToPreviousPositionChanged(a0); }
    @Override public void onAudioSessionIdChanged(int a0) { target.onAudioSessionIdChanged(a0); }
    @Override public void onAudioAttributesChanged(AudioAttributes a0) { target.onAudioAttributesChanged(a0); }
    @Override public void onVolumeChanged(float a0) { target.onVolumeChanged(a0); }
    @Override public void onSkipSilenceEnabledChanged(boolean a0) { target.onSkipSilenceEnabledChanged(a0); }
    @Override public void onDeviceInfoChanged(DeviceInfo a0) { target.onDeviceInfoChanged(a0); }
    @Override public void onDeviceVolumeChanged(int a0, boolean a1) { target.onDeviceVolumeChanged(a0, a1); }
    @Override public void onVideoSizeChanged(VideoSize a0) { target.onVideoSizeChanged(a0); }
    @Override public void onSurfaceSizeChanged(int a0, int a1) { target.onSurfaceSizeChanged(a0, a1); }
    @Override public void onRenderedFirstFrame() { target.onRenderedFirstFrame(); }
    @SuppressWarnings("deprecation")
    @Override public void onCues(List<Cue> a0) { target.onCues(a0); }
    @SuppressWarnings("deprecation")
    @Override public void onCues(CueGroup a0) { target.onCues(a0); }
    @Override public void onMetadata(Metadata a0) { target.onMetadata(a0); }
}
