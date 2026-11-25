package com.okonu.flipflix;

import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.os.SystemClock;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.util.Collections;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WebPlaybackFailureTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Object field(String name) {
        try { java.lang.reflect.Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(rule.getActivity()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private Object invoke(String name, Object... args) {
        try {
            for (java.lang.reflect.Method m : MainActivity.class.getDeclaredMethods()) if (m.getName().equals(name)) {
                m.setAccessible(true); return m.invoke(rule.getActivity(), args);
            }
            throw new NoSuchMethodException(name);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private Object item() throws Exception {
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        return from.invoke(null, new JSONObject().put("id", 45745).put("media_type", "movie").put("title", "Sintel"), "movie");
    }
    private TextView text(View view, String label) {
        if (view instanceof TextView && view.isShown() && label.equals(((TextView) view).getText().toString())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = text(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private WebResourceRequest request(String url) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return false; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public Map<String,String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }
    private void fail(WebView web, WebViewClient client, String url) {
        client.onReceivedHttpError(web, request(url), new WebResourceResponse("text/html", "UTF-8", 503,
            "Unavailable", Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0])));
    }
    private void key(int code) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); SystemClock.sleep(150); }
    private void capture(String name) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureUi"))) return;
        SystemClock.sleep(350);
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream("/sdcard/Download/" + name + ".png")) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
    }
    @Test public void failedProviderFrameShowsRecoveryInsteadOfBlankScreen() throws Exception {
        Object item = item();
        main(() -> {
            invoke("showPlayer", item, 1, 1, 4);
            WebView web = (WebView) field("player"); web.stopLoading();
            fail(web, web.getWebViewClient(), "https://embed.vidrift.in/embed/movie/45745?fs=parent");
            View root = (View) field("root");
            assertNotNull("A failed provider iframe must show a useful error, not the Android broken-page icon",
                text(root, "This source is unavailable"));
            assertNotNull(text(root, "Retry"));
            assertNotNull(text(root, "Change source"));
            assertNotNull(text(root, "Back"));
        });
        capture("darkflix-source-error");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        main(() -> assertEquals("Change source", ((TextView) rule.getActivity().getCurrentFocus()).getText().toString()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        capture("darkflix-source-picker");
        key(KeyEvent.KEYCODE_BACK);
        main(() -> assertTrue(((PlaybackFeedback) field("playbackFeedback")).hasFocus()));
        key(KeyEvent.KEYCODE_BACK);
        main(() -> assertFalse((Boolean) field("playerOpen")));
    }
    @Test public void ignoresAuxiliaryErrorsAndStaleSourceCallbacks() throws Exception {
        Object item = item();
        main(() -> {
            invoke("showPlayer", item, 1, 1, 4);
            WebView web = (WebView) field("player"); web.stopLoading(); WebViewClient old = web.getWebViewClient();
            fail(web, old, "https://embed.vidrift.in/favicon.ico");
            assertFalse(((PlaybackFeedback) field("playbackFeedback")).failed());
            Runnable oldDeadline = (Runnable) field("webStartupDeadline");
            invoke("loadSource", item, 3, 1, 1); web.stopLoading();
            fail(web, old, "https://vidbolt.xyz/movie/45745"); oldDeadline.run();
            assertFalse(((PlaybackFeedback) field("playbackFeedback")).failed());
            invoke("closePlayer"); oldDeadline.run();
            assertNull(field("playbackFeedback"));
        });
    }
    @Test public void timeoutAllowsReturnAndRetryCreatesFreshPlayer() throws Exception {
        Object item = item(); final WebView[] before = new WebView[1];
        main(() -> {
            invoke("showPlayer", item, 1, 1, 4); before[0] = (WebView) field("player"); before[0].stopLoading();
            ((Runnable) field("webStartupDeadline")).run();
            assertNotNull(text((View) field("root"), "Still waiting for this source"));
            assertNotNull(text((View) field("root"), "Return to player"));
        });
        capture("darkflix-web-timeout");
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> {
            assertNotSame(before[0], field("player"));
            assertEquals(4, field("currentSourceIndex"));
            // A new real request can fail immediately on a filtered network.
            // It must not retain the previous attempt's timeout screen.
            assertNull(text((View) field("root"), "Still waiting for this source"));
            invoke("closePlayer");
        });
    }
    @Test public void liveProviderFailureIsExplained() throws Exception {
        String choice = InstrumentationRegistry.getArguments().getString("failureChoice");
        org.junit.Assume.assumeTrue(choice != null);
        Object item = item();
        main(() -> invoke("showPlayer", item, 1, 1, Integer.parseInt(choice)));
        java.util.concurrent.atomic.AtomicBoolean failure = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 50000;
        while (!failure.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> failure.set(field("playbackFeedback") != null && ((PlaybackFeedback) field("playbackFeedback")).failed())); SystemClock.sleep(100);
        }
        assertTrue("Live failure must produce recoverable native UI", failure.get());
        capture("darkflix-live-failure-" + choice);
        main(() -> invoke("closePlayer"));
    }
    @Test public void rendererLossDisposesTheWebViewAndRetryCreatesANewSession() throws Exception {
        Object item = item(); final WebView[] before = new WebView[1];
        main(() -> {
            invoke("showPlayer", item, 1, 1, 4); before[0] = (WebView) field("player"); before[0].stopLoading();
            assertTrue(before[0].getWebViewClient().onRenderProcessGone(before[0], new android.webkit.RenderProcessGoneDetail() {
                @Override public boolean didCrash() { return true; }
                @Override public int rendererPriorityAtExit() { return WebView.RENDERER_PRIORITY_IMPORTANT; }
            }));
            assertNull(field("player")); assertNull(field("webStartupDeadline")); assertNull(field("nativeDiscoveryDeadline"));
            assertNotNull(text((View) field("root"), "The web player closed"));
        });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> {
            assertNotNull(field("player")); assertNotSame(before[0], field("player"));
            assertEquals(4, field("currentSourceIndex")); invoke("closePlayer");
        });
    }
    @Test public void mediaErrorShowsRecoveryAndHealthyStateRestoresWebInput() throws Exception {
        Object item = item();
        main(() -> {
            invoke("showPlayer", item, 1, 1, 5);
            WebView web = (WebView) field("player"); web.stopLoading();
            EmbeddedPlayback backend = (EmbeddedPlayback) field("embeddedPlayer");
            backend.state().error = 3; backend.onStateChanged.run();
            assertNotNull(text((View) field("root"), "This video couldn't play"));
            assertEquals(View.INVISIBLE, web.getVisibility());
            backend.state().error = 0; backend.state().available = true; backend.onStateChanged.run();
            assertEquals(View.GONE, ((View) field("playbackFeedback")).getVisibility());
            assertEquals(View.VISIBLE, web.getVisibility()); assertNull(field("webStartupDeadline"));
            assertTrue((Boolean) field("providerInput")); invoke("closePlayer");
        });
    }
}
