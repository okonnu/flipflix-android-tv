package com.okonu.flipflix;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/** One local, synchronized cold-start intro over the already-loading Home screen. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class StartupIntro {
    static final long FADE_START_MS = 7000, END_MS = 8000, WATCHDOG_MS = 12000;
    private final Context context;
    private final FrameLayout content;
    private final Runnable completed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = this::fadeOutOnFailure;
    private final int previousFocusability, previousAccessibility;
    private final View previousFocus;
    final FrameLayout overlay;
    final StartupLogo logo;
    ExoPlayer player;
    private boolean finished, fading;
    private int consumedKey = -1;
    private ValueAnimator failureFade;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!isActive() || fading || player == null) return;
            // One audio clock drives all vector layers and the final crossfade.
            // Decoder startup cannot shift the impact away from logo assembly.
            long position = player.getCurrentPosition();
            logo.render(position);
            overlay.setAlpha(opacityAt(position));
            overlay.postOnAnimation(this);
        }
    };

    // This is an input shield, not an actionable control; announcing a click/skip
    // action through performClick would be misleading.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    StartupIntro(Context context, FrameLayout host, FrameLayout content, Runnable completed) {
        this.context = context;
        this.content = content;
        this.completed = completed;
        previousFocus = content.findFocus();
        previousFocusability = content.getDescendantFocusability();
        previousAccessibility = content.getImportantForAccessibility();
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay = new FrameLayout(context);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setKeepScreenOn(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setContentDescription("Starting Darkflix");
        // Do not let touch input leak into Home, and do not provide a skip action.
        overlay.setOnTouchListener((v, event) -> true);
        logo = new StartupLogo(context);
        overlay.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.requestFocus();
    }

    static boolean motionEnabled(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    static float opacityAt(long positionMs) {
        float progress = Math.max(0f, Math.min(1f, (float) (positionMs - FADE_START_MS) / (END_MS - FADE_START_MS)));
        return (float) ((1.0 + Math.cos(Math.PI * progress)) / 2.0);
    }

    void start() {
        if (finished || player != null) return;
        ui.postDelayed(watchdog, WATCHDOG_MS);
        try {
            // A missing bundled resource cannot recover through network-style retries.
            player = new ExoPlayer.Builder(context).setMediaSourceFactory(new DefaultMediaSourceFactory(context)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0))).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
            player.setHandleAudioBecomingNoisy(true);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) close();
                }
                @Override public void onPlayerError(PlaybackException error) { fadeOutOnFailure(); }
                @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                    if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) fadeOutOnFailure();
                }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.darkflix_startup)));
            player.prepare();
            player.play();
            overlay.postOnAnimation(frame);
        } catch (RuntimeException unavailableDecoder) {
            // Cosmetic startup must never prevent browsing, even on older Fire TVs.
            fadeOutOnFailure();
        }
    }

    boolean isActive() { return !finished; }

    boolean handleKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == consumedKey) {
            if (event.getAction() == KeyEvent.ACTION_UP) consumedKey = -1;
            return true;
        }
        if (!isActive()) return false;
        switch (code) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (event.getAction() == KeyEvent.ACTION_DOWN) consumedKey = code;
                return true;
            default: return false; // Keep system volume and other system keys working.
        }
    }

    private void fadeOutOnFailure() {
        if (!isActive() || fading) return;
        fading = true;
        overlay.removeCallbacks(frame);
        failureFade = ValueAnimator.ofFloat(overlay.getAlpha(), 0f);
        failureFade.setDuration(220);
        failureFade.setInterpolator(new AccelerateDecelerateInterpolator());
        failureFade.addUpdateListener(animation -> {
            float alpha = (float) animation.getAnimatedValue();
            overlay.setAlpha(alpha);
            if (player != null) player.setVolume(alpha);
        });
        failureFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { close(); }
        });
        failureFade.start();
    }

    void close() {
        if (finished) return;
        finished = true;
        ui.removeCallbacks(watchdog);
        overlay.removeCallbacks(frame);
        if (failureFade != null) { failureFade.removeAllListeners(); failureFade.cancel(); failureFade = null; }
        if (player != null) { ExoPlayer old = player; player = null; old.release(); }
        logo.release();
        overlay.setAlpha(0f);
        if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
        content.setDescendantFocusability(previousFocusability);
        content.setImportantForAccessibility(previousAccessibility);
        if (previousFocus == null || !previousFocus.isAttachedToWindow() || !previousFocus.requestFocus()) content.requestFocus();
        completed.run();
    }
}
