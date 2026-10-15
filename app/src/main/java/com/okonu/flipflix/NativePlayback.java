package com.okonu.flipflix;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
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

    NativePlayback(Context context, String url, String referer, JSONArray captions) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent("Flipflix/0.1.8")
            .setDefaultRequestProperties(Collections.singletonMap("Referer", referer));
        player = new ExoPlayer.Builder(context)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, http))).build();
        view = new PlayerView(context);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setUseController(false);
        view.setPlayer(player);
        view.setFocusableInTouchMode(true);
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        for (int i = 0; captions != null && i < captions.length(); i++) {
            JSONObject track = captions.optJSONObject(i);
            if (track == null) continue;
            String path = track.optString("url");
            if (path.startsWith("/")) path = "https://embed.vidrift.in" + path;
            if (!path.startsWith("https://")) continue;
            subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(path))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage(track.optString("lang"))
                .setLabel(track.optString("label", "Subtitles")).setId(track.optString("code")).build());
        }
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        player.setMediaItem(new MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitles).build());
    }

    void start() { player.prepare(); player.play(); }
    void release() { view.setPlayer(null); player.release(); }

    @Override public State state() {
        State s = new State();
        s.available = player.getPlaybackState() != Player.STATE_IDLE;
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
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = group.getTrackFormat(i);
                String id = g + ":" + i;
                if (group.getType() == C.TRACK_TYPE_TEXT) {
                    String label = f.label != null ? f.label : f.language != null ? f.language : "Subtitles " + (i + 1);
                    s.subtitles.add(new Option(id, label, group.isTrackSelected(i)));
                } else if (group.getType() == C.TRACK_TYPE_VIDEO) {
                    String label = f.height > 0 ? f.height + "p" : "Source quality";
                    s.qualities.add(new Option(id, label, !s.qualities.get(0).selected && group.isTrackSelected(i)));
                }
            }
        }
        return s;
    }

    @Override public void command(String name, String value) {
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
        } else if (name.equals("subtitle") || name.equals("quality")) {
            int type = name.equals("subtitle") ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_VIDEO;
            androidx.media3.common.TrackSelectionParameters.Builder params = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(type).setTrackTypeDisabled(type, value.equals("off"));
            if (!value.equals("off") && !value.equals("auto")) {
                String[] parts = value.split(":");
                int g = Integer.parseInt(parts[0]), t = Integer.parseInt(parts[1]);
                List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
                if (g >= groups.size() || t >= groups.get(g).length || groups.get(g).getType() != type) return;
                params.setOverrideForType(new TrackSelectionOverride(groups.get(g).getMediaTrackGroup(), t));
            }
            player.setTrackSelectionParameters(params.build());
        }
    }
}
