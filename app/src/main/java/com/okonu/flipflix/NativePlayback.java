package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import org.json.JSONArray;

/** Native adapter for the shared controls. ExoPlayer owns decoding and tracks;
 * this module bounds preparation and prevents released sessions from restarting. */
final class NativePlayback implements PlaybackBackend {
    final FrameLayout view;
    interface Listener { void ready(); void failed(); }
    private final Context context;
    private final String url, referer, agent;
    private final JSONArray captions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayback strategy;
    private int lease;
    private Runnable timeout;
    private Listener listener;
    private boolean closed, failed, started, notified;

    NativePlayback(Context context, String url, String referer, JSONArray captions) {
        this(context, url, referer, "Darkflix/0.1.32", captions);
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
    String engine() { return "ExoPlayer"; }
    void start() { start(0); }
    void start(long position) {
        if (closed || failed || started) return;
        started = true;
        State initial = new State(); initial.paused = false;
        initial.position = Math.max(0, position);
        openEngine(initial);
    }
    private void openEngine(State transport) {
        int token = ++lease;
        notified = false;
        if (timeout != null) handler.removeCallbacks(timeout);
        if (strategy != null) { strategy.release(); strategy = null; view.removeAllViews(); }
        try {
            Runnable ready = () -> { if (current(token)) ready(); };
            Runnable error = () -> handler.post(() -> { if (current(token)) fail(); });
            strategy = new ExoPlayback(context, url, referer, agent, captions, transport.position,
                transport.paused, transport.muted, transport.fillScreen, ready, error);
            view.addView(strategy.view(), new FrameLayout.LayoutParams(-1, -1));
            timeout = () -> { if (current(token)) fail(); };
            handler.postDelayed(timeout, 25000); strategy.start();
        } catch (RuntimeException | LinkageError error) {
            handler.post(() -> { if (current(token)) fail(); });
        }
    }
    private boolean current(int token) { return !closed && !failed && token == lease; }
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
        if (strategy != null) strategy.release();
        if (listener != null) listener.failed();
    }
    void reject() { fail(); }
    void release() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (strategy != null) { strategy.release(); strategy = null; view.removeAllViews(); }
    }
    @Override public State state() {
        if (closed || failed) return new State();
        return strategy == null ? new State() : strategy.state();
    }
    @Override public void command(String name, String value) {
        if (closed || failed) return;
        if (strategy != null) strategy.command(name, value);
    }
}
