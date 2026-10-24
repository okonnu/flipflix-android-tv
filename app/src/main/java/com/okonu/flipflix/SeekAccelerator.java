package com.okonu.flipflix;

/** One seek intent per burst, independent of slow/asynchronous player reports. */
final class SeekAccelerator {
    static final long RESET_MS = 900, MIN_INTERVAL_MS = 180;
    private long started, lastInput = -1, lastStep = -1, target;
    private int direction;
    private boolean awaitingPosition;
    long stepMillis;

    long seek(int direction, long now, long position, long duration) {
        boolean fresh = lastInput < 0 || now - lastInput >= RESET_MS;
        boolean newBurst = fresh || direction != this.direction;
        if (newBurst) started = now;
        syncPosition(now, position);
        this.direction = direction;
        lastInput = now;
        if (!newBurst && lastStep >= 0 && now - lastStep < MIN_INTERVAL_MS) return -1;
        long held = now - started;
        stepMillis = held >= 5000 ? 120000 : held >= 2500 ? 60000 : held >= 1000 ? 30000 : 10000;
        target = Math.max(0, Math.min(duration, target + direction * stepMillis));
        lastStep = now;
        awaitingPosition = true;
        return target;
    }

    private void syncPosition(long now, long reported) {
        long elapsed = Math.max(0, now - lastStep);
        // Rift progress can arrive five seconds apart. Ending acceleration must
        // not discard the last seek intent before the provider acknowledges it.
        if (awaitingPosition && (elapsed >= 6000 || (reported >= target - 2000 && reported <= target + elapsed + 2000)))
            awaitingPosition = false;
        if (!awaitingPosition) target = reported;
    }
    long displayPosition(long now, long reported) { syncPosition(now, reported); return target; }
    long target() { return target; }
    void reset() { lastInput = -1; lastStep = -1; direction = 0; awaitingPosition = false; stepMillis = 10000; }
}
