package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import org.json.JSONArray;

/** One native playback session, backed directly by LibVLC. */
final class NativePlayback implements PlaybackBackend {
    final FrameLayout view;
    interface Listener { void ready(); void failed(); void engineChanged(String engine); }
    private final Context context;
    private final String url, referer, agent;
    private final JSONArray captions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::fail;
    private VlcPlayback vlc;
    private Listener listener;
    private boolean closed, failed, started, notified;

    NativePlayback(Context context, String url, String referer, JSONArray captions) {
        this(context, url, referer, "Darkflix/0.1.28", captions);
    }
    NativePlayback(Context context, String url, String referer, String agent, JSONArray captions) {
        this.context = context; this.url = url; this.referer = referer; this.agent = agent; this.captions = captions;
        view = new FrameLayout(context);
        view.setBackgroundColor(Color.BLACK);
        view.setFocusableInTouchMode(true);
        view.setDescendantFocusability(FrameLayout.FOCUS_BLOCK_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
    }

    static String captionUrl(String path, String referer) {
        if (path == null || path.isEmpty()) return "";
        try {
            String resolved = new java.net.URI(referer == null ? "" : referer).resolve(path).toString();
            return resolved.startsWith("https://") ? resolved : "";
        } catch (Exception ignored) { return ""; }
    }
    void setListener(Listener listener) { this.listener = listener; }
    String engine() { return "VLC"; }
    void start() { start(0); }
    void start(long position) {
        if (closed || failed || started) return;
        started = true;
        try {
            vlc = new VlcPlayback(context, url, referer, agent, captions, Math.max(0, position),
                false, false, false, this::ready, this::fail);
            view.addView(vlc.view, new FrameLayout.LayoutParams(-1, -1));
            handler.postDelayed(timeout, 25000);
            vlc.start();
        } catch (RuntimeException | LinkageError error) { fail(); }
    }
    private void ready() {
        handler.removeCallbacks(timeout);
        if (!notified && !closed && !failed) {
            notified = true;
            if (listener != null) listener.ready();
        }
    }
    private void fail() {
        if (closed || failed) return;
        failed = true;
        handler.removeCallbacksAndMessages(null);
        if (vlc != null) vlc.release();
        if (listener != null) listener.failed();
    }
    void reject() { fail(); }
    void release() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (vlc != null) { vlc.release(); view.removeView(vlc.view); }
    }
    @Override public State state() {
        return closed || failed || vlc == null ? new State() : vlc.state();
    }
    @Override public void command(String name, String value) {
        if (!closed && !failed && vlc != null) vlc.command(name, value);
    }
}
