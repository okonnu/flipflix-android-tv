package com.okonu.flipflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** TV adaptation of the reference player: timeline above a compact icon row. */
final class PlaybackControls extends LinearLayout {
    interface Backend { PlaybackBackend get(); }
    static final long HIDE_DELAY_MS = 5000;
    private final Activity activity;
    private final Backend backend;
    private final Runnable focusVideo;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ImageButton> buttons = new ArrayList<>();
    private final SeekBar timeline;
    private final TextView time, hint;
    private final ImageButton play, mute, source, resize;
    private boolean active, menuOpen, scrubbing;
    private int wakeKey = -1, lastButton;
    private final Runnable hide = this::hideControls;
    private void hideControls() {
        if (active && !menuOpen) {
            setVisibility(GONE);
            focusVideo.run();
        }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || getVisibility() != VISIBLE) return;
            PlaybackBackend b = backend.get();
            if (b != null) b.refresh();
            render();
            handler.postDelayed(this, 1000);
        }
    };

    PlaybackControls(Activity activity, Backend backend, Runnable focusVideo, String title,
                     Runnable previous, Runnable next, Runnable sourceAction, Runnable retry, Runnable exit) {
        super(activity);
        this.activity = activity; this.backend = backend; this.focusVideo = focusVideo;
        hint = label("Pause", 13);
        setOrientation(VERTICAL);
        setPadding(dp(28), dp(38), dp(28), dp(14));
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00070a11, 0xc8070a11, 0xf0070a11}));
        LinearLayout progress = row();
        addView(progress, new LayoutParams(-1, dp(40)));
        timeline = new SeekBar(activity);
        timeline.setContentDescription("Playback position. Left or right seeks 10 seconds.");
        timeline.setMax(10000);
        timeline.setProgressTintList(ColorStateList.valueOf(AppStyle.ACCENT));
        timeline.setProgressBackgroundTintList(ColorStateList.valueOf(AppStyle.BORDER));
        timeline.setThumbTintList(ColorStateList.valueOf(AppStyle.ACCENT));
        timeline.setFocusableInTouchMode(true);
        timeline.setPadding(dp(8), 0, dp(8), 0);
        timeline.setOnFocusChangeListener((v, focused) -> {
            timeline.setBackground(focused ? shape(0x30ffffff, Color.WHITE) : null);
            if (focused) hint.setText("Left / right: seek 10 seconds   |   Down: playback controls");
        });
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) time.setText(format(state().duration * value / 10000) + " / " + format(state().duration));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; handler.removeCallbacks(hide); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                send("seek", String.valueOf(state().duration * bar.getProgress() / 10000));
                interact();
            }
        });
        progress.addView(timeline, new LayoutParams(0, -1, 1));
        time = label("0:00 / 0:00", 13);
        time.setGravity(Gravity.CENTER);
        progress.addView(time, new LayoutParams(dp(125), -1));
        LinearLayout actions = row();
        addView(actions, new LayoutParams(-1, dp(52)));
        play = icon(actions, "Pause", "pause", () -> send("toggle", ""));
        icon(actions, "Back 10 seconds", "rewind", () -> seekBy(-10000));
        icon(actions, "Forward 10 seconds", "forward", () -> seekBy(10000));
        mute = icon(actions, "Mute", "volume", () -> send("mute", ""));
        TextView name = label(title, 15);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(16), 0, dp(16), 0);
        actions.addView(name, new LayoutParams(0, -1, 1));
        if (previous != null) icon(actions, "Previous episode", "previous", previous);
        if (next != null) icon(actions, "Next episode", "next", next);
        icon(actions, "Subtitles", "subtitles", () -> options(false));
        icon(actions, "Video quality", "quality", () -> options(true));
        source = icon(actions, "Choose source", "source", sourceAction);
        icon(actions, "Retry playback", "retry", retry);
        resize = icon(actions, "Fill screen (crop edges)", "fill", this::toggleResize);
        icon(actions, "Close player", "back", exit);
        hint.setTextColor(AppStyle.MUTED);
        hint.setPadding(dp(8), dp(7), 0, 0);
        addView(hint, new LayoutParams(-1, dp(28)));
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView label(String value, int size) {
        TextView v = new TextView(activity); v.setText(value); v.setTextColor(Color.WHITE);
        v.setTextSize(size); v.setGravity(Gravity.CENTER_VERTICAL);
        v.setTypeface(AppStyle.MEDIUM); return v;
    }
    private GradientDrawable shape(int fill, int border) {
        return AppStyle.shape(activity, fill, border);
    }
    private ImageButton icon(LinearLayout row, String label, String glyph, Runnable action) {
        ImageButton b = new ImageButton(activity);
        b.setContentDescription(label); b.setFocusableInTouchMode(true);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setImageDrawable(new ControlIcon(glyph, Color.WHITE));
        b.setBackground(shape(Color.TRANSPARENT, Color.TRANSPARENT));
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackground(shape(focused ? Color.WHITE : Color.TRANSPARENT, Color.TRANSPARENT));
            ((ControlIcon) b.getDrawable()).color(focused ? AppStyle.BG : Color.WHITE);
            if (focused) { lastButton = buttons.indexOf(b); if (hint != null) hint.setText(b.getContentDescription()); }
        });
        b.setOnClickListener(v -> { interact(); action.run(); render(); });
        LayoutParams p = new LayoutParams(dp(46), dp(46)); p.rightMargin = dp(3);
        row.addView(b, p); buttons.add(b); return b;
    }
    void sourceName(String name) { source.setContentDescription("Source: " + name); }
    void activate() { active = true; show(true); }
    void deactivate() { active = false; handler.removeCallbacksAndMessages(null); setVisibility(GONE); }
    void suspendForMenu() { menuOpen = true; handler.removeCallbacks(hide); }
    void resumeFromMenu() { menuOpen = false; if (active) show(false); }
    void show(boolean focus) {
        if (!active) return;
        setVisibility(VISIBLE); bringToFront();
        if (focus) buttons.get(Math.min(lastButton, buttons.size() - 1)).requestFocus();
        interact(); handler.removeCallbacks(tick); tick.run();
    }
    private void interact() {
        handler.removeCallbacks(hide);
        if (active && !menuOpen && !scrubbing) handler.postDelayed(hide, HIDE_DELAY_MS);
    }
    private PlaybackBackend.State state() {
        PlaybackBackend b = backend.get(); return b == null ? new PlaybackBackend.State() : b.state();
    }
    private void send(String command, String value) {
        PlaybackBackend b = backend.get();
        PlaybackBackend.State s = b == null ? new PlaybackBackend.State() : b.state();
        boolean startup = s.canStart && (command.equals("play") || command.equals("toggle") || command.equals("pause"));
        if (b == null || (!s.available && !startup)) {
            Toast.makeText(activity, "Waiting for the source's video controls. Try another source if playback does not start.", Toast.LENGTH_SHORT).show(); return;
        }
        b.command(command, value); render();
    }
    private void seekBy(long offset) {
        PlaybackBackend.State s = state();
        if (!s.seekable) { Toast.makeText(activity, "This stream is not seekable yet.", Toast.LENGTH_SHORT).show(); return; }
        send("seek", String.valueOf(Math.max(0, Math.min(s.duration > 0 ? s.duration : Long.MAX_VALUE, s.position + offset))));
    }
    private void toggleResize() {
        PlaybackBackend.State s = state();
        if (s.available && !s.canResize) {
            Toast.makeText(activity, "This source does not expose video sizing. Try another source.", Toast.LENGTH_SHORT).show();
            return;
        }
        send("resize", s.fillScreen ? "fit" : "fill");
    }
    private void render() {
        PlaybackBackend.State s = state();
        if (!scrubbing) {
            timeline.setProgress(s.duration > 0 ? (int) Math.min(10000, s.position * 10000 / s.duration) : 0);
            time.setText(format(s.position) + " / " + (s.duration > 0 ? format(s.duration) : "--:--"));
        }
        setStateIcon(play, s.paused ? "Play" : "Pause", s.paused ? "play" : "pause");
        setStateIcon(mute, s.muted ? "Unmute" : "Mute", s.muted ? "muted" : "volume");
        setStateIcon(resize, s.fillScreen ? "Fit picture (show all)" : "Fill screen (crop edges)", s.fillScreen ? "fit" : "fill");
    }
    private void setStateIcon(ImageButton button, String label, String glyph) {
        if (label.contentEquals(button.getContentDescription())) return;
        button.setContentDescription(label); button.setImageDrawable(new ControlIcon(glyph, button.hasFocus() ? AppStyle.BG : Color.WHITE));
        if (button.hasFocus()) hint.setText(label);
    }
    private static String format(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
    private void options(boolean quality) {
        PlaybackBackend.State s = state();
        List<PlaybackBackend.Option> choices = quality ? s.qualities : s.subtitles;
        suspendForMenu();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(quality ? "Video quality" : "Subtitles");
        if (choices.size() <= 1) {
            builder.setMessage(quality ? "This source does not expose selectable video qualities. Playback uses the source's default quality."
                : "This source has not supplied selectable subtitles for this video.").setPositiveButton("OK", null);
        } else {
            String[] labels = new String[choices.size()]; int checked = -1;
            for (int i = 0; i < choices.size(); i++) { labels[i] = choices.get(i).label; if (choices.get(i).selected) checked = i; }
            builder.setSingleChoiceItems(labels, checked, (dialog, index) -> {
                send(quality ? "quality" : "subtitle", choices.get(index).id); dialog.dismiss();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> resumeFromMenu());
        dialog.show();
        AppStyle.dialog(dialog, 460, -2);
    }

    boolean handleKey(KeyEvent event) {
        if (!active || menuOpen) return false;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean direction = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (code == wakeKey) { if (!down) wakeKey = -1; return true; }
        if (getVisibility() != VISIBLE && down && (direction || select)) {
            wakeKey = code; show(true); return true;
        }
        if (direction || select) interact();
        if (direction) {
            if (!down) return true;
            if (timeline.hasFocus()) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) seekBy(-10000);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) seekBy(10000);
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) buttons.get(lastButton).requestFocus();
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) timeline.requestFocus();
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) { handler.removeCallbacks(hide); hide.run(); }
            else {
                int index = buttons.indexOf(activity.getCurrentFocus());
                if (index < 0) index = lastButton;
                index = Math.max(0, Math.min(buttons.size() - 1, index + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                buttons.get(index).requestFocus();
            }
            return true;
        }
        String command = null;
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) command = "toggle";
        else if (code == KeyEvent.KEYCODE_MEDIA_PLAY) command = "play";
        else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) command = "pause";
        else if (code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MUTE) command = "mute";
        if (command != null || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (down) {
                show(false);
                if (command != null) send(command, "");
                else seekBy(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 10000 : -10000);
            }
            return true;
        }
        return select && timeline.hasFocus();
    }
}
