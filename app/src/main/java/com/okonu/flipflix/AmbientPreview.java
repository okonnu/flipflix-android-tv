package com.okonu.flipflix;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.ArrayList;
import java.util.Arrays;

/** A single muted VLC preview. No iframe overlays, polling, or stale callbacks. */
final class AmbientPreview {
    static final long DELAY_MS = 1500;
    private final Context context;
    final FrameLayout view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable start = this::startSelected;
    private final Runnable timeout = this::stop;
    MediaPlayer player;
    private LibVLC library;
    private AssetFileDescriptor asset;
    DiscoveryItem selected;
    private boolean active = true, closed, firstFrame;
    private int generation;
    int startCount;

    AmbientPreview(Context context, FrameLayout view) {
        this.context = context; this.view = view;
        view.setAlpha(0f); view.setClipChildren(true);
        view.setFocusable(false); view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    boolean reducedMotion() {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }
    void select(DiscoveryItem item) {
        if (selected == item) return;
        stop(); selected = item; schedule();
    }
    void setActive(boolean value) {
        if (active == value) return;
        active = value; stop(); if (value) schedule();
    }
    void restartDelay() { stop(); schedule(); }
    private void schedule() {
        if (!closed && active && selected != null && !selected.previewUrl.isEmpty() && !reducedMotion())
            ui.postDelayed(start, DELAY_MS);
    }
    private boolean current(MediaPlayer p, int token) {
        return player == p && generation == token && active && !closed;
    }
    private void startSelected() {
        if (closed || !active || selected == null || selected.previewUrl.isEmpty() || reducedMotion()) return;
        final int token = generation;
        startCount++;
        try {
            library = new LibVLC(context, new ArrayList<>(Arrays.asList("--network-caching=1500", "--no-video-title-show")));
            MediaPlayer p = new MediaPlayer(library);
            player = p;
            p.setVolume(0);
            // A released TextureView's surface must not be handed to a new VLC
            // player. Keep the crossfade host, but give each session a fresh view.
            VLCVideoLayout video = new VLCVideoLayout(context);
            view.addView(video, new FrameLayout.LayoutParams(-1, -1));
            // TextureView is deliberate for crossfading under the browsing UI.
            // Fullscreen movies use the more efficient SurfaceView path.
            p.attachViews(video, null, false, true);
            p.setVideoScale(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN);
            p.setEventListener(event -> {
                if (!current(p, token)) return;
                if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached) { stop(); return; }
                if (firstFrame || !p.isPlaying()) return;
                // Vout/Playing alone can precede rendering. Reveal only after VLC
                // has actually presented a frame; this is event-driven, not a poll.
                IMedia media = p.getMedia();
                if (media == null) return;
                boolean rendered;
                try { IMedia.Stats stats = media.getStats(); rendered = stats != null && stats.displayedPictures > 0; }
                finally { media.release(); }
                if (!rendered) return;
                firstFrame = true;
                ui.removeCallbacks(timeout);
                view.animate().alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
            });
            Media media;
            if (selected.previewUrl.startsWith("asset:///")) {
                asset = context.getAssets().openFd(selected.previewUrl.substring(9));
                media = new Media(library, asset);
            } else media = new Media(library, Uri.parse(selected.previewUrl));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":start-time=" + selected.previewStartMs / 1000.0);
            media.addOption(":stop-time=" + (selected.previewStartMs + 25000) / 1000.0);
            p.setMedia(media); media.release();
            ui.postDelayed(timeout, 15000);
            p.play();
        } catch (java.io.IOException | RuntimeException | LinkageError unavailable) { stop(); }
    }
    void stop() {
        generation++; firstFrame = false;
        ui.removeCallbacks(start); ui.removeCallbacks(timeout);
        view.animate().cancel(); view.setAlpha(0f);
        if (player != null) {
            MediaPlayer old = player; player = null;
            old.setEventListener(null); old.stop(); old.detachViews(); old.release();
        }
        if (library != null) { library.release(); library = null; }
        view.removeAllViews();
        if (asset != null) { try { asset.close(); } catch (java.io.IOException ignored) { } asset = null; }
    }
    void close() { closed = true; stop(); }
}
