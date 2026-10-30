package com.okonu.flipflix;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercise the real Activity player, not just the overlay in isolation. */
@RunWith(AndroidJUnit4.class)
public final class PlayerNavigationTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private MainActivity activity() { return rule.getActivity(); }
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private void key(int code) { instrumentation().sendKeyDownUpSync(code); instrumentation().waitForIdleSync(); }
    private Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private Object invoke(String name, Object... arguments) {
        try {
            for (Method method : MainActivity.class.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                method.setAccessible(true); return method.invoke(activity(), arguments);
            }
            throw new NoSuchMethodException(name);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private Object item(String kind) throws Exception {
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        return from.invoke(null, new JSONObject().put("id", 0).put("title", "Fullscreen navigation test")
            .put("media_type", kind).put("year", "2026"), kind);
    }
    private void start(Object item) {
        main(() -> { invoke("showPlayer", item, 2, 3, 0); stopEmbedNetwork(); });
        instrumentation().waitForIdleSync();
    }
    private void stopEmbedNetwork() {
        // Layout/navigation assertions must not depend on a live provider response.
        WebView web = (WebView) field(activity(), "player");
        if (web != null) { web.stopLoading(); web.loadUrl("about:blank"); }
    }
    private void assertFullscreen() {
        assertEquals(true, field(activity(), "playerOpen"));
        FrameLayout root = (FrameLayout) field(activity(), "root");
        FrameLayout screen = (FrameLayout) field(activity(), "playerScreen");
        assertEquals(0, screen.getPaddingTop()); assertEquals(0, screen.getPaddingBottom());
        assertEquals(0, screen.getPaddingLeft()); assertEquals(0, screen.getPaddingRight());
        assertEquals(root.getWidth(), screen.getWidth()); assertEquals(root.getHeight(), screen.getHeight());
        assertTrue(root.getWidth() > 0 && root.getHeight() > 0);
        assertEquals("No old header/footer buttons exist, even hidden", 0, countButtons(root));
        assertNotNull(findDescription(root, "Close player"));
        assertNotNull(findDescription(root, "Fill screen (crop edges)"));
        assertNull(findDescription(root, "Exit fullscreen"));
        assertTrue((activity().getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0);
    }
    private void assertClosed(boolean sports) {
        assertEquals(false, field(activity(), "playerOpen"));
        assertNull(field(activity(), "player")); assertNull(field(activity(), "nativePlayer"));
        assertNull(field(activity(), "playerOverlay")); assertNull(field(activity(), "playerScreen"));
        assertNull(field(activity(), "customVideoView"));
        assertEquals(!sports, field(activity(), "detailOpen"));
        assertEquals(0, activity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (sports) assertEquals("Live Sports", field(activity(), "section"));
    }
    private int countButtons(View view) {
        // Only inspect app chrome, not provider-owned elements.
        if (view instanceof WebView) return 0;
        int count = view instanceof Button ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countButtons(((ViewGroup) view).getChildAt(i));
        return count;
    }
    private View findDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = findDescription(((ViewGroup) view).getChildAt(i), description);
            if (result != null) return result;
        }
        return null;
    }
    private void select(String description) {
        // Dialog dismissal can finish before WindowManager restores input focus.
        // Do not send OK to the closing dialog instead of the player underneath.
        AtomicBoolean windowReady = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!windowReady.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> windowReady.set(activity().hasWindowFocus()));
            if (!windowReady.get()) SystemClock.sleep(50);
        }
        assertTrue("Player window has input focus before selection", windowReady.get());
        main(() -> {
            PlaybackControls controls = (PlaybackControls) field(activity(), "playerOverlay");
            controls.show(true);
            View button = findDescription(controls, description);
            assertNotNull(description, button); assertTrue(button.requestFocus());
        });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void awaitDialogFocus(String text) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        String[] focused = {""};
        boolean[] ready = {false};
        while (SystemClock.uptimeMillis() < deadline) {
            // Accessibility can report a focused child before WindowManager
            // transfers input to a newly opened dialog. Do not send D-pad events
            // to the Activity underneath it (especially after close/reopen).
            main(() -> {
                android.app.AlertDialog dialog = (android.app.AlertDialog) field(activity(), "playerSourceDialog");
                View decor = dialog == null || dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
                View input = decor == null ? null : decor.findFocus();
                focused[0] = input instanceof Button ? ((Button) input).getText().toString() : "";
                ready[0] = decor != null && decor.hasWindowFocus() && text.equals(focused[0]);
            });
            if (ready[0]) return;
            SystemClock.sleep(50);
        }
        assertTrue("Source dialog must own input before the next remote press; expected " + text + ", focused " + focused[0], ready[0]);
    }

    @Test public void backReturnsDirectlyToMovieOrSeriesDetails() throws Exception {
        for (String kind : new String[]{"movie", "tv"}) {
            Object item = item(kind); start(item);
            main(this::assertFullscreen);
            key(KeyEvent.KEYCODE_BACK);
            main(() -> { assertClosed(false); assertSame(item, field(activity(), "selected")); });
        }
    }

    @Test public void hiddenControlsDoNotFocusTheWebVideoOrDrawAnAndroidBorder() throws Exception {
        start(item("movie"));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> {
            assertEquals(View.GONE, ((View) field(activity(), "playerOverlay")).getVisibility());
            WebView web = (WebView) field(activity(), "player");
            assertFalse("Hiding native controls must not wake the provider controls", web.hasFocus());
            assertSame(field(activity(), "playerScreen"), activity().getCurrentFocus());
            if (android.os.Build.VERSION.SDK_INT >= 26) assertFalse(web.getDefaultFocusHighlightEnabled());
        });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> assertEquals(View.VISIBLE, ((View) field(activity(), "playerOverlay")).getVisibility()));
    }

    @Test public void backDuringSourceDialogFocusTransferDoesNotClosePlayback() throws Exception {
        start(item("tv"));
        main(() -> {
            invoke("choosePlayerSource", field(activity(), "selected"));
            // Before WindowManager transfers input, Back can still reach the Activity.
            activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertFullscreen();
        });
    }

    @Test public void episodeSourceRetryAndCloseStayInOnePlayer() throws Exception {
        start(item("tv"));
        select("Next episode"); main(this::stopEmbedNetwork);
        instrumentation().waitForIdleSync();
        main(() -> { assertFullscreen(); assertEquals(4, field(activity(), "episode")); });
        select("Previous episode"); main(this::stopEmbedNetwork);
        instrumentation().waitForIdleSync();
        main(() -> { assertFullscreen(); assertEquals(3, field(activity(), "episode")); });
        select("Source: Bolt / Web");
        awaitDialogFocus("Bolt");
        key(KeyEvent.KEYCODE_BACK); // Dismiss source menu, not playback.
        main(this::assertFullscreen);
        select("Source: Bolt / Web");
        awaitDialogFocus("Bolt");
        key(KeyEvent.KEYCODE_DPAD_DOWN); awaitDialogFocus("Rift");
        key(KeyEvent.KEYCODE_DPAD_DOWN); awaitDialogFocus("Fast");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(this::stopEmbedNetwork); instrumentation().waitForIdleSync();
        main(() -> { assertFullscreen(); assertEquals(2, field(activity(), "currentSourceIndex")); });
        select("Retry playback"); main(this::stopEmbedNetwork);
        instrumentation().waitForIdleSync(); main(this::assertFullscreen);
        select("Close player"); main(() -> assertClosed(false));
    }

    @Test public void repeatedlyReopenedSourceMenuAcceptsRemoteMovement() throws Exception {
        start(item("tv"));
        for (int i = 0; i < 8; i++) {
            select("Source: Bolt / Web"); awaitDialogFocus("Bolt");
            key(KeyEvent.KEYCODE_DPAD_DOWN); awaitDialogFocus("Rift");
            key(KeyEvent.KEYCODE_BACK);
        }
    }

    @Test public void riftNeverExposesWebFallbackAndTimeoutKeepsRetrySourceAndBack() throws Exception {
        Object item = item("tv");
        for (int source = 1; source < 2; source++) {
            final int selectedSource = source;
            main(() -> {
                invoke("showPlayer", item, 2, 3, selectedSource);
                stopEmbedNetwork();
                assertNull("No Bolt web-playback backend", field(activity(), "boltPlayer"));
                assertNull("No Rift web-playback backend", field(activity(), "riftPlayer"));
                WebView web = (WebView) field(activity(), "player");
                assertEquals(0f, web.getAlpha(), 0f); assertFalse(web.isFocusable());
                assertFalse(((PlaybackBackend) invoke("playbackBackend")).state().available);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    AtomicInteger hidden = new AtomicInteger();
                    web.getWebChromeClient().onShowCustomView(new FrameLayout(activity()), hidden::incrementAndGet);
                    assertEquals(1, hidden.get()); assertNull(field(activity(), "customVideoView"));
                }
                invoke("useProviderControls"); assertEquals(false, field(activity(), "providerInput"));
                invoke("nativeDiscoveryFailed", field(activity(), "sourceRequest"));
                assertEquals(View.GONE, web.getVisibility());
                assertFalse(((PlaybackBackend) invoke("playbackBackend")).state().providerControls);
                assertNotNull(findDescription((View) field(activity(), "playerOverlay"), "Retry playback"));
                assertTrue(((android.widget.TextView) field(activity(), "playerStatus")).getText().toString().contains("native stream"));
            });
            key(KeyEvent.KEYCODE_BACK);
            main(() -> assertClosed(false));
        }
    }

    @Test public void nativeVideoFillsThePlayerAndIsReleasedOnBack() throws Exception {
        Object item = item("movie"); start(item);
        File fixture = new File(activity().getCacheDir(), "navigation-video.mp4");
        try (InputStream input = instrumentation().getContext().getAssets().open("silent-black-25s.mp4");
             FileOutputStream output = new FileOutputStream(fixture)) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
        }
        NativePlayback[] nativeVideo = new NativePlayback[1];
        main(() -> {
            ((WebView) field(activity(), "player")).setVisibility(View.GONE);
            invoke("playNativeVideo", item, 1, 2, 3, fixture.toURI().toString(), "https://example.com/", null, field(activity(), "sourceRequest"));
            nativeVideo[0] = (NativePlayback) field(activity(), "nativePlayer");
        });
        AtomicBoolean ready = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 12000;
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> ready.set(nativeVideo[0].state().available)); SystemClock.sleep(100);
        }
        assertTrue("Fixture video plays through the real Activity", ready.get());
        main(() -> {
            assertFullscreen(); FrameLayout root = (FrameLayout) field(activity(), "root");
            assertEquals(root.getWidth(), nativeVideo[0].view.getWidth());
            assertEquals(root.getHeight(), nativeVideo[0].view.getHeight());
        });
        key(KeyEvent.KEYCODE_BACK);
        main(() -> { assertClosed(false); assertFalse(nativeVideo[0].state().available); assertEquals(0, nativeVideo[0].view.getChildCount()); assertNull(nativeVideo[0].view.getParent()); });
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    public void providerFullscreenNeverRestoresOldChromeAndBackClosesSports() throws Exception {
        start(item("sport"));
        WebChromeClient[] client = new WebChromeClient[1]; AtomicInteger hidden = new AtomicInteger();
        main(() -> {
            client[0] = ((WebView) field(activity(), "player")).getWebChromeClient();
            client[0].onShowCustomView(new FrameLayout(activity()), hidden::incrementAndGet);
            ViewGroup root = (ViewGroup) field(activity(), "root");
            assertSame(field(activity(), "playerOverlay"), root.getChildAt(root.getChildCount() - 1));
            assertEquals(View.GONE, ((View) field(activity(), "playerScreen")).getVisibility());
            activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            activity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
            assertSame(root, activity().getCurrentFocus());
            assertFalse(root.getDefaultFocusHighlightEnabled());
            client[0].onHideCustomView();
        });
        instrumentation().waitForIdleSync();
        main(() -> { assertFullscreen(); assertEquals(1, hidden.get());
            FrameLayout providerVideo = new FrameLayout(activity());
            providerVideo.setFocusableInTouchMode(true);
            providerVideo.setOnKeyListener((view, code, event) -> code == KeyEvent.KEYCODE_BACK);
            client[0].onShowCustomView(providerVideo, hidden::incrementAndGet);
            providerVideo.requestFocus(); // A provider cannot swallow our Back action.
        });
        key(KeyEvent.KEYCODE_BACK);
        main(() -> {
            assertClosed(true); assertEquals(2, hidden.get());
            // A late fullscreen request from the destroyed WebView cannot reopen it.
            client[0].onShowCustomView(new FrameLayout(activity()), hidden::incrementAndGet);
            assertClosed(true); assertEquals(3, hidden.get());
        });
    }
}
