package com.okonu.flipflix;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class NativePlayback implements PlaybackBackend {
    final ExoPlayer player;
    final PlayerView view;
    interface Listener { void ready(); void failed(); void engineChanged(String engine); }
    private final Context context;
    private final String url, referer, agent;
    private final JSONArray captions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VlcPlayback vlc;
    private Listener listener;
    private boolean closed, exoReleased, failed, notified;
    private long initialPosition;
    private final Runnable timeout = this::fallback;
    private final Runnable vlcTimeout = this::fail;

    NativePlayback(Context context, String url, String referer, JSONArray captions) {
        this(context, url, referer, "Darkflix/0.1.27", captions);
    }
    NativePlayback(Context context, String url, String referer, String agent, JSONArray captions) {
        this.context = context; this.url = url; this.referer = referer; this.agent = agent; this.captions = captions;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(agent).setConnectTimeoutMs(12000).setReadTimeoutMs(12000)
            .setDefaultRequestProperties(referer == null || referer.isEmpty() ? Collections.emptyMap() : Collections.singletonMap("Referer", referer));
        player = new ExoPlayer.Builder(context)
            .setRenderersFactory(new DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, http))).build();
        view = new PlayerView(context);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setUseController(false);
        view.setPlayer(player);
        view.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (int i = 0; captions != null && i < captions.length(); i++) {
            JSONObject track = captions.optJSONObject(i);
            if (track == null) continue;
            String path = captionUrl(track.optString("url"), referer);
            if (path.isEmpty()) continue;
            subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(path))
                .setMimeType(path.split("\\?")[0].endsWith(".srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT).setLanguage(track.optString("lang"))
                .setLabel(track.optString("label", "Subtitles")).setId(track.optString("code")).build());
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.setMediaItem(new MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitles).build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (!closed && !exoReleased && state == Player.STATE_READY) ready();
            }
            @Override public void onPlayerError(PlaybackException error) { fallback(); }
            @Override public void onTracksChanged(Tracks tracks) {
                // Exo can play silently when all audio tracks are unsupported without
                // raising a fatal player error. Prefer native software decoding then.
                boolean hasAudio = false, supported = false;
                for (Tracks.Group group : tracks.getGroups()) if (group.getType() == C.TRACK_TYPE_AUDIO) {
                    hasAudio = true;
                    for (int i = 0; i < group.length; i++) supported |= group.isTrackSupported(i);
                }
                if (hasAudio && !supported) handler.post(NativePlayback.this::fallback);
            }
        });
    }

    static String captionUrl(String path, String referer) {
        if (path == null || path.isEmpty()) return "";
        try {
            String resolved = new java.net.URI(referer == null ? "" : referer).resolve(path).toString();
            return resolved.startsWith("https://") ? resolved : "";
        } catch (Exception ignored) { return ""; }
    }
    void setListener(Listener listener) { this.listener = listener; }
    String engine() { return vlc == null ? "ExoPlayer" : "VLC"; }
    void start() { start(0); }
    void start(long position) {
        initialPosition = Math.max(0, position);
        player.seekTo(initialPosition); player.prepare(); player.play();
        handler.postDelayed(timeout, 20000);
    }
    private void ready() {
        handler.removeCallbacks(timeout); handler.removeCallbacks(vlcTimeout);
        if (!notified && !closed && !failed) { notified = true; if (listener != null) listener.ready(); }
    }
    void fallback() {
        if (closed || failed || vlc != null || exoReleased) return;
        State previous = state();
        long position = player.getDuration() > 0 ? Math.max(0, player.getCurrentPosition()) : initialPosition;
        view.setPlayer(null); player.release(); exoReleased = true;
        handler.removeCallbacks(timeout); notified = false;
        try {
            vlc = new VlcPlayback(context, url, referer, agent, captions, position, previous.paused,
                previous.muted, previous.fillScreen, this::ready, this::fail);
            view.addView(vlc.view, new FrameLayout.LayoutParams(-1, -1));
            if (listener != null) listener.engineChanged("VLC");
            handler.postDelayed(vlcTimeout, 25000); vlc.start();
        } catch (RuntimeException | LinkageError error) { fail(); }
    }
    private void fail() {
        if (closed || failed) return;
        failed = true; handler.removeCallbacksAndMessages(null);
        if (!exoReleased) player.pause();
        if (vlc != null) vlc.release();
        if (listener != null) listener.failed();
    }
    void reject() { fail(); }
    void release() {
        if (closed) return;
        closed = true; handler.removeCallbacksAndMessages(null); view.setPlayer(null);
        if (!exoReleased) player.release();
        if (vlc != null) { vlc.release(); view.removeView(vlc.view); }
    }

    @Override public State state() {
        if (closed || failed) return new State();
        if (vlc != null) return vlc.state();
        State s = new State();
        s.available = player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_ENDED;
        s.canStart = !s.available;
        s.ended = player.getPlaybackState() == Player.STATE_ENDED;
        s.seekable = player.isCurrentMediaItemSeekable();
        s.paused = !player.getPlayWhenReady();
        s.muted = player.getVolume() == 0;
        s.canResize = true;
        s.fillScreen = view.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        s.position = player.getCurrentPosition();
        s.duration = Math.max(0, player.getDuration());
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        boolean textSelected = false;
        for (Tracks.Group group : groups) if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) textSelected = true;
        s.subtitles.add(new Option("off", "Off", !textSelected));
        boolean autoQuality = true;
        for (TrackSelectionOverride override : player.getTrackSelectionParameters().overrides.values()) {
            if (override.getType() == C.TRACK_TYPE_VIDEO) autoQuality = false;
        }
        s.qualities.add(new Option("auto", "Auto", autoQuality));
        List<Option> audio = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                String id = g + ":" + i;
                if (group.getType() == C.TRACK_TYPE_TEXT) {
                    String label = f.label != null ? f.label : f.language != null ? f.language : "Subtitles " + (i + 1);
                    s.subtitles.add(new Option(id, label, group.isTrackSelected(i)));
                } else if (group.getType() == C.TRACK_TYPE_AUDIO) {
                    String label = f.label != null ? f.label : f.language != null ? f.language : "Audio " + (i + 1);
                    audio.add(new Option(id, label, group.isTrackSelected(i)));
                } else if (group.getType() == C.TRACK_TYPE_VIDEO) {
                    String label = f.height > 0 ? f.height + "p" : "Source quality";
                    s.qualities.add(new Option(id, label, !s.qualities.get(0).selected && group.isTrackSelected(i)));
                }
            }
        }
        if (!audio.isEmpty()) s.settings.put("audio", audio);
        return s;
    }

    @Override public void command(String name, String value) {
        if (closed || failed) return;
        if (vlc != null) { vlc.command(name, value); return; }
        try {
        if (name.equals("play")) player.play();
        else if (name.equals("pause")) player.pause();
        else if (name.equals("toggle")) player.setPlayWhenReady(!player.getPlayWhenReady());
        else if (name.equals("mute")) player.setVolume(player.getVolume() == 0 ? 1 : 0);
        else if (name.equals("resize") && (value.equals("fill") || value.equals("fit"))) {
            // ZOOM preserves the movie's shape and crops overflow; FILL would stretch it.
            view.setResizeMode(value.equals("fill") ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }
        else if (name.equals("seek") && player.isCurrentMediaItemSeekable()) {
            player.seekTo(Math.max(0, Math.min(Long.parseLong(value), Math.max(0, player.getDuration()))));
        } else if (name.equals("subtitle") || name.equals("quality") || name.equals("audio")) {
            int type = name.equals("subtitle") ? C.TRACK_TYPE_TEXT : name.equals("audio") ? C.TRACK_TYPE_AUDIO : C.TRACK_TYPE_VIDEO;
            androidx.media3.common.TrackSelectionParameters.Builder params = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(type).setTrackTypeDisabled(type, value.equals("off"));
            if (!value.equals("off") && !value.equals("auto")) {
                String[] parts = value.split(":");
                if (parts.length != 2) return;
                int g = Integer.parseInt(parts[0]), t = Integer.parseInt(parts[1]);
                List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
                if (g < 0 || t < 0 || g >= groups.size() || t >= groups.get(g).length || groups.get(g).getType() != type) return;
                params.setOverrideForType(new TrackSelectionOverride(groups.get(g).getMediaTrackGroup(), t));
            }
            player.setTrackSelectionParameters(params.build());
        }
        } catch (NumberFormatException ignored) { }
    }
}
