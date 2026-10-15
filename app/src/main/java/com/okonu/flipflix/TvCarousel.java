package com.okonu.flipflix;

import android.content.Context;
import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;

/** Four whole tiles per TV page. Remote movement never leaves an edge tile half cut. */
final class TvCarousel extends TvHorizontalScrollView {
    static final int COLUMNS = 4;
    private int sizedWidth, sizedCount;
    TvCarousel(Context context) {
        super(context);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (getChildCount() > 0 && getChildAt(0) instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) getChildAt(0);
            // CENTER_VERTICAL with a bottom margin lifts the tile into the row's clipped padding.
            row.setGravity(Gravity.TOP);
            row.setClipToPadding(false);
            int count = row.getChildCount();
            if (width > 0 && count > 0 && (width != sizedWidth || count != sizedCount)) {
                sizedWidth = width; sizedCount = count;
                int gap = dp(10), inset = dp(8);
                int tileWidth = Math.max(1, (width - 2 * inset - (COLUMNS - 1) * gap) / COLUMNS);
                for (int i = 0; i < count; i++) {
                    View tile = row.getChildAt(i);
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) tile.getLayoutParams();
                    p.width = tileWidth;
                    p.height = Math.round((tileWidth - dp(6)) * 9f / 16f) + dp(6);
                    p.rightMargin = i == count - 1 ? 0 : gap;
                    tile.setLayoutParams(p);
                }
                // Complete the final page with empty slots instead of exposing clipped prior tiles.
                int emptySlots = (COLUMNS - count % COLUMNS) % COLUMNS;
                row.setPadding(inset, dp(6), inset + emptySlots * (tileWidth + gap), dp(6));
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected boolean revealFocus(View focused, boolean animate) {
        if (focused == null || getChildCount() == 0) return false;
        LinearLayout row = (LinearLayout) getChildAt(0);
        while (focused.getParent() instanceof View && focused.getParent() != row) focused = (View) focused.getParent();
        int index = row.indexOfChild(focused);
        if (index < 0) return false;
        View first = row.getChildAt(index / COLUMNS * COLUMNS);
        return moveTo(Math.max(0, first.getLeft() - row.getPaddingLeft()), animate);
    }
}
