package com.okonu.flipflix;

import android.app.Instrumentation;
import androidx.test.rule.ActivityTestRule;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.util.Base64;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android TV input and player tests. No test entry point is shipped in the APK. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackControlsTest {
    @Rule public final ActivityTestRule<MainActivity> activityRule = new ActivityTestRule<>(MainActivity.class, false, false);
    private PlaybackControls controls;
    private MainActivity activity;
    private FrameLayout frame;

    private MainActivity getActivity() { if (activity == null) activity = activityRule.launchActivity(null); return activity; }
    private Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }

    private void main(Runnable action) { getInstrumentation().runOnMainSync(action); }
    private void field(String name, Object value) {
        try { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void install(PlaybackBackend backend) {
        activity = getActivity();
        // A preceding WebView test can leave touch mode/window setup in flight.
        // Establish a ready remote-focused surface before sending the first OK.
        getInstrumentation().setInTouchMode(false);
        main(() -> {
            // Invalidate outstanding catalog callbacks before replacing the test surface.
            field("generation", 999999);
            frame = new FrameLayout(activity);
            View video = new View(activity); video.setFocusableInTouchMode(true);
            frame.addView(video, new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(frame);
            controls = new PlaybackControls(activity, () -> backend, video::requestFocus,
                "TV playback test | Season 1 Episode 1", () -> {}, () -> {}, () -> {}, () -> {}, () -> {});
            frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            field("playerOverlay", controls); field("playerOpen", true);
            controls.activate();
        });
        waitFor(() -> activity.hasWindowFocus() && controls.isLaidOut()
            && activity.getCurrentFocus() instanceof ImageButton);
    }
    private void key(int key) { getInstrumentation().sendKeyDownUpSync(key); getInstrumentation().waitForIdleSync(); }

    @Test public void testTimelineFocusUsesThumbInsteadOfWhitePanel() {
        install(new FakeBackend());
        key(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> {
            android.widget.SeekBar timeline = (android.widget.SeekBar) activity.getCurrentFocus();
            assertNull("The Netflix-style timeline has no rectangular focus background", timeline.getBackground());
            if (android.os.Build.VERSION.SDK_INT >= 26) assertFalse(timeline.getDefaultFocusHighlightEnabled());
        });
    }

    @Test public void testFocusedControlsHideAndWakeWithoutAccidentalClick() {
        FakeBackend backend = new FakeBackend(); install(backend);
        main(() -> assertTrue(activity.getCurrentFocus() instanceof ImageButton));
        SystemClock.sleep(5400);
        main(() -> assertEquals(View.GONE, controls.getVisibility()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> assertEquals(View.VISIBLE, controls.getVisibility()));
        assertEquals("Wake must not activate the focused action", 0, backend.commands);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("toggle", backend.last);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("seek", backend.last); assertEquals("20000", backend.value);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("30000", backend.value);
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("mute", backend.last);
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("40000", backend.value);
        main(() -> controls.suspendForMenu());
        SystemClock.sleep(5400);
        main(() -> { assertEquals(View.VISIBLE, controls.getVisibility()); controls.resumeFromMenu(); });
        SystemClock.sleep(5400);
        main(() -> assertEquals(View.GONE, controls.getVisibility()));
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        main(() -> assertEquals(View.VISIBLE, controls.getVisibility()));
        key(KeyEvent.KEYCODE_MEDIA_PAUSE);
        assertEquals("pause", backend.last);
    }

    @Test public void testRemoteCanStartSportsBeforeVideoIsReady() {
        FakeBackend backend = new FakeBackend();
        backend.s.available = false; backend.s.canStart = true; backend.s.paused = true;
        install(backend);
        key(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals("toggle", backend.last);
        key(KeyEvent.KEYCODE_MEDIA_PLAY); assertEquals("play", backend.last);
        key(KeyEvent.KEYCODE_MEDIA_PAUSE); assertEquals("pause", backend.last);
        int sent = backend.commands;
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("Seeking still requires a ready video", sent, backend.commands);
        main(() -> backend.s.canStart = false);
        key(KeyEvent.KEYCODE_MEDIA_PLAY);
        assertEquals("Other providers keep their readiness check", sent, backend.commands);
    }

    @Test public void testHeldSeekAcceleratesAndKeyUpDoesNotSeekAgain() {
        FakeBackend backend = new FakeBackend(); backend.s.duration = 3600000; backend.s.position = 1000000; install(backend);
        main(() -> {
            // Exercise real KeyEvent repeats through the Activity. A held OK on
            // the seek icon must not also produce the normal click on key-up.
            controls.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            controls.handleKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
        });
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 7; i++) {
            int repeat = i;
            main(() -> activity.dispatchKeyEvent(new KeyEvent(start, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat)));
            SystemClock.sleep(200);
        }
        int sent = backend.commands;
        assertTrue("Holding accelerates beyond eight 10s steps", backend.s.position < 920000);
        main(() -> activity.dispatchKeyEvent(new KeyEvent(start, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0)));
        assertEquals(sent, backend.commands);
        main(() -> backend.s.position = 1000000);
        SystemClock.sleep(1000);
        key(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        assertEquals("1010000", backend.value);
        main(() -> controls.sourceName("Rift"));
        key(KeyEvent.KEYCODE_MEDIA_REWIND);
        assertEquals("1000000", backend.value);
    }

    @Test public void testRiftSettingsAreRemoteReachableAndMenusKeepControlsVisible() {
        FakeBackend backend = new FakeBackend();
        backend.s.settings.put("audio", java.util.Arrays.asList(new PlaybackBackend.Option("en","English",true), new PlaybackBackend.Option("ko","Korean",false)));
        install(backend);
        main(() -> {
            ViewGroup actions = (ViewGroup) activity.getCurrentFocus().getParent();
            for (int i = 0; i < actions.getChildCount(); i++) {
                View button = actions.getChildAt(i);
                if ("Playback settings".contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription())) button.requestFocus();
            }
        });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(300);
        key(KeyEvent.KEYCODE_DPAD_CENTER); // Audio language.
        SystemClock.sleep(300);
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("audio", backend.last); assertEquals("ko", backend.value);
        main(() -> { assertEquals(View.VISIBLE, controls.getVisibility()); assertTrue(activity.getCurrentFocus() instanceof ImageButton); });
    }

    @Test public void testExoPlaybackSeekMuteSingleQualityAndFitFill() throws Exception {
        activity = getActivity();
        File movie = copyAsset("silent-black-25s.mp4");
        NativePlayback[] nativeVideo = new NativePlayback[1];
        main(() -> nativeVideo[0] = new NativePlayback(activity, movie.toURI().toString(), "https://example.com/", null));
        install(nativeVideo[0]);
        main(() -> { frame.addView(nativeVideo[0].view, 0, new FrameLayout.LayoutParams(-1, -1));
            nativeVideo[0].start(); });
        try {
            waitFor(() -> nativeVideo[0].state().available);
            main(() -> {
                NativePlayback n = nativeVideo[0];
                assertTrue(n.state().seekable); assertTrue(n.state().duration > 24000);
                n.command("pause", ""); assertTrue(n.state().paused);
                n.command("seek", "12000");
                n.command("mute", ""); assertTrue(n.state().muted);
            });
            waitFor(() -> NativeFirstPlaybackTest.playerVolume(nativeVideo[0]) == 0);
            main(() -> {
                NativePlayback n = nativeVideo[0];
                n.command("mute", ""); assertFalse(n.state().muted);
            });
            waitFor(() -> NativeFirstPlaybackTest.playerVolume(nativeVideo[0]) == 100);
            main(() -> {
                NativePlayback n = nativeVideo[0];
                assertEquals("Single rendition has no invented quality choices", 1, n.state().qualities.size());
                assertEquals("auto", n.state().qualities.get(0).id);
                assertTrue(n.state().qualityMessage.contains("one supported video quality"));
            });
            waitFor(() -> Math.abs(nativeVideo[0].state().position - 12000) < 1000);
            main(() -> {
                nativeVideo[0].command("play", ""); assertFalse(nativeVideo[0].state().paused);
                nativeVideo[0].command("pause", "");
                nativeVideo[0].view.setLayoutParams(new FrameLayout.LayoutParams(600, 600, Gravity.CENTER));
                assertTrue(nativeVideo[0].state().canResize);
                assertFalse(nativeVideo[0].state().fillScreen);
            });
            View[] surface = new View[1];
            waitFor(() -> {
                surface[0] = findVideoSurface(nativeVideo[0].view);
                return surface[0] != null && surface[0].getWidth() == 600 && surface[0].getHeight() < 600;
            });
            main(() -> {
                nativeVideo[0].command("resize", "fill");
                assertTrue(nativeVideo[0].state().fillScreen);
                assertTrue("Resizing must not resume a paused video", nativeVideo[0].state().paused);
            });
            waitFor(() -> surface[0].getWidth() > 600 && surface[0].getHeight() == 600);
            main(() -> nativeVideo[0].command("resize", "fit"));
            waitFor(() -> surface[0].getWidth() == 600 && surface[0].getHeight() < 600);
            main(() -> assertFalse(nativeVideo[0].state().fillScreen));
        } finally { main(() -> { controls.deactivate(); nativeVideo[0].release(); }); }
    }

    private static View findVideoSurface(View view) {
        if (view instanceof android.view.SurfaceView && view.getVisibility() == View.VISIBLE) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findVideoSurface(group.getChildAt(i)); if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void testFillControlTogglesWithRemoteAndKeepsFocus() {
        FakeBackend backend = new FakeBackend(); install(backend);
        AtomicBoolean selected = new AtomicBoolean();
        for (int i = 0; i < 14 && !selected.get(); i++) {
            main(() -> selected.set("Fill screen (crop edges)".contentEquals(activity.getCurrentFocus().getContentDescription())));
            if (!selected.get()) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        }
        assertTrue("Fill screen is reachable with the remote", selected.get());
        main(() -> {
            ViewGroup actions = (ViewGroup) activity.getCurrentFocus().getParent();
            for (int i = 0; i < actions.getChildCount(); i++) {
                if (actions.getChildAt(i) instanceof ImageButton) assertWhole(actions.getChildAt(i));
            }
        });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("resize", backend.last); assertEquals("fill", backend.value);
        main(() -> assertEquals("Fit picture (show all)", activity.getCurrentFocus().getContentDescription()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("fit", backend.value);
        main(() -> assertEquals("Fill screen (crop edges)", activity.getCurrentFocus().getContentDescription()));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_CENTER); // Wake only, keep Fit.
        assertEquals("fit", backend.value);
        main(() -> assertEquals("Fill screen (crop edges)", activity.getCurrentFocus().getContentDescription()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        main(() -> assertEquals("Close player", activity.getCurrentFocus().getContentDescription()));
    }

    private File copyAsset(String name) throws Exception {
        File file = new File(activity.getCacheDir(), name);
        try (InputStream input = getInstrumentation().getContext().getAssets().open(name); FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return file;
    }

    @Test public void testEmbeddedVideoBridge() throws Exception {
        activity = getActivity();
        String encoded;
        try (InputStream input = getInstrumentation().getContext().getAssets().open("silent-black-25s.mp4");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        }
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;background:black'><video autoplay muted playsinline style='width:90vw;height:80vh;object-fit:contain;transform:translateY(12px)' src='data:video/mp4;base64," + encoded
            + "'><track kind='subtitles' label='English fixture' srclang='en' src='data:text/vtt,WEBVTT%0A%0A00:00:00.000%20--%3E%2000:00:24.000%0AFixture%0A'></video></body></html>";
        EmbeddedPlayback[] bridge = new EmbeddedPlayback[1]; WebView[] web = new WebView[1];
        main(() -> {
            web[0] = new WebView(activity);
            web[0].getSettings().setJavaScriptEnabled(true);
            web[0].getSettings().setMediaPlaybackRequiresUserGesture(false);
            bridge[0] = new EmbeddedPlayback(web[0]);
            web[0].setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) { bridge[0].pageFinished(); }
            });
        });
        install(bridge[0]);
        main(() -> {
            frame.addView(web[0], 0, new FrameLayout.LayoutParams(-1, -1));
            web[0].loadDataWithBaseURL("https://embed.vidrift.in/", html, "text/html", "UTF-8", null);
        });
        try {
            waitFor(() -> bridge[0].state().available);
            main(() -> bridge[0].command("pause", "")); waitFor(() -> bridge[0].state().paused);
            main(() -> bridge[0].command("seek", "10000")); waitFor(() -> bridge[0].state().position >= 9900);
            main(() -> bridge[0].command("mute", "")); waitFor(() -> !bridge[0].state().muted);
            main(() -> bridge[0].command("mute", "")); waitFor(() -> bridge[0].state().muted);
            waitFor(() -> bridge[0].state().subtitles.size() == 2);
            main(() -> bridge[0].command("subtitle", "0")); waitFor(() -> bridge[0].state().subtitles.get(1).selected);
            main(() -> bridge[0].command("subtitle", "off")); waitFor(() -> bridge[0].state().subtitles.get(0).selected);
            main(() -> { assertTrue(bridge[0].state().canResize); assertFalse(bridge[0].state().fillScreen); });
            main(() -> bridge[0].command("resize", "fill")); waitFor(() -> bridge[0].state().fillScreen);
            assertEquals("true", javascript(web[0], "(function(){var v=document.querySelector('video'),r=v.getBoundingClientRect();"
                + "return getComputedStyle(v).objectFit==='cover' && Math.abs(r.width-innerWidth)<2 && Math.abs(r.height-innerHeight)<2"
                + " && r.top===0 && r.left===0;})()"));
            main(() -> bridge[0].command("resize", "fit")); waitFor(() -> !bridge[0].state().fillScreen);
            assertEquals("true", javascript(web[0], "(function(){var v=document.querySelector('video');return !v.hasAttribute('data-flipflix-fill')"
                + " && getComputedStyle(v).objectFit==='contain' && v.style.width==='90vw' && v.style.transform==='translateY(12px)';})()"));
            // Replacing the provider's video must not lose the selected mode or keep a stale element.
            main(() -> bridge[0].command("resize", "fill")); waitFor(() -> bridge[0].state().fillScreen);
            javascript(web[0], "(function(){var v=document.querySelector('video'),n=v.cloneNode(true);n.removeAttribute('data-flipflix-fill');v.replaceWith(n);})()");
            waitFor(() -> bridge[0].state().available && !bridge[0].state().paused);
            // Explicit state refresh also covers players that replace their element while paused.
            main(() -> bridge[0].refresh());
            assertEquals("true", javascript(web[0], "document.querySelector('video').hasAttribute('data-flipflix-fill')"));
            main(() -> bridge[0].command("resize", "fit")); waitFor(() -> !bridge[0].state().fillScreen);
            main(() -> bridge[0].command("play", "")); waitFor(() -> !bridge[0].state().paused);
        } finally { main(() -> { controls.deactivate(); web[0].destroy(); }); }
    }
    private interface Condition { boolean met(); }
    private String javascript(WebView web, String script) throws Exception {
        AtomicReference<String> value = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        main(() -> web.evaluateJavascript(script, result -> { value.set(result); done.countDown(); }));
        assertTrue("JavaScript returned", done.await(5, TimeUnit.SECONDS)); return value.get();
    }
    private void waitFor(Condition condition) {
        AtomicBoolean success = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 12000;
        while (!success.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> success.set(condition.met())); SystemClock.sleep(100);
        }
        assertTrue("Playback state reached expected result", success.get());
    }

    @Test public void testCarouselShowsWholeCardsIncludingLastPage() {
        activity = getActivity();
        View[] tiles = new View[6]; TvCarousel[] carousel = new TvCarousel[1];
        main(() -> {
            field("generation", 999999);
            frame = new FrameLayout(activity); activity.setContentView(frame);
            carousel[0] = new TvCarousel(activity);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            carousel[0].addView(row);
            frame.addView(carousel[0], new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = new View(activity); tiles[i].setFocusableInTouchMode(true);
                LinearLayout.LayoutParams tileSize = new LinearLayout.LayoutParams(200, 120);
                tileSize.bottomMargin = 16;
                row.addView(tiles[i], tileSize);
            }
        });
        getInstrumentation().waitForIdleSync();
        main(() -> {
            tiles[0].requestFocus();
            View row = (View) tiles[0].getParent();
            assertTrue("Top border must not enter clipped row padding: top=" + tiles[0].getTop() + ", padding=" + row.getPaddingTop(),
                tiles[0].getTop() >= row.getPaddingTop());
            for (int i = 0; i < 4; i++) assertWhole(tiles[i]);
            assertFalse(tiles[4].getGlobalVisibleRect(new Rect()));
            tiles[4].requestFocus();
        });
        waitFor(() -> !carousel[0].motion.isRunning());
        main(() -> {
            assertWhole(tiles[4]); assertWhole(tiles[5]);
            assertFalse(tiles[3].getGlobalVisibleRect(new Rect()));
            tiles[3].requestFocus();
        });
        waitFor(() -> !carousel[0].motion.isRunning());
        main(() -> {
            for (int i = 0; i < 4; i++) assertWhole(tiles[i]);
        });
    }
    private void assertWhole(View view) {
        Rect visible = new Rect(); assertTrue(view.getGlobalVisibleRect(visible));
        assertEquals("Full card width visible", view.getWidth(), visible.width());
        assertEquals("Full card height visible", view.getHeight(), visible.height());
    }

    @Test public void testEverySectionHeadingIsRemoteSelectable() {
        activity = getActivity();
        String[] sections = {"Trending", "Movies", "TV Shows", "Anime", "Live Sports"};
        for (int target = 0; target < sections.length; target++) {
            if (target > 0) key(KeyEvent.KEYCODE_BACK);
            String expected = sections[target];
            int[] targetRow = {0};
            main(() -> {
                DiscoveryView view = (DiscoveryView) readField(activity, "discovery");
                for (int i = 0; i < view.rows.size(); i++) if (view.rows.get(i).title.equals(expected)) targetRow[0] = i;
                view.rowViews.get(0).heading.requestFocus();
            });
            for (int down = 0; down < targetRow[0]; down++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            main(() -> assertEquals(expected, ((TextView) activity.getCurrentFocus()).getText().toString()));
            if (target == 1) {
                // Menu is the direct remote shortcut from a card to its category heading.
                main(() -> {
                    DiscoveryView view = (DiscoveryView) readField(activity, "discovery");
                    DiscoveryRow row = view.rows.get(targetRow[0]);
                    if (row.items.isEmpty()) {
                        DiscoveryItem item = new DiscoveryItem(); item.key = "test:heading"; item.title = "Test";
                        item.category = "Movies"; item.thumbnail = item.heroImage = "";
                        row.items.add(item); view.refreshRow(targetRow[0]);
                    }
                });
                key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_MENU);
                main(() -> assertEquals("Movies", ((TextView) activity.getCurrentFocus()).getText().toString()));
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER);
            main(() -> assertEquals(expected, readField(activity, "section")));
        }
    }
    @Test public void testCatalogUsesDiscoveryCanvasWithBlackSystemBars() {
        activity = getActivity();
        main(() -> {
            View root = (View) readField(activity, "root");
            assertEquals(AppStyle.BG, ((ColorDrawable) root.getBackground()).getColor());
            TypedValue color = new TypedValue();
            assertTrue(activity.getTheme().resolveAttribute(android.R.attr.colorBackground, color, true));
            assertEquals(AppStyle.BG, color.data);
            assertTrue(activity.getTheme().resolveAttribute(android.R.attr.windowBackground, color, true));
            assertEquals("Launch window matches the black intro before the catalog mounts", Color.BLACK, color.data);
            assertEquals(Color.BLACK, activity.getWindow().getNavigationBarColor());
            assertEquals(Color.BLACK, activity.getWindow().getStatusBarColor());
        });
    }
    private Object readField(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @After public void tearDown() {
        if (controls != null) main(() -> controls.deactivate());
    }
    private static final class FakeBackend implements PlaybackBackend {
        final State s = new State(); int commands; String last, value;
        FakeBackend() { s.available = true; s.seekable = true; s.canResize = true; s.position = 30000; s.duration = 120000; s.paused = false; }
        @Override public State state() { return s; }
        @Override public void command(String name, String value) {
            commands++; last = name; this.value = value;
            if (name.equals("seek")) s.position = Long.parseLong(value);
            if (name.equals("resize")) s.fillScreen = value.equals("fill");
        }
    }
}
