package com.okonu.flipflix;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** One frame-synced motion per axis. New focus replaces, rather than queues, a destination. */
final class TvScrollMotion {
    private final View view;
    private final boolean horizontal;
    private ValueAnimator animator;
    private int destination;

    TvScrollMotion(View view, boolean horizontal) { this.view = view; this.horizontal = horizontal; }
    private int position() { return horizontal ? view.getScrollX() : view.getScrollY(); }
    int target() { return isRunning() ? destination : position(); }
    boolean isRunning() { return animator != null && animator.isRunning(); }

    boolean moveTo(int target, boolean animate) {
        if (animate && isRunning() && destination == target) return false;
        int from = position();
        cancel();
        destination = target;
        if (from == target) return false;
        if (!animate || !view.isAttachedToWindow()) { apply(target); return true; }
        int viewport = horizontal ? view.getWidth() : view.getHeight();
        // Small reveals are brisk; a whole page gets a little more settling time.
        long duration = 220 + Math.round(100f * Math.min(1f, Math.abs(target - from) / (float) Math.max(1, viewport)));
        animator = ValueAnimator.ofInt(from, target);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(value -> apply((Integer) value.getAnimatedValue()));
        // ValueAnimator follows the device's animation-duration/accessibility setting.
        animator.start();
        return true;
    }
    private void apply(int value) {
        if (horizontal) view.scrollTo(value, view.getScrollY());
        else view.scrollTo(view.getScrollX(), value);
    }
    void cancel() {
        if (animator != null) { animator.cancel(); animator.removeAllUpdateListeners(); animator = null; }
    }
    static int reveal(int start, int end, int viewport, int currentTarget) {
        if (end - start > viewport || start < currentTarget) return start;
        if (end > currentTarget + viewport) return end - viewport;
        return currentTarget;
    }
}
