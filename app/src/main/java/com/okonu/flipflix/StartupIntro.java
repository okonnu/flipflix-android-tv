package com.okonu.flipflix;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
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
    private AudioManager audio;
    private boolean focusHeld, receiverRegistered;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change < 0) ui.post(this::fadeOutOnFailure);
    };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { fadeOutOnFailure(); }
    };
    private boolean finished, fading;
    private int consumedKey = -1;
    private ValueAnimator failureFade;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!isActive() || fading || player == null) return;
            // The audio clock drives vectors and crossfade, keeping assembly
            // synchronized with the impact even when decoder startup is delayed.
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
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            focusHeld = audio != null && audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focusHeld) { fadeOutOnFailure(); return; }
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(noisy, filter);
            receiverRegistered = true;
            player = new ExoPlayer.Builder(context).setMediaSourceFactory(new DefaultMediaSourceFactory(context)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0))).build();
            player.setVolume(1f);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) close();
                }
                @Override public void onPlayerError(PlaybackException error) { fadeOutOnFailure(); }
            });
            player.setMediaItem(MediaItem.fromUri("android.resource://" + context.getPackageName() + "/" + R.raw.darkflix_startup));
            player.prepare();
            player.play();
            overlay.postOnAnimation(frame);
        } catch (RuntimeException | LinkageError unavailableDecoder) {
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
        if (receiverRegistered) { context.unregisterReceiver(noisy); receiverRegistered = false; }
        if (focusHeld && audio != null) { audio.abandonAudioFocus(focus); focusHeld = false; }
        logo.release();
        overlay.setAlpha(0f);
        if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
        content.setDescendantFocusability(previousFocusability);
        content.setImportantForAccessibility(previousAccessibility);
        if (previousFocus == null || !previousFocus.isAttachedToWindow() || !previousFocus.requestFocus()) content.requestFocus();
        completed.run();
    }
}
