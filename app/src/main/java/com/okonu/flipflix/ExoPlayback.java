package com.okonu.flipflix;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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

/** Native Media3 strategy. Uses the complete input manifest, including audio and text. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class ExoPlayback implements PlaybackBackend {
    final ExoPlayer player;
    private final PlayerView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean closed, notified;
    private long tracksRevision;
    private List<androidx.media3.common.TrackGroup> trackGroups = Collections.emptyList();

    ExoPlayback(Context context, String url, String referer, String agent, JSONArray captions,
                long position, boolean paused, boolean muted, boolean fill, Runnable onReady, Runnable onError) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(agent).setConnectTimeoutMs(12000).setReadTimeoutMs(12000)
            .setDefaultRequestProperties(referer == null || referer.isEmpty()
                ? Collections.emptyMap() : Collections.singletonMap("Referer", referer));
        player = new ExoPlayer.Builder(context)
            .setRenderersFactory(new DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, http))).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        view = (PlayerView) android.view.LayoutInflater.from(context).inflate(R.layout.native_player, null, false);
        view.setUseController(false);
        view.setFocusable(false);
        view.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        view.setResizeMode(fill ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setPlayer(player);
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (int i = 0; captions != null && i < captions.length(); i++) {
            JSONObject track = captions.optJSONObject(i);
            if (track == null) continue;
            String path = NativePlayback.captionUrl(track.optString("url"), referer);
            if (path.isEmpty()) continue;
            String mime = Uri.parse(path).getPath();
            subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(path))
                .setMimeType(mime != null && mime.endsWith(".srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT)
                .setLanguage(track.optString("lang")).setLabel(track.optString("label", "Subtitles"))
                .setId("caption-" + i).build());
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.setVolume(muted ? 0f : 1f);
        player.setMediaItem(new MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitles).build());
        player.seekTo(Math.max(0, position));
        player.setPlayWhenReady(!paused);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (!closed && !notified && state == Player.STATE_READY) {
                    notified = true; onReady.run();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (!closed) onError.run();
            }
            @Override public void onTracksChanged(Tracks tracks) {
                List<androidx.media3.common.TrackGroup> current = new ArrayList<>();
                for (Tracks.Group group : tracks.getGroups()) current.add(group.getMediaTrackGroup());
                // Adaptive selection changes must not invalidate an open menu;
                // replacement/reordered track groups must invalidate its IDs.
                if (!current.equals(trackGroups)) { tracksRevision++; trackGroups = current; }
                // Unsupported audio need not cause a fatal Exo error. Do not
                // report silent video as a successfully supported movie.
                boolean audio = false, supportedAudio = false;
                for (Tracks.Group group : tracks.getGroups()) if (group.getType() == C.TRACK_TYPE_AUDIO) {
                    audio = true;
                    for (int i = 0; i < group.length; i++) supportedAudio |= group.isTrackSupported(i);
                }
                if (audio && !supportedAudio) handler.post(() -> { if (!closed) onError.run(); });
            }
        });
    }

    View view() { return view; }
    void start() { if (!closed) player.prepare(); }
    void release() {
        if (closed) return;
        closed = true; handler.removeCallbacksAndMessages(null);
        view.setPlayer(null); player.release();
    }
    @Override public State state() {
        State s = new State();
        if (closed) return s;
        s.optionsOwner = this;
        s.available = player.getPlayerError() == null && (player.getPlaybackState() == Player.STATE_READY
            || player.getPlaybackState() == Player.STATE_ENDED);
        s.canStart = !s.available;
        s.ended = player.getPlaybackState() == Player.STATE_ENDED;
        s.seekable = player.isCurrentMediaItemSeekable();
        s.paused = !player.getPlayWhenReady(); s.muted = player.getVolume() == 0;
        s.canResize = true; s.fillScreen = view.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        s.position = Math.max(0, player.getCurrentPosition()); s.duration = Math.max(0, player.getDuration());
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        boolean textSelected = false, auto = true;
        for (Tracks.Group group : groups) if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) textSelected = true;
        for (TrackSelectionOverride override : player.getTrackSelectionParameters().overrides.values())
            if (override.getType() == C.TRACK_TYPE_VIDEO) auto = false;
        s.subtitles.add(new Option("off", "Off", !textSelected));
        List<Option> videos = new ArrayList<>(), audio = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            for (int t = 0; t < group.length; t++) {
                if (!group.isTrackSupported(t)) continue;
                Format f = group.getTrackFormat(t);
                String id = tracksRevision + ":" + g + ":" + t;
                if (group.getType() == C.TRACK_TYPE_VIDEO) {
                    String label = f.height > 0 ? f.height + "p" : "Video " + (videos.size() + 1);
                    if (f.bitrate > 0) label += String.format(java.util.Locale.US, " (%.1f Mbps)", f.bitrate / 1000000f);
                    videos.add(new Option(id, label, !auto && group.isTrackSelected(t)));
                } else if (group.getType() == C.TRACK_TYPE_AUDIO) {
                    audio.add(new Option(id, trackLabel(f, "Audio", audio.size() + 1), group.isTrackSelected(t)));
                } else if (group.getType() == C.TRACK_TYPE_TEXT) {
                    s.subtitles.add(new Option(id, trackLabel(f, "Subtitles", s.subtitles.size()), group.isTrackSelected(t)));
                }
            }
        }
        if (videos.size() > 1) {
            s.qualities.add(new Option("auto", "Auto", auto)); s.qualities.addAll(videos);
        } else {
            if (!videos.isEmpty()) s.qualities.add(new Option("auto", videos.get(0).label, true));
            s.qualityMessage = videos.isEmpty() ? "Video qualities are not available yet."
                : "This stream provides one supported video quality: " + videos.get(0).label + ".";
        }
        if (!audio.isEmpty()) s.settings.put("audio", audio);
        return s;
    }
    private static String trackLabel(Format format, String fallback, int index) {
        if (format.label != null && !format.label.isEmpty()) return format.label;
        if (format.language != null && !format.language.isEmpty() && !format.language.equals("und")) {
            String language = java.util.Locale.forLanguageTag(format.language).getDisplayLanguage();
            return language.isEmpty() ? format.language : language;
        }
        return fallback + " " + index;
    }
    @Override public void command(String name, String value) {
        if (closed) return;
        try {
            if (name.equals("play")) player.play();
            else if (name.equals("pause")) player.pause();
            else if (name.equals("toggle")) player.setPlayWhenReady(!player.getPlayWhenReady());
            else if (name.equals("mute")) player.setVolume(player.getVolume() == 0 ? 1f : 0f);
            else if (name.equals("seek") && player.isCurrentMediaItemSeekable())
                player.seekTo(Math.max(0, Math.min(Long.parseLong(value), Math.max(0, player.getDuration() - 1000))));
            else if (name.equals("resize") && (value.equals("fit") || value.equals("fill")))
                view.setResizeMode(value.equals("fill") ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            else if (name.equals("quality") || name.equals("audio") || name.equals("subtitle")) {
                int type = name.equals("quality") ? C.TRACK_TYPE_VIDEO : name.equals("audio") ? C.TRACK_TYPE_AUDIO : C.TRACK_TYPE_TEXT;
                androidx.media3.common.TrackSelectionParameters.Builder params = player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(type).setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && value.equals("off"));
                if (!(name.equals("subtitle") && value.equals("off")) && !(name.equals("quality") && value.equals("auto"))) {
                    String[] parts = value.split(":");
                    if (parts.length != 3 || Long.parseLong(parts[0]) != tracksRevision) return;
                    int g = Integer.parseInt(parts[1]), t = Integer.parseInt(parts[2]);
                    List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
                    if (g < 0 || t < 0 || g >= groups.size() || t >= groups.get(g).length
                            || groups.get(g).getType() != type || !groups.get(g).isTrackSupported(t)) return;
                    params.setOverrideForType(new TrackSelectionOverride(groups.get(g).getMediaTrackGroup(), t));
                }
                player.setTrackSelectionParameters(params.build());
            }
        } catch (NumberFormatException ignored) { }
    }
}
