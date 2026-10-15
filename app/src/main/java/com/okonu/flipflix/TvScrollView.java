package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** Smooth vertical focus movement; a Home heading and its cards stay together. */
final class TvScrollView extends ScrollView {
    final TvScrollMotion motion = new TvScrollMotion(this, false);
    private final List<View[]> groups = new ArrayList<>();
    private final Rect bounds = new Rect(), endBounds = new Rect();

    TvScrollView(Context context) {
        super(context);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }
    void addFocusGroup(View heading, View body) { groups.add(new View[]{heading, body}); }
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
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
        View focused = findFocus();
        if (focusGroup(focused)) return revealBounds(!immediate);
        bounds.set(rect);
        bounds.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
        return revealBounds(!immediate);
    }
    private boolean revealFocus(View focused, boolean animate) {
        if (focused == null || focused == this || focused.getHeight() == 0) return false;
        if (!focusGroup(focused)) {
            focused.getDrawingRect(bounds);
            offsetDescendantRectToMyCoords(focused, bounds);
        }
        return revealBounds(animate);
    }
    private boolean focusGroup(View focused) {
        if (focused == null) return false;
        for (View[] group : groups) {
            if (focused != group[0] && !inside(focused, group[1])) continue;
            group[0].getDrawingRect(bounds); offsetDescendantRectToMyCoords(group[0], bounds);
            group[1].getDrawingRect(endBounds); offsetDescendantRectToMyCoords(group[1], endBounds);
            bounds.union(endBounds);
            return bounds.height() <= getHeight();
        }
        return false;
    }
    private boolean inside(View child, View parent) {
        for (View v = child; v != this; ) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return false;
    }
    private boolean revealBounds(boolean animate) {
        if (getChildCount() == 0 || getHeight() == 0) return false;
        int inset = Math.round(16 * getResources().getDisplayMetrics().density);
        int y = TvScrollMotion.reveal(bounds.top - inset, bounds.bottom + inset, getHeight(), motion.target());
        int max = Math.max(0, getChildAt(0).getHeight() - getHeight() + getPaddingTop() + getPaddingBottom());
        return motion.moveTo(Math.max(0, Math.min(y, max)), animate);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) motion.cancel();
        return super.dispatchTouchEvent(event);
    }
    @Override protected void onDetachedFromWindow() { motion.cancel(); super.onDetachedFromWindow(); }
}
