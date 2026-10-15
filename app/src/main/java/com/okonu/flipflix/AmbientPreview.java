package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.TextureView;
import android.view.animation.DecelerateInterpolator;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

/** A single muted native preview. No iframe overlays, polling, or stale selection callbacks. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class AmbientPreview {
    static final long DELAY_MS = 1500;
    private final Context context;
    final TextureView view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable start = this::startSelected;
    private final Runnable timeout = this::stop;
    ExoPlayer player;
    DiscoveryItem selected;
    private boolean active = true, closed, firstFrame;
    private int generation;
    int startCount;
    private VideoSize videoSize;

    AmbientPreview(Context context, TextureView view) {
        this.context = context; this.view = view;
        view.setAlpha(0f); view.setOpaque(false);
        view.setFocusable(false); view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> crop());
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
    private void startSelected() {
        if (closed || !active || selected == null || selected.previewUrl.isEmpty() || reducedMotion()) return;
        final int token = generation;
        ExoPlayer p = new ExoPlayer.Builder(context).setLoadControl(new DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 10000, 500, 1000).build()).build();
        player = p; startCount++;
        p.setVolume(0f);
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().setMaxVideoSize(1280, 720).build());
        p.setVideoTextureView(view);
        p.addListener(new Player.Listener() {
            private boolean current() { return player == p && generation == token && active && !closed; }
            private void reveal() {
                if (!current() || !firstFrame || !p.isPlaying()) return;
                ui.removeCallbacks(timeout);
                view.animate().alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
            }
            @Override public void onRenderedFirstFrame() { if (current()) { firstFrame = true; reveal(); } }
            @Override public void onIsPlayingChanged(boolean playing) { if (playing) reveal(); }
            @Override public void onVideoSizeChanged(VideoSize size) { if (current()) { videoSize = size; crop(); } }
            @Override public void onPlayerError(PlaybackException error) { if (current()) stop(); }
            @Override public void onPlaybackStateChanged(int state) { if (current() && state == Player.STATE_ENDED) stop(); }
        });
        MediaItem.ClippingConfiguration clip = new MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(selected.previewStartMs).setEndPositionMs(selected.previewStartMs + 25000).build();
        p.setMediaItem(new MediaItem.Builder().setUri(selected.previewUrl).setClippingConfiguration(clip).build());
        p.prepare(); p.play();
        ui.postDelayed(timeout, 15000);
    }
    private void crop() {
        if (videoSize == null || videoSize.height == 0 || view.getWidth() == 0 || view.getHeight() == 0) return;
        float videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height;
        float boundsRatio = (float) view.getWidth() / view.getHeight();
        Matrix m = new Matrix();
        m.setScale(Math.max(1f, videoRatio / boundsRatio), Math.max(1f, boundsRatio / videoRatio), view.getWidth() / 2f, view.getHeight() / 2f);
        view.setTransform(m);
    }
    void stop() {
        generation++; firstFrame = false;
        ui.removeCallbacks(start); ui.removeCallbacks(timeout);
        view.animate().cancel(); view.setAlpha(0f);
        if (player != null) { ExoPlayer old = player; player = null; old.clearVideoTextureView(view); old.release(); }
    }
    void close() { closed = true; stop(); }
}
