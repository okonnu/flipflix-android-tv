package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.TextureView;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

/** A single muted native preview. No iframe overlays, polling, or stale callbacks. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class AmbientPreview {
    static final long DELAY_MS = 1500;
    private final Context context;
    final FrameLayout view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable start = this::startSelected;
    private final Runnable timeout = this::stop;
    ExoPlayer player;
    private TextureView surface;
    private VideoSize videoSize;
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
    private boolean current(ExoPlayer p, int token) {
        return player == p && generation == token && active && !closed;
    }
    private void startSelected() {
        if (closed || !active || selected == null || selected.previewUrl.isEmpty() || reducedMotion()) return;
        final int token = generation;
        startCount++;
        try {
            ExoPlayer p = new ExoPlayer.Builder(context).setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 10000, 500, 1000).build()).build();
            player = p;
            p.setVolume(0f);
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon().setMaxVideoSize(1280, 720).build());
            // TextureView supports the crossfade beneath the browsing UI.
            surface = new TextureView(context);
            surface.setOpaque(false);
            surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> crop());
            view.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            p.setVideoTextureView(surface);
            p.addListener(new Player.Listener() {
                private void reveal() {
                    if (!current(p, token) || !firstFrame || !p.isPlaying()) return;
                    ui.removeCallbacks(timeout);
                    view.animate().alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
                }
                @Override public void onRenderedFirstFrame() { if (current(p, token)) { firstFrame = true; reveal(); } }
                @Override public void onIsPlayingChanged(boolean playing) { if (playing) reveal(); }
                @Override public void onVideoSizeChanged(VideoSize size) { if (current(p, token)) { videoSize = size; crop(); } }
                @Override public void onPlayerError(PlaybackException error) { if (current(p, token)) stop(); }
                @Override public void onPlaybackStateChanged(int state) { if (current(p, token) && state == Player.STATE_ENDED) stop(); }
            });
            MediaItem.ClippingConfiguration clip = new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(selected.previewStartMs).setEndPositionMs(selected.previewStartMs + 25000).build();
            p.setMediaItem(new MediaItem.Builder().setUri(selected.previewUrl).setClippingConfiguration(clip).build());
            ui.postDelayed(timeout, 15000);
            p.prepare(); p.play();
        } catch (RuntimeException | LinkageError unavailable) { stop(); }
    }
    private void crop() {
        if (surface == null || videoSize == null || videoSize.height == 0 || surface.getWidth() == 0 || surface.getHeight() == 0) return;
        float videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height;
        float boundsRatio = (float) surface.getWidth() / surface.getHeight();
        Matrix matrix = new Matrix();
        matrix.setScale(Math.max(1f, videoRatio / boundsRatio), Math.max(1f, boundsRatio / videoRatio),
            surface.getWidth() / 2f, surface.getHeight() / 2f);
        surface.setTransform(matrix);
    }
    void stop() {
        generation++; firstFrame = false;
        ui.removeCallbacks(start); ui.removeCallbacks(timeout);
        view.animate().cancel(); view.setAlpha(0f);
        if (player != null) {
            ExoPlayer old = player; player = null;
            old.clearVideoTextureView(surface); old.release();
        }
        view.removeAllViews();
        surface = null; videoSize = null;
    }
    void close() { closed = true; stop(); }
}
