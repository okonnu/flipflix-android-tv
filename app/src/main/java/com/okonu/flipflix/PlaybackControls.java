package com.okonu.flipflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private TextView restart;
    private final ImageButton play, mute, source, resize, rewind, forward;
    private final SeekAccelerator seeking = new SeekAccelerator();
    private AlertDialog settingsDialog;
    private boolean active, menuOpen, scrubbing;
    private int wakeKey = -1, lastButton;
    private final Runnable hide = this::hideControls;
    private void hideControls() {
        if (active && !menuOpen) {
            seeking.reset();
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
            new int[]{0x00000000, 0xb8000000, 0xef000000}));
        LinearLayout progress = row();
        addView(progress, new LayoutParams(-1, dp(40)));
        timeline = new SeekBar(activity);
        timeline.setContentDescription("Playback position. Hold left or right to seek faster.");
        timeline.setMax(10000);
        timeline.setProgressTintList(ColorStateList.valueOf(AppStyle.ACCENT));
        timeline.setProgressBackgroundTintList(ColorStateList.valueOf(AppStyle.BORDER));
        timeline.setThumbTintList(ColorStateList.valueOf(AppStyle.ACCENT));
        timeline.setFocusableInTouchMode(true);
        timeline.setBackground(null);
        if (android.os.Build.VERSION.SDK_INT >= 26) timeline.setDefaultFocusHighlightEnabled(false);
        timeline.setSplitTrack(false);
        styleTimeline(false);
        timeline.setPadding(dp(8), 0, dp(8), 0);
        timeline.setOnFocusChangeListener((v, focused) -> {
            styleTimeline(focused);
            if (focused) hint.setText("Left / right: seek. Keep pressing to speed up. Down: controls");
        });
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) time.setText(format(state().duration * value / 10000) + " / " + format(state().duration));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking.reset(); scrubbing = true; handler.removeCallbacks(hide); }
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
        rewind = icon(actions, "Back 10 seconds", "rewind", () -> seekBy(-1));
        forward = icon(actions, "Forward 10 seconds", "forward", () -> seekBy(1));
        mute = icon(actions, "Mute", "volume", () -> send("mute", ""));
        TextView name = label(title, 15);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(16), 0, dp(16), 0);
        actions.addView(name, new LayoutParams(0, -1, 1));
        if (previous != null) icon(actions, "Previous episode", "previous", previous);
        if (next != null) icon(actions, "Next episode", "next", next);
        icon(actions, "Subtitles", "subtitles", () -> options(false));
        icon(actions, "Video quality", "quality", () -> options(true));
        icon(actions, "Playback settings", "settings", this::settings);
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
    private void styleTimeline(boolean focused) {
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL); thumb.setColor(AppStyle.ACCENT);
        thumb.setSize(dp(focused ? 14 : 8), dp(focused ? 14 : 8));
        timeline.setThumb(thumb);
    }
    private ImageButton icon(LinearLayout row, String label, String glyph, Runnable action) {
        ImageButton b = new ImageButton(activity);
        b.setContentDescription(label); b.setFocusableInTouchMode(true);
        b.setStateListAnimator(null);
        if (android.os.Build.VERSION.SDK_INT >= 26) b.setDefaultFocusHighlightEnabled(false);
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
    void sourceName(String name) {
        seeking.reset(); source.setContentDescription("Source: " + name);
        if (source.hasFocus()) hint.setText(source.getContentDescription());
    }
    void offerRestart(Runnable action) {
        if (restart != null) return;
        restart = label("Start from beginning", 14);
        restart.setFocusableInTouchMode(true);
        restart.setPadding(dp(14), 0, dp(14), 0);
        restart.setContentDescription("Start from beginning");
        ControlIcon glyph = new ControlIcon("retry", Color.WHITE); glyph.setBounds(0, 0, dp(18), dp(18));
        restart.setCompoundDrawables(glyph, null, null, null); restart.setCompoundDrawablePadding(dp(8));
        restart.setBackground(shape(AppStyle.CONTROL, Color.TRANSPARENT));
        if (android.os.Build.VERSION.SDK_INT >= 26) restart.setDefaultFocusHighlightEnabled(false);
        restart.setOnFocusChangeListener((view, focused) -> {
            int color = focused ? Color.BLACK : Color.WHITE;
            restart.setTextColor(color); glyph.color(color);
            restart.setBackground(shape(focused ? Color.WHITE : AppStyle.CONTROL, Color.TRANSPARENT));
            if (focused) hint.setText("Start this title from the beginning");
        });
        restart.setOnClickListener(view -> {
            seeking.reset(); action.run(); restart.setVisibility(GONE);
            buttons.get(0).requestFocus(); interact();
        });
        LayoutParams p = new LayoutParams(-2, dp(38)); p.gravity = Gravity.END; p.bottomMargin = dp(8);
        addView(restart, 0, p);
    }
    void activate() { active = true; show(true); }
    void deactivate() {
        active = false; seeking.reset(); handler.removeCallbacksAndMessages(null); setVisibility(GONE);
        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
    }
    void suspendForMenu() { seeking.reset(); menuOpen = true; handler.removeCallbacks(hide); }
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
        boolean startup = (s.providerControls && command.equals("player")) || (s.canStart && (command.equals("play") || command.equals("toggle") || command.equals("pause")));
        if (b == null || (!s.available && !startup)) {
            Toast.makeText(activity, "Playback is not ready. Use Retry or Source if it does not start.", Toast.LENGTH_SHORT).show(); return;
        }
        b.command(command, value); render();
    }
    private void seekBy(int direction) {
        PlaybackBackend.State s = state();
        if (!s.available || !s.seekable) { Toast.makeText(activity, "This stream is not seekable yet.", Toast.LENGTH_SHORT).show(); return; }
        long target = seeking.seek(direction, SystemClock.uptimeMillis(), s.position, s.duration);
        if (target < 0) return;
        send("seek", String.valueOf(target));
        hint.setText((direction < 0 ? "Back " : "Forward ") + seeking.stepMillis / 1000 + "s - release to slow down");
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
            long position = seeking.displayPosition(SystemClock.uptimeMillis(), s.position);
            timeline.setProgress(s.duration > 0 ? (int) Math.min(10000, position * 10000 / s.duration) : 0);
            time.setText(format(position) + " / " + (s.duration > 0 ? format(s.duration) : "--:--"));
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
                sendOption(quality ? "quality" : "subtitle", choices.get(index)); dialog.dismiss();
            });
        }
        AlertDialog dialog = builder.create();
        settingsDialog = dialog;
        dialog.setOnDismissListener(d -> resumeFromMenu());
        dialog.show();
        AppStyle.dialog(dialog, 460, -2);
    }

    private static String settingLabel(String key) {
        switch (key) {
            case "audio": return "Audio language / dubbing";
            case "server": return "Rift server";
            case "source": return "Rift source / language version";
            case "speed": return "Playback speed";
            case "volume": return "Player volume";
            case "subtitleOffset": return "Subtitle timing (+ means later)";
            case "subtitleSize": return "Subtitle size";
            case "subtitlePosition": return "Subtitle vertical position";
            case "subtitleColor": return "Subtitle color";
            case "subtitleBackground": return "Subtitle background";
            case "subtitleFont": return "Subtitle font";
            case "cast": return "Cast (requires compatible device)";
            default: return "Use Rift player controls";
        }
    }
    private void settings() {
        List<String> keys = new ArrayList<>(state().settings.keySet());
        if (state().providerControls) keys.add("player");
        suspendForMenu();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("Playback settings");
        if (keys.isEmpty()) builder.setMessage("This source has not exposed additional settings. Rift supplies its available options when ready.").setPositiveButton("OK", null);
        else {
            String[] labels = new String[keys.size()];
            for (int i = 0; i < keys.size(); i++) labels[i] = settingLabel(keys.get(i));
            builder.setItems(labels, (dialog, index) -> {
                String key = keys.get(index); dialog.dismiss();
                if (key.equals("player") || key.equals("cast")) send(key, "");
                else settingChoices(key);
            });
        }
        showSettingsDialog(builder.create());
    }
    private void settingChoices(String key) {
        List<PlaybackBackend.Option> current = state().settings.get(key);
        List<PlaybackBackend.Option> choices = current == null ? new ArrayList<>() : new ArrayList<>(current);
        suspendForMenu();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(settingLabel(key));
        if (choices.isEmpty()) builder.setMessage("No choices are available on the current source.").setPositiveButton("OK", null);
        else {
            String[] labels = new String[choices.size()]; int checked = -1;
            for (int i = 0; i < labels.length; i++) { labels[i] = choices.get(i).label; if (choices.get(i).selected) checked = i; }
            builder.setSingleChoiceItems(labels, checked, (dialog, index) -> { sendOption(key, choices.get(index)); dialog.dismiss(); });
        }
        showSettingsDialog(builder.create());
    }
    private void sendOption(String key, PlaybackBackend.Option selected) {
        PlaybackBackend.State current = state();
        List<PlaybackBackend.Option> choices = key.equals("quality") ? current.qualities : key.equals("subtitle") ? current.subtitles : current.settings.get(key);
        if (choices != null) for (PlaybackBackend.Option choice : choices) {
            // A failover may reuse track ID "0" for a different language/quality.
            if (choice.id.equals(selected.id) && choice.label.equals(selected.label)) { send(key, selected.id); return; }
        }
        Toast.makeText(activity, "The source changed these options. Reopen the menu for the current choices.", Toast.LENGTH_SHORT).show();
    }
    private void showSettingsDialog(AlertDialog dialog) {
        settingsDialog = dialog;
        dialog.setOnDismissListener(d -> { if (settingsDialog == dialog) { settingsDialog = null; resumeFromMenu(); } });
        dialog.show(); AppStyle.dialog(dialog, 500, -2);
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
        if (select && (rewind.hasFocus() || forward.hasFocus())) {
            if (down) seekBy(rewind.hasFocus() ? -1 : 1);
            return true;
        }
        if (direction) {
            if (!down) return true;
            if (restart != null && restart.hasFocus()) {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) timeline.requestFocus();
            } else if (timeline.hasFocus()) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) seekBy(-1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) seekBy(1);
                else if (code == KeyEvent.KEYCODE_DPAD_UP && restart != null && restart.getVisibility() == VISIBLE) restart.requestFocus();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) { seeking.reset(); buttons.get(lastButton).requestFocus(); }
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) { seeking.reset(); timeline.requestFocus(); }
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) { handler.removeCallbacks(hide); hide.run(); }
            else {
                seeking.reset();
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
                if (command != null) { seeking.reset(); if (event.getRepeatCount() == 0) send(command, ""); }
                else seekBy(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 1 : -1);
            }
            return true;
        }
        return select && timeline.hasFocus();
    }
}
