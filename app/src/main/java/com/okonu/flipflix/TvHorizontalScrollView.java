package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

/** Focus reveal without HorizontalScrollView's immediate requestChildFocus jump. */
class TvHorizontalScrollView extends HorizontalScrollView {
    final TvScrollMotion motion = new TvScrollMotion(this, true);
    private final Rect bounds = new Rect();

    TvHorizontalScrollView(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        // Keep native focus bookkeeping, but let our single animation own reveal.
        return 0;
    }
    @Override public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (!isLayoutRequested()) revealFocus(focused, true);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean animate = isLaidOut();
        super.onLayout(changed, l, t, r, b);
        revealFocus(findFocus(), animate);
    }
    @Override public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        if (isLayoutRequested()) return false;
        return revealFocus(findFocus(), !immediate);
    }
    protected boolean revealFocus(View focused, boolean animate) {
        if (focused == null || focused == this || focused.getWidth() == 0) return false;
        focused.getDrawingRect(bounds);
        offsetDescendantRectToMyCoords(focused, bounds);
        int inset = Math.round(8 * getResources().getDisplayMetrics().density);
        return moveTo(TvScrollMotion.reveal(bounds.left - inset, bounds.right + inset, getWidth(), motion.target()), animate);
    }
    protected boolean moveTo(int x, boolean animate) {
        int max = getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getWidth() - getWidth() + getPaddingLeft() + getPaddingRight());
        return motion.moveTo(Math.max(0, Math.min(x, max)), animate);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) motion.cancel();
        return super.dispatchTouchEvent(event);
    }
    @Override protected void onDetachedFromWindow() { motion.cancel(); super.onDetachedFromWindow(); }
}
