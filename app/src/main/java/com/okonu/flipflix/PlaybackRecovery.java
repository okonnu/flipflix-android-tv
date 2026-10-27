package com.okonu.flipflix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** Provider-independent resume coordinator over PlaybackBackend adapters and one content-keyed repository.
 * Samples already-received Java state; never scans a page or requests extra web state. */
final class PlaybackRecovery implements AutoCloseable {
    interface Notice { void show(String message); }
    private final WatchHistory store;
    private final String key;
    private final JSONObject catalog;
    private final int season, episode, source;
    private final PlaybackControls.Backend backend;
    private final Notice notice;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long target, started, lastAttempt;
    private int attempts;
    private boolean active, closed, unresolved, playAfterRestore;
    private long lastSeen = -1;
    PlaybackRecovery(WatchHistory store, String key, JSONObject catalog, int season, int episode, int source,
                     long target, PlaybackControls.Backend backend, Notice notice) {
        this.store = store; this.key = key; this.catalog = catalog; this.season = season; this.episode = episode;
        this.source = source; this.target = target; this.backend = backend; this.notice = notice;
        started = SystemClock.uptimeMillis(); unresolved = target >= 0;
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || closed) return;
            sample(SystemClock.uptimeMillis());
            if (active && !closed) handler.postDelayed(this, unresolved ? 1000 : 5000);
        }
    };
    void resume() { if (closed) return; active = true; handler.removeCallbacks(tick); tick.run(); }
    void pause() { active = false; handler.removeCallbacks(tick); save(); }
    long restartPosition() {
        if (unresolved) return target;
        PlaybackBackend b = backend.get(); PlaybackBackend.State state = b == null ? null : b.state();
        if (validProgress(state)) return Math.min(state.position, state.duration);
        // A buffering/error/ad clock is not a new movie position. Keep the last
        // confirmed content checkpoint even when the outgoing adapter is gone.
        if (lastSeen >= 0) return lastSeen;
        WatchHistory.Entry saved = store.get(key);
        return saved != null && saved.resumePosition() > 0 ? saved.resumePosition() : -1;
    }
    long handoff() { long position = restartPosition(); close(); return position; }
    void startOver() {
        if (closed) return;
        store.startOver(key);
        target = 0; unresolved = true; playAfterRestore = true; lastSeen = -1;
        attempts = 0; lastAttempt = 0; started = SystemClock.uptimeMillis();
        resume();
    }
    private boolean validProgress(PlaybackBackend.State state) {
        return state != null && state.available && state.duration >= 60000 && state.position > 0;
    }
    void sample(long now) {
        PlaybackBackend b = backend.get(); if (closed || b == null) return;
        PlaybackBackend.State state = b.state();
        if (unresolved) {
            if (state.available && state.seekable && state.duration >= 60000 && target < state.duration - 1000) {
                long requested = Math.min(target, Math.max(0, state.duration - 1000));
                // Require a command first, then an acknowledged position. Never save loading zeroes over a bookmark.
                if (attempts > 0 && Math.abs(state.position - requested) <= 6000) {
                    unresolved = false; target = -1;
                    if (playAfterRestore) { playAfterRestore = false; b.command("play", ""); }
                } else if (attempts < 3 && (attempts == 0 || now - lastAttempt >= 4000)) {
                    // Slow provider startup must not consume the seek/acknowledgement window.
                    if (attempts == 0) started = now;
                    b.command("seek", String.valueOf(requested));
                    lastAttempt = now; attempts++;
                }
            }
            if (unresolved && attempts > 0 && (now - started > 45000 || (attempts >= 3 && now - lastAttempt > 8000))) {
                notice.show("This source could not restore your position. Your saved progress is kept; try Rift or another source.");
                // Preserve the bookmark for this failed attempt; changing sources retries the same target.
                active = false; handler.removeCallbacks(tick); return;
            }
        }
        if (!unresolved) save();
    }
    void save() {
        if (closed || unresolved) return;
        PlaybackBackend b = backend.get(); if (b == null) return;
        PlaybackBackend.State state = b.state();
        if (!validProgress(state) || state.position == lastSeen) return;
        store.save(key, catalog, season, episode, source, state); lastSeen = Math.min(state.position, state.duration);
    }
    @Override public void close() { if (closed) return; pause(); closed = true; }
}
