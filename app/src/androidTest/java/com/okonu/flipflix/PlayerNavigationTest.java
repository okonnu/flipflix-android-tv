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
import androidx.media3.common.Player;
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
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
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
        // Media3 has its own disabled controller children; only inspect app chrome.
        if (view instanceof androidx.media3.ui.PlayerView || view instanceof WebView) return 0;
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
        String focused = "";
        while (SystemClock.uptimeMillis() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation().getUiAutomation().getRootInActiveWindow();
            android.view.accessibility.AccessibilityNodeInfo input = root == null ? null : root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            focused = input == null ? "" : String.valueOf(input.getText());
            if (text.equals(focused)) return;
            SystemClock.sleep(50);
        }
        assertEquals("Source dialog has focus before the next remote press", text, focused);
    }

    @Test public void backReturnsDirectlyToMovieOrSeriesDetails() throws Exception {
        for (String kind : new String[]{"movie", "tv"}) {
            Object item = item(kind); start(item);
            main(this::assertFullscreen);
            key(KeyEvent.KEYCODE_BACK);
            main(() -> { assertClosed(false); assertSame(item, field(activity(), "selected")); });
        }
    }

    @Test public void episodeSourceRetryAndCloseStayInOnePlayer() throws Exception {
        start(item("tv"));
        select("Next episode"); main(this::stopEmbedNetwork);
        instrumentation().waitForIdleSync();
        main(() -> { assertFullscreen(); assertEquals(4, field(activity(), "episode")); });
        select("Previous episode"); main(this::stopEmbedNetwork);
        instrumentation().waitForIdleSync();
        main(() -> { assertFullscreen(); assertEquals(3, field(activity(), "episode")); });
        select("Source: Bolt");
        awaitDialogFocus("Bolt");
        key(KeyEvent.KEYCODE_BACK); // Dismiss source menu, not playback.
        main(this::assertFullscreen);
        select("Source: Bolt");
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
            invoke("playNativeRift", item, 1, 2, 3, fixture.toURI().toString(), "https://example.com/", null, field(activity(), "sourceRequest"));
            nativeVideo[0] = (NativePlayback) field(activity(), "nativePlayer");
        });
        AtomicBoolean ready = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 12000;
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> ready.set(nativeVideo[0].player.getPlaybackState() == Player.STATE_READY)); SystemClock.sleep(100);
        }
        assertTrue("Fixture video plays through the real Activity", ready.get());
        main(() -> {
            assertFullscreen(); FrameLayout root = (FrameLayout) field(activity(), "root");
            assertEquals(root.getWidth(), nativeVideo[0].view.getWidth());
            assertEquals(root.getHeight(), nativeVideo[0].view.getHeight());
        });
        key(KeyEvent.KEYCODE_BACK);
        main(() -> { assertClosed(false); assertNull(nativeVideo[0].view.getPlayer()); assertNull(nativeVideo[0].view.getParent()); });
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
