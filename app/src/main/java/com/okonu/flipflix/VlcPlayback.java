package com.okonu.flipflix;

import android.content.Context;
import android.net.Uri;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native LibVLC engine. No external VLC activity or provider-owned controls. */
final class VlcPlayback implements PlaybackBackend {
    final VLCVideoLayout view;
    private final LibVLC library;
    private final MediaPlayer player;
    private boolean ready, closed, ended, fill, paused, muted;
    private boolean started, initialSeekSent;
    private long pendingSeek = -1;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable seekTimeout = () -> finishSeek();
    private final long initialPosition;
    private final Runnable onReady;

    VlcPlayback(Context context, String url, String referer, String agent, JSONArray captions,
                long position, boolean paused, boolean muted, boolean fill, Runnable onReady, Runnable onError) {
        this.paused = paused; this.muted = muted; this.fill = fill;
        this.initialPosition = Math.max(0, position); this.onReady = onReady;
        library = new LibVLC(context, new ArrayList<>(Arrays.asList("--network-caching=1500", "--no-video-title-show")));
        player = new MediaPlayer(library);
        view = new VLCVideoLayout(context);
        view.setFocusable(false);
        view.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        player.attachViews(view, null, true, false);
        player.setVideoScale(fill ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN : MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        player.setEventListener(event -> {
            if (closed) return;
            if (event.type == MediaPlayer.Event.Playing) {
                started = true; completePreparation();
            } else if (event.type == MediaPlayer.Event.SeekableChanged || event.type == MediaPlayer.Event.TimeChanged
                    || event.type == MediaPlayer.Event.PositionChanged || event.type == MediaPlayer.Event.LengthChanged) {
                completePreparation();
            } else if (event.type == MediaPlayer.Event.EndReached) ended = true;
            else if (event.type == MediaPlayer.Event.EncounteredError) onError.run();
        });
        Uri uri = Uri.parse(url);
        if ("file".equals(uri.getScheme())) uri = Uri.fromFile(new java.io.File(uri.getPath()));
        Media media = new Media(library, uri);
        media.setHWDecoderEnabled(true, false); // Hardware first, software permitted.
        if (referer != null && !referer.isEmpty()) media.addOption(":http-referrer=" + referer);
        if (agent != null && !agent.isEmpty()) media.addOption(":http-user-agent=" + agent);
        player.setMedia(media);
        media.release();
        for (int i = 0; captions != null && i < captions.length(); i++) {
            JSONObject track = captions.optJSONObject(i);
            String path = track == null ? "" : NativePlayback.captionUrl(track.optString("url"), referer);
            if (!path.isEmpty()) player.addSlave(Media.Slave.Type.Subtitle, Uri.parse(path), false);
        }
    }

    private void completePreparation() {
        if (closed || !started) return;
        if (ready) {
            if (pendingSeek >= 0 && Math.abs(player.getTime() - pendingSeek) < 1000) {
                finishSeek();
            }
            return;
        }
        if (initialPosition > 0) {
            if (!player.isSeekable() || player.getLength() <= 0) return;
            long target = Math.min(initialPosition, Math.max(0, player.getLength() - 1000));
            if (!initialSeekSent) { initialSeekSent = true; player.setTime(target); return; }
            // Playing can precede the initial seek. Pause only after the native
            // clock acknowledges it; otherwise VLC can remain paused at zero.
            if (Math.abs(player.getTime() - target) > 1500) return;
        }
        ready = true;
        player.setVolume(muted ? 0 : 100);
        if (paused) player.pause();
        onReady.run();
    }
    private void finishSeek() {
        if (closed) return;
        pendingSeek = -1; handler.removeCallbacks(seekTimeout);
        if (paused) player.pause();
        player.setVolume(muted ? 0 : 100);
    }
    void start() {
        if (!closed) {
            // Do not inherit a previous LibVLC session's muted output. Only an
            // explicitly muted/paused preparation is silent; ready restores it.
            player.setVolume(paused || muted ? 0 : 100);
            player.play();
        }
    }
    void release() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        player.setEventListener(null);
        player.stop(); player.detachViews(); player.release(); library.release();
    }

    @Override public State state() {
        State s = new State();
        if (closed) return s;
        s.available = ready; s.canStart = !ready; s.ended = ended;
        s.position = Math.max(0, player.getTime()); s.duration = Math.max(0, player.getLength());
        s.seekable = ready && player.isSeekable(); s.paused = paused; s.muted = muted;
        s.canResize = true; s.fillScreen = fill;
        MediaPlayer.TrackDescription[] subs = player.getSpuTracks();
        s.subtitles.add(new Option("-1", "Off", player.getSpuTrack() == -1));
        if (subs != null) for (MediaPlayer.TrackDescription t : subs) if (t.id != -1)
            s.subtitles.add(new Option(String.valueOf(t.id), t.name, player.getSpuTrack() == t.id));
        List<Option> audio = new ArrayList<>();
        MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
        if (tracks != null) for (MediaPlayer.TrackDescription t : tracks) if (t.id != -1)
            audio.add(new Option(String.valueOf(t.id), t.name, player.getAudioTrack() == t.id));
        if (!audio.isEmpty()) s.settings.put("audio", audio);
        // LibVLC's adaptive bandwidth selection is automatic; do not invent quality choices.
        s.qualities.add(new Option("auto", "Auto (VLC)", true));
        return s;
    }

    @Override public void command(String name, String value) {
        if (closed) return;
        try {
            if (name.equals("play") || name.equals("pause") || name.equals("toggle")) {
                paused = name.equals("pause") || name.equals("toggle") && !paused;
                if (!paused) ended = false;
                if (ready) { if (paused) player.pause(); else player.play(); }
                else { player.setVolume(paused || muted ? 0 : 100); if (!paused) player.play(); }
            } else if (name.equals("mute")) { muted = !muted; player.setVolume(muted || !ready && paused ? 0 : 100); }
            else if (name.equals("seek") && player.isSeekable()) {
                ended = false;
                pendingSeek = Math.max(0, Math.min(Long.parseLong(value), Math.max(0, player.getLength() - 1000)));
                if (paused) player.setVolume(0);
                player.setTime(pendingSeek);
                // VLC's paused input can remain parked on the old/keyframe
                // clock. Decode the seek silently, then re-pause on its ACK.
                if (paused) player.play();
                handler.removeCallbacks(seekTimeout); handler.postDelayed(seekTimeout, 4000);
            }
            else if (name.equals("subtitle")) player.setSpuTrack(value.equals("off") ? -1 : Integer.parseInt(value));
            else if (name.equals("audio")) player.setAudioTrack(Integer.parseInt(value));
            else if (name.equals("resize") && (value.equals("fit") || value.equals("fill"))) {
                fill = value.equals("fill");
                player.setVideoScale(fill ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN : MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            }
        } catch (NumberFormatException ignored) { }
    }
}
