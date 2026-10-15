package com.okonu.flipflix;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real frame-by-frame scroll positions, not just a final visibility assertion. */
@RunWith(AndroidJUnit4.class)
public final class TvScrollingTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private TvScrollView vertical;
    private FrameLayout root;
    private final List<TvCarousel> rows = new ArrayList<>();
    private final List<TextView> headings = new ArrayList<>();
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private int dp(int n) { return Math.round(n * rule.getActivity().getResources().getDisplayMetrics().density); }
    private View tile(int row, int index) { return ((LinearLayout) rows.get(row).getChildAt(0)).getChildAt(index); }
    private void focus(View target) {
        assertTrue(target.requestFocus());
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), false);
    }
    @Before public void setup() {
        main(() -> {
            try {
                Field generation = MainActivity.class.getDeclaredField("generation"); generation.setAccessible(true);
                generation.set(rule.getActivity(), 999999);
            } catch (Exception e) { throw new AssertionError(e); }
            root = new FrameLayout(rule.getActivity()); rule.getActivity().setContentView(root);
            vertical = new TvScrollView(rule.getActivity());
            root.addView(vertical, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout content = new LinearLayout(rule.getActivity()); content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(36), dp(16), dp(36), dp(34)); vertical.addView(content);
            for (int row = 0; row < 6; row++) {
                TextView heading = new TextView(rule.getActivity()); heading.setText("Row " + row);
                heading.setFocusableInTouchMode(true); headings.add(heading);
                content.addView(heading, new LinearLayout.LayoutParams(-1, dp(36)));
                TvCarousel carousel = new TvCarousel(rule.getActivity()); rows.add(carousel);
                LinearLayout cards = new LinearLayout(rule.getActivity()); carousel.addView(cards);
                for (int index = 0; index < 10; index++) addTile(cards);
                content.addView(carousel, new LinearLayout.LayoutParams(-1, -2));
                vertical.addFocusGroup(heading, carousel);
                View gap = new View(rule.getActivity()); content.addView(gap, new LinearLayout.LayoutParams(1, dp(16)));
            }
        });
        instrumentation().waitForIdleSync();
        main(() -> focus(tile(0, 0)));
        settle();
    }
    private void addTile(LinearLayout cards) {
        View tile = new View(rule.getActivity()); tile.setFocusableInTouchMode(true);
        cards.addView(tile, new LinearLayout.LayoutParams(dp(180), dp(110)));
    }
    private interface Condition { boolean met(); }
    private void await(String message, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 5000; boolean[] met = {false};
        while (!met[0] && SystemClock.uptimeMillis() < deadline) {
            main(() -> met[0] = condition.met());
            if (!met[0]) SystemClock.sleep(8);
        }
        assertTrue(message, met[0]);
    }
    private void settle() {
        await("Scrolls finish without a backlog", () -> {
            if (vertical.motion.isRunning() || vertical.isLayoutRequested()) return false;
            for (TvCarousel row : rows) if (row.motion.isRunning() || row.isLayoutRequested()) return false;
            return true;
        });
    }
    private void whole(View view) {
        Rect rect = new Rect(); assertTrue(view.getGlobalVisibleRect(rect));
        assertEquals(view.getWidth(), rect.width()); assertEquals(view.getHeight(), rect.height());
    }

    @Test public void horizontalMovementHasIntermediateFramesAndWholeCardsAtRest() {
        TvCarousel carousel = rows.get(0);
        List<Integer> positions = new ArrayList<>();
        main(() -> {
            carousel.getViewTreeObserver().addOnScrollChangedListener(() -> positions.add(carousel.getScrollX()));
            focus(tile(0, 4));
            assertEquals("Focus must not jump to the destination before animating", 0, carousel.getScrollX());
            assertTrue(carousel.motion.isRunning());
        });
        await("Horizontal scroll has an intermediate frame", () -> carousel.getScrollX() > 0 && carousel.getScrollX() < carousel.motion.target());
        settle();
        main(() -> {
            for (int index = 4; index < 8; index++) whole(tile(0, index));
            assertFalse(tile(0, 3).getGlobalVisibleRect(new Rect()));
            assertTrue("Several frames, not a delayed snap", positions.size() >= 3);
            for (int i = 1; i < positions.size(); i++) assertTrue("No backwards jump", positions.get(i) >= positions.get(i - 1));
            focus(tile(0, 9));
        });
        settle();
        main(() -> { whole(tile(0, 8)); whole(tile(0, 9)); });
    }

    @Test public void fastDirectionChangesRetargetFromCurrentPosition() {
        TvCarousel carousel = rows.get(0);
        main(() -> focus(tile(0, 4)));
        await("Animation starts", () -> carousel.getScrollX() > 0 && carousel.motion.isRunning());
        main(() -> {
            int current = carousel.getScrollX();
            focus(tile(0, 8));
            assertEquals("Rapid forward focus must not snap", current, carousel.getScrollX());
            focus(tile(0, 0));
            assertEquals("Reversing starts where the previous frame stopped", current, carousel.getScrollX());
            assertEquals(0, carousel.motion.target());
            assertSame(tile(0, 0), rule.getActivity().getCurrentFocus());
        });
        settle();
        main(() -> { assertEquals(0, carousel.getScrollX()); whole(tile(0, 0)); });
    }

    @Test public void verticalMovementKeepsHeadingsAndRowsTogetherWithoutSnaps() {
        main(() -> {
            focus(tile(4, 6));
            assertEquals("No initial vertical jump", 0, vertical.getScrollY());
            assertTrue(vertical.motion.isRunning());
        });
        await("Vertical scroll has an intermediate frame", () -> vertical.getScrollY() > 0 && vertical.getScrollY() < vertical.motion.target());
        main(() -> {
            int current = vertical.getScrollY();
            focus(headings.get(5));
            assertEquals("Holding Down must retarget, not snap", current, vertical.getScrollY());
        });
        settle();
        main(() -> { whole(headings.get(5)); whole(tile(5, 0)); focus(headings.get(0)); });
        settle();
        main(() -> { assertEquals(0, vertical.getScrollY()); whole(headings.get(0)); });
    }

    @Test public void appendingCardsDuringMotionDoesNotSnapOrLoseFocus() {
        TvCarousel carousel = rows.get(0);
        main(() -> focus(tile(0, 4)));
        await("Animation starts", () -> carousel.getScrollX() > 0 && carousel.motion.isRunning());
        main(() -> {
            LinearLayout cards = (LinearLayout) carousel.getChildAt(0);
            for (int i = 0; i < 10; i++) addTile(cards);
            assertSame(tile(0, 4), rule.getActivity().getCurrentFocus());
        });
        settle();
        main(() -> { whole(tile(0, 4)); whole(tile(0, 7)); focus(tile(0, 19)); });
        settle();
        main(() -> { whole(tile(0, 16)); whole(tile(0, 19)); });
    }

    @Test public void closingTheScreenCancelsBothAxes() {
        main(() -> {
            focus(tile(5, 9));
            assertTrue(vertical.motion.isRunning()); assertTrue(rows.get(5).motion.isRunning());
            root.removeView(vertical);
            assertFalse(vertical.motion.isRunning()); assertFalse(rows.get(5).motion.isRunning());
        });
    }

    @Test public void immediateRequestsStayImmediateAndGenericHorizontalChoicesAnimate() {
        main(() -> {
            focus(tile(5, 0));
            headings.get(5).requestRectangleOnScreen(new Rect(0, 0, headings.get(5).getWidth(), headings.get(5).getHeight()), true);
            assertFalse(vertical.motion.isRunning()); whole(headings.get(5));
        });
        TvHorizontalScrollView[] seasons = new TvHorizontalScrollView[1];
        View[] choices = new View[10];
        main(() -> {
            seasons[0] = new TvHorizontalScrollView(rule.getActivity());
            LinearLayout row = new LinearLayout(rule.getActivity()); seasons[0].addView(row);
            for (int i = 0; i < choices.length; i++) {
                choices[i] = new View(rule.getActivity()); choices[i].setFocusableInTouchMode(true);
                row.addView(choices[i], new LinearLayout.LayoutParams(dp(140), dp(44)));
            }
            root.removeAllViews(); root.addView(seasons[0]);
        });
        instrumentation().waitForIdleSync();
        main(() -> { focus(choices[9]); assertEquals(0, seasons[0].getScrollX()); assertTrue(seasons[0].motion.isRunning()); });
        await("Season choices settle", () -> !seasons[0].motion.isRunning());
        main(() -> whole(choices[9]));
    }
}
