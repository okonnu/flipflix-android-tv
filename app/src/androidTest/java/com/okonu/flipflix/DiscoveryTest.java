package com.okonu.flipflix;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native TV navigation and actual local Media3 decoding. No external stream dependency. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public final class DiscoveryTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private DiscoveryView view;
    private FrameLayout root;
    private final List<DiscoveryRow> rows = new ArrayList<>();
    private DiscoveryView.Position position;
    private DiscoveryItem opened;
    private String section, savedLibrary;
    private CatalogApi fixtureApi;
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void setField(String name, Object value) {
        try { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(rule.getActivity(), value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private DiscoveryItem item(int r, int c) {
        DiscoveryItem i = new DiscoveryItem(); i.key = r + ":" + c; i.title = "Title " + r + " / " + c;
        i.category = "Category " + r; i.metadata = "2010 | Open film | Test fixture";
        i.description = "A licensed sample fixture for predictable remote navigation and real preview playback.";
        i.thumbnail = i.heroImage = "asset:///open-films/" + (c % 2 == 0 ? "sintel" : "bunny") + ".png";
        return i;
    }
    @Before public void setup() {
        instrumentation().setInTouchMode(false);
        main(() -> {
            DiscoveryView old = (DiscoveryView) field(rule.getActivity(), "discovery"); if (old != null) old.close();
            setField("generation", 999999);
            savedLibrary = rule.getActivity().getSharedPreferences("discovery", 0).getString("library", "[]");
            rule.getActivity().getSharedPreferences("discovery", 0).edit().putString("library", "[]").commit();
            for (int r = 0; r < 6; r++) {
                DiscoveryRow row = new DiscoveryRow("Category " + r, "Section " + r, null, "test", r == 4);
                row.complete = true; for (int c = 0; c < 10; c++) row.items.add(item(r, c)); rows.add(row);
            }
            root = new FrameLayout(rule.getActivity()); rule.getActivity().setContentView(root);
            fixtureApi = new CatalogApi();
            position = new DiscoveryView.Position(); mount();
        });
        await("First card focused after layout", () -> card(0, 0).hasFocus() && view.getWidth() > 0);
        settle();
    }
    private void mount() {
        view = new DiscoveryView(rule.getActivity(), fixtureApi, rows, new DiscoveryView.Actions() {
            public void open(DiscoveryItem item) { opened = item; }
            public void section(String name) { section = name; }
            public void more(DiscoveryRow row) { }
        }, position);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1)); setField("discovery", view);
    }
    private DiscoveryView.Card card(int r, int c) { return view.rowViews.get(r).cards.get(c); }
    private void key(int key) { instrumentation().sendKeyDownUpSync(key); instrumentation().waitForIdleSync(); }
    private interface Condition { boolean met(); }
    private void await(String message, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 15000; boolean[] done = {false};
        while (!done[0] && SystemClock.uptimeMillis() < deadline) {
            main(() -> done[0] = condition.met()); if (!done[0]) SystemClock.sleep(20);
        }
        assertTrue(message, done[0]);
    }
    private void settle() {
        await("All scrolling settles", () -> {
            if (view.vertical.isRunning() || view.isLayoutRequested()) return false;
            for (DiscoveryView.RowView r : view.rowViews) if (r.carousel.motion.isRunning() || r.carousel.isLayoutRequested()) return false;
            return true;
        });
    }
    @After public void cleanup() {
        main(() -> {
            if (view != null) view.close();
            if (fixtureApi != null) fixtureApi.close();
            rule.getActivity().getSharedPreferences("discovery", 0).edit().putString("library", savedLibrary).commit();
        });
    }

    @Test public void remoteSelectionOwnsHeroAndRemembersEachRow() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> { assertTrue(card(1, 0).hasFocus()); assertEquals("Title 1 / 0", ((TextView) field(view, "title")).getText().toString()); });
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> { assertTrue(card(0, 2).hasFocus()); assertSame(rows.get(0).items.get(2), view.preview.selected); });
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> assertTrue(card(1, 1).hasFocus()));
        key(KeyEvent.KEYCODE_DPAD_CENTER); assertSame(rows.get(1).items.get(1), opened);
    }
    @Test public void railBoundaryAndClickableHeadingsReturnPredictably() {
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        main(() -> assertEquals("Browse", rule.getActivity().getCurrentFocus().getContentDescription()));
        key(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> assertEquals("Search", rule.getActivity().getCurrentFocus().getContentDescription()));
        key(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals("Search", section);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        main(() -> assertTrue(card(0, 0).hasFocus()));
        key(KeyEvent.KEYCODE_MENU); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("Section 1", section);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        main(() -> assertTrue(card(1, 0).hasFocus()));
    }
    @Test public void movementAnimatesAnchorsLeftAndDoesNotClipSelectedCard() {
        final List<Integer> frames = new ArrayList<>();
        main(() -> {
            view.rowViews.get(0).carousel.getViewTreeObserver().addOnScrollChangedListener(() -> frames.add(view.rowViews.get(0).carousel.getScrollX()));
            card(0, 4).requestFocus(); assertEquals(0, view.rowViews.get(0).carousel.getScrollX());
        });
        await("Horizontal intermediate frame", () -> view.rowViews.get(0).carousel.getScrollX() > 0 && view.rowViews.get(0).carousel.motion.isRunning());
        settle();
        main(() -> {
            assertTrue(frames.size() >= 3);
            assertEquals(Math.round(8 * view.getResources().getDisplayMetrics().density), card(0, 4).getLeft() - view.rowViews.get(0).carousel.getScrollX());
            Rect actual = new Rect(); assertTrue(card(0, 4).picture.getGlobalVisibleRect(actual));
            assertTrue("Selected outline including top remains whole", actual.height() >= card(0, 4).picture.getHeight());
            card(3, 0).requestFocus(); assertTrue(view.vertical.isRunning());
        });
        settle();
        main(() -> {
            Rect clip = view.scroll.getClipBounds(); assertNotNull(clip); assertEquals(view.scroll.getHeight(), clip.height());
            Rect picture = new Rect(); assertTrue(card(3, 0).picture.getGlobalVisibleRect(picture));
            assertTrue(picture.height() >= card(3, 0).picture.getHeight());
            assertTrue(view.rowViews.get(3).heading.getGlobalVisibleRect(new Rect()));
        });
    }
    @Test public void hoverSelectsAndMouseClickOpens() {
        main(() -> {
            MotionEvent e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 5, 5, 0);
            e.setSource(android.view.InputDevice.SOURCE_MOUSE);
            card(0, 3).dispatchGenericMotionEvent(e); e.recycle();
            assertTrue(card(0, 3).hasFocus()); assertSame(rows.get(0).items.get(3), view.preview.selected);
            card(0, 3).performClick(); assertSame(rows.get(0).items.get(3), opened);
        });
    }
    private void swipe(View target, boolean horizontal) {
        int[] p = new int[2]; main(() -> target.getLocationOnScreen(p));
        float x = p[0] + target.getWidth() * .8f, y = p[1] + target.getHeight() * .65f;
        long start = SystemClock.uptimeMillis();
        for (int step = 0; step <= 12; step++) {
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            float fraction = step / 12f;
            MotionEvent e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                x - (horizontal ? target.getWidth() * .55f * fraction : 0),
                y - (horizontal ? 0 : target.getHeight() * .45f * fraction), 0);
            e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); instrumentation().sendPointerSync(e); e.recycle(); SystemClock.sleep(24);
        }
        instrumentation().waitForIdleSync();
    }
    @Test public void touchSwipesScrollBothAxes() {
        swipe(view.rowViews.get(0).carousel, true);
        await("Native horizontal touch scroll", () -> view.rowViews.get(0).carousel.getScrollX() > 100);
        swipe(view.scroll, false);
        await("Native vertical touch scroll", () -> view.scroll.getScrollY() > 30);
    }
    @Test public void compactLandscapeKeepsHeroActionsAboveRows() {
        main(() -> view.setLayoutParams(new FrameLayout.LayoutParams(1280, 720)));
        await("Compact layout settles", () -> view.getWidth() == 1280 && view.getHeight() == 720 && !view.isLayoutRequested());
        main(() -> {
            View hero = (View) field(view, "hero");
            assertTrue("Hero does not overlap browsing rows", hero.getBottom() <= view.scroll.getTop());
            assertEquals(720 - view.scroll.getTop(), view.scroll.getHeight());
            assertTrue(card(0, 0).isShown());
        });
    }
    private void preview(int r, int c, String path) {
        main(() -> { rows.get(r).items.get(c).previewUrl = path; rows.get(r).items.get(c).previewStartMs = 10000; card(r, c).requestFocus(); view.preview.restartDelay(); });
    }
    @Test public void actualMutedPreviewStartsOnlyAfterIdleAndLeavesBrowsingVisible() {
        preview(0, 0, "asset:///open-films/sintel.mp4");
        SystemClock.sleep(950); main(() -> assertNull(view.preview.player));
        await("Real decoded video crossfades in", () -> view.preview.player != null && view.preview.player.isPlaying() && view.preview.view.getAlpha() > .99f);
        main(() -> {
            assertEquals(0f, view.preview.player.getVolume(), 0f); assertEquals(1, view.preview.startCount);
            assertEquals(View.VISIBLE, ((View) field(view, "hero")).getVisibility()); assertEquals(1f, ((View) field(view, "hero")).getAlpha(), 0f);
            assertTrue(card(0, 0).hasFocus()); assertTrue(view.scroll.isShown());
        });
    }
    @Test public void rapidNavigationCancelsOldTimersAndOldPlayingVideo() {
        preview(0, 0, "asset:///open-films/sintel.mp4"); SystemClock.sleep(450);
        preview(0, 1, "asset:///open-films/bunny.mp4"); SystemClock.sleep(450);
        preview(0, 2, "asset:///open-films/sintel.mp4"); SystemClock.sleep(450);
        main(() -> { assertEquals(0, view.preview.startCount); assertNull(view.preview.player); });
        await("Only final selection starts", () -> view.preview.player != null && view.preview.player.isPlaying() && view.preview.view.getAlpha() > .99f);
        main(() -> { assertEquals(1, view.preview.startCount); card(0, 3).requestFocus(); assertNull(view.preview.player); assertEquals(0f, view.preview.view.getAlpha(), 0f); });
        SystemClock.sleep(1800);
        main(() -> { assertNull(view.preview.player); assertEquals(0f, view.preview.view.getAlpha(), 0f); assertSame(rows.get(0).items.get(3), view.preview.selected); });
    }
    @Test public void unavailablePreviewRetainsPosterAndHiddenPageStopsPlayback() {
        preview(0, 0, "asset:///open-films/not-found.mp4");
        await("Bad preview attempted", () -> view.preview.startCount == 1);
        await("Bad preview disposed with poster intact", () -> view.preview.player == null && ((android.widget.ImageView) field(view, "incoming")).getDrawable() != null);
        main(() -> assertEquals(0f, view.preview.view.getAlpha(), 0f));
        preview(0, 1, "asset:///open-films/bunny.mp4");
        await("Valid playback starts", () -> view.preview.player != null && view.preview.player.isPlaying());
        main(() -> { view.setActive(false); assertNull(view.preview.player); });
        SystemClock.sleep(1800); main(() -> assertNull(view.preview.player));
        main(() -> view.setActive(true));
        await("Return restarts after idle", () -> view.preview.player != null && view.preview.player.isPlaying());
    }
    @Test public void savedLibraryAndSelectionSurviveLeavingBrowse() {
        main(() -> card(2, 3).requestFocus()); settle();
        main(() -> {
            ((View) field(view, "save")).performClick(); assertEquals(1, DiscoveryView.saved(rule.getActivity()).size());
            assertEquals("2:3", DiscoveryView.saved(rule.getActivity()).get(0).key);
            view.close(); root.removeAllViews(); mount();
        });
        await("Restored card focused", () -> card(2, 3).hasFocus()); settle();
        main(() -> { assertTrue(view.scroll.getScrollY() > 0); assertEquals("Saved", ((TextView) field(view, "save")).getText().toString()); });
    }
    @Test public void reducedMotionDisablesAutomaticVideo() throws Exception {
        String original = android.provider.Settings.Global.getString(rule.getActivity().getContentResolver(), "animator_duration_scale");
        try {
            instrumentation().getUiAutomation().executeShellCommand("settings put global animator_duration_scale 0").close();
            await("Reduced motion enabled", () -> view.preview.reducedMotion());
            preview(0, 0, "asset:///open-films/sintel.mp4"); SystemClock.sleep(1800);
            main(() -> { assertNull(view.preview.player); assertEquals(0, view.preview.startCount); });
        } finally {
            instrumentation().getUiAutomation().executeShellCommand("settings put global animator_duration_scale " + (original == null ? "1" : original)).close();
            await("Animation preference restored", () -> !view.preview.reducedMotion());
        }
    }
}
