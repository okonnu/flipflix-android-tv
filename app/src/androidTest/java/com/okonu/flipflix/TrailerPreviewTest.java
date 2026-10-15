package com.okonu.flipflix;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TrailerPreviewTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private TrailerPreview preview;
    private FrameLayout root;
    private FrameLayout chrome;
    private Button source;
    private WebView web;
    private final AtomicInteger clicks = new AtomicInteger();
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void set(Object object, String name, Object value) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void invoke(Object target, String name, Class<?>[] signature, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, signature); m.setAccessible(true); m.invoke(target, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void await(java.util.function.BooleanSupplier condition) {
        AtomicBoolean done = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 6000;
        while (!done.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> done.set(condition.getAsBoolean()));
            if (!done.get()) SystemClock.sleep(40);
        }
        assertTrue("Condition completes within six seconds", done.get());
    }
    private JSONObject video(String key, String type, boolean official, String language) throws Exception {
        return new JSONObject().put("key", key).put("type", type).put("site", "YouTube")
            .put("official", official).put("iso_639_1", language);
    }

    @Test public void selectsOfficialTrailersByIdThenTeasersWithoutGuessing() throws Exception {
        JSONArray videos = new JSONArray()
            .put(video("teaser00001", "Teaser", true, "en"))
            .put(video("trailer0001", "Trailer", false, "en"))
            .put(video("trailer0002", "Trailer", true, "fr"))
            .put(video("<script>bad", "Trailer", true, "en"))
            .put(video("trailer0003", "Trailer", true, "en"))
            .put(video("trailer0003", "Trailer", true, "en"))
            .put(video("clip0000001", "Clip", true, "en"));
        assertEquals(Arrays.asList("trailer0003", "teaser00001"),
            TrailerPreview.selectVideos(new JSONObject().put("results", videos)));
        assertTrue(TrailerPreview.selectVideos(null).isEmpty());
    }

    private void fixture() {
        main(() -> {
            if (preview != null) preview.close();
            // Invalidate pending catalog callbacks without using live trailer services.
            set(rule.getActivity(), "generation", (int) field(rule.getActivity(), "generation") + 1);
            root = (FrameLayout) field(rule.getActivity(), "root"); root.removeAllViews();
            root.addView(new View(rule.getActivity()), new FrameLayout.LayoutParams(-1, -1));
            chrome = new FrameLayout(rule.getActivity());
            source = new Button(rule.getActivity()); source.setText("Bolt"); source.setFocusableInTouchMode(true);
            source.setOnClickListener(v -> clicks.incrementAndGet());
            chrome.addView(source, new FrameLayout.LayoutParams(300, 100));
            root.addView(chrome, new FrameLayout.LayoutParams(-1, -1)); source.requestFocus();
            preview = new TrailerPreview(rule.getActivity(), root, chrome);
            set(rule.getActivity(), "trailerPreview", preview);
            invoke(preview, "load", new Class<?>[]{String.class}, "trailer0003");
            web = (WebView) field(preview, "web");
            assertNotNull(web);
            // Intercept only the fixture's YouTube API download. Production wrapper,
            // message bridge and Activity key handling remain unchanged.
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    if ("/child.html".equals(request.getUrl().getPath())) {
                        String child = "<script>FlipflixTrailer.postMessage(JSON.stringify({event:'state',value:1}));"
                            + "parent.postMessage('subframe-sent','" + TrailerPreview.ORIGIN + "');</script>";
                        return new WebResourceResponse("text/html", "UTF-8",
                            new ByteArrayInputStream(child.getBytes(StandardCharsets.UTF_8)));
                    }
                    String script = "window.fixtureEvents=null;window.fixtureCalls=[];window.YT={Player:function(id,opts){"
                        + "window.fixtureEvents=opts.events;var p={unMute:function(){fixtureCalls.push('unmute')},"
                        + "setVolume:function(v){fixtureCalls.push('volume:'+v)},"
                        + "pauseVideo:function(){fixtureCalls.push('pause')},"
                        + "playVideo:function(){fixtureCalls.push('play')}};window.fixturePlayer=p;"
                        + "opts.events.onReady({target:p});return p;}};"
                        + "window.onYouTubeIframeAPIReady();";
                    return new WebResourceResponse("application/javascript", "UTF-8",
                        new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
                }
            });
        });
        await(() -> rule.getActivity().hasWindowFocus());
        AtomicBoolean ready = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 6000;
        while (!ready.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> web.evaluateJavascript("JSON.stringify(window.fixtureCalls) === JSON.stringify(['unmute','volume:100','play'])",
                result -> ready.set("true".equals(result))));
            SystemClock.sleep(50);
        }
        assertTrue("Real wrapper unmutes and sets player volume before playback", ready.get());
    }
    private void state(int value) {
        main(() -> web.evaluateJavascript("fixtureEvents.onStateChange({data:" + value + "})", null));
    }
    private void shown() { state(1); await(() -> chrome.getVisibility() == View.INVISIBLE); }
    private boolean jsTrue(String expression) {
        AtomicBoolean result = new AtomicBoolean();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main(() -> web.evaluateJavascript(expression, value -> { result.set("true".equals(value)); done.countDown(); }));
        try { assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
        return result.get();
    }
    private String playCount(int expected) {
        return "fixtureCalls.filter(function(c){return c==='play'}).length === " + expected;
    }
    private void awaitPlays(int count) {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (jsTrue(playCount(count))) return;
            SystemClock.sleep(50);
        }
        fail("Expected " + count + " play requests");
    }

    @Test public void fadesOnlyAfterPlayingThenRestoresOnRemoteWithoutSelecting() {
        fixture();
        state(3); instrumentation().waitForIdleSync();
        main(() -> { assertEquals(View.VISIBLE, chrome.getVisibility()); assertEquals(1f, chrome.getAlpha(), 0f); });
        main(() -> assertNotNull(root.findViewWithTag("preview-loading")));
        shown();
        main(() -> {
            assertNull(root.findViewWithTag("preview-loading"));
            assertEquals(1f, web.getAlpha(), 0.01f); assertSame(web, rule.getActivity().getCurrentFocus());
            assertEquals(root.getWidth(), web.getWidth()); assertEquals(root.getHeight(), web.getHeight());
        });
        instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> web.getAlpha() == 0f && chrome.getAlpha() == 1f);
        main(() -> {
            assertEquals(View.VISIBLE, chrome.getVisibility()); assertEquals(1f, chrome.getAlpha(), 0f);
            assertSame(source, rule.getActivity().getCurrentFocus()); assertSame(web, field(preview, "web"));
        });
        assertEquals(0, clicks.get());
        instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, clicks.get());
    }

    @Test public void backAndHeldRemoteWakeOnlyOnce() {
        fixture(); shown();
        main(() -> {
            for (int i = 0; i < 5; i++) assertTrue(preview.handleKey(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, i)));
            assertTrue(preview.handleKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)));
            assertFalse(preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
            assertEquals(0, clicks.get());
        });
    }

    @Test public void volumeKeysStayWithTheTvAndDoNotDismissPreview() {
        fixture(); shown();
        main(() -> {
            for (int key : new int[]{KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE}) {
                assertFalse(preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
                assertFalse(preview.handleKey(new KeyEvent(KeyEvent.ACTION_UP, key)));
            }
            assertTrue((boolean) field(preview, "showing"));
            assertFalse((boolean) field(preview, "closed"));
        });
    }

    @Test public void navigationWhileBufferingIgnoresLatePlaybackUntilIdle() {
        fixture(); state(3);
        main(() -> {
            assertFalse(preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
            preview.onMessage("{\"event\":\"state\",\"value\":1}");
            assertEquals(View.VISIBLE, chrome.getVisibility()); assertEquals(1f, chrome.getAlpha(), 0f);
            assertSame(web, field(preview, "web"));
            assertTrue((boolean) field(preview, "waitingForIdle"));
        });
        main(() -> web.evaluateJavascript("fixtureEvents.onReady({target:fixturePlayer})", null));
        assertTrue(jsTrue(playCount(1))); // Late readiness does not autoplay over controls.
        main(() -> preview.handleKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)));
        awaitPlays(2);
        shown();
    }

    @Test public void failedAndBlockedTrailersRestoreArtworkWithoutRetryLoops() {
        for (String event : new String[]{"{\"event\":\"error\",\"value\":153}", "{\"event\":\"blocked\"}"}) {
            fixture();
            main(() -> web.evaluateJavascript("FlipflixTrailer.postMessage(" + JSONObject.quote(event) + ")", null));
            await(() -> field(preview, "web") == null && root.findViewWithTag("preview-loading") == null);
            main(() -> { assertNull(root.findViewWithTag("preview-loading"));
                assertEquals(View.VISIBLE, chrome.getVisibility()); assertEquals(1f, chrome.getAlpha(), 0f);
                assertFalse(((android.os.Handler) field(preview, "ui")).hasCallbacks((Runnable) field(preview, "idle"))); });
        }
    }

    @Test public void idleResumesTheSamePlayerThreeSecondsAfterTheLastPress() {
        fixture(); shown();
        instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(1600);
        assertTrue(jsTrue(playCount(1)));
        instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        SystemClock.sleep(1600);
        assertTrue("A later key resets the entire three-second delay", jsTrue(playCount(1)));
        awaitPlays(2);
        main(() -> {
            assertSame(web, field(preview, "web"));
            assertFalse((boolean) field(preview, "showing")); // Still waits for actual PLAYING.
        });
        assertEquals(0, clicks.get());
        shown();
        instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        awaitPlays(3); // Not a one-shot timer: another interaction resumes again.
        shown();
    }

    @Test public void holdingARemoteButtonCannotRestartThePreview() {
        fixture(); shown();
        main(() -> preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        SystemClock.sleep(3300);
        assertTrue(jsTrue(playCount(1)));
        main(() -> preview.handleKey(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 3)));
        main(() -> preview.handleKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)));
        SystemClock.sleep(1600);
        assertTrue(jsTrue(playCount(1)));
        awaitPlays(2);
    }

    @Test public void finishedTrailerReplaysAfterIdleWithoutReloading() {
        fixture(); shown(); state(0);
        await(() -> chrome.getAlpha() == 1f && !(boolean) field(preview, "showing"));
        assertTrue(jsTrue(playCount(1)));
        awaitPlays(2);
        main(() -> assertSame(web, field(preview, "web")));
        shown();
    }

    @Test public void dialogsSuspendIdleAndLeavingThePageCancelsIt() {
        fixture(); shown();
        main(() -> {
            preview.onWindowFocusChanged(false);
            assertFalse(((android.os.Handler) field(preview, "ui")).hasCallbacks((Runnable) field(preview, "idle")));
            ((Runnable) field(preview, "idle")).run(); // Even a stale runnable cannot steal focus.
        });
        assertTrue(jsTrue(playCount(1)));
        main(() -> preview.onWindowFocusChanged(true));
        SystemClock.sleep(1600);
        assertTrue(jsTrue(playCount(1)));
        awaitPlays(2);
        shown();
        main(() -> {
            preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
            preview.handleKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
            assertTrue(((android.os.Handler) field(preview, "ui")).hasCallbacks((Runnable) field(preview, "idle")));
            preview.close();
            assertFalse(((android.os.Handler) field(preview, "ui")).hasCallbacks((Runnable) field(preview, "idle")));
            ((Runnable) field(preview, "idle")).run();
            assertNull(field(preview, "web"));
        });
    }

    @Test public void interactionDefersInitialLookupAndItsLateResponse() throws Exception {
        fixture();
        AtomicInteger lookups = new AtomicInteger();
        CatalogApi.Result[] response = new CatalogApi.Result[1];
        CatalogApi api = new CatalogApi() {
            @Override void object(String path, Result result) { lookups.incrementAndGet(); response[0] = result; }
        };
        try {
            main(() -> {
                preview.close();
                preview = new TrailerPreview(rule.getActivity(), root, chrome);
                set(rule.getActivity(), "trailerPreview", preview);
                preview.start(api, "movie", 550);
            });
            SystemClock.sleep(1600);
            instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            SystemClock.sleep(1600);
            assertEquals(0, lookups.get());
            await(() -> lookups.get() == 1);
            JSONObject videos = new JSONObject().put("results", new JSONArray().put(video("trailer0003", "Trailer", true, "en")));
            main(() -> {
                preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                response[0].done(videos, null);
                assertNull(field(preview, "web"));
                preview.handleKey(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                preview.close();
                ((Runnable) field(preview, "idle")).run();
                assertNull(field(preview, "web"));
            });
            assertEquals(1, lookups.get());
        } finally { api.close(); }
    }

    @Test public void timeoutAndActivityPauseCancelFadesAndReleaseThePlayer() {
        fixture();
        main(() -> ((Runnable) field(preview, "timeout")).run());
        main(() -> assertNull(field(preview, "web")));
        fixture(); shown();
        main(() -> invoke(rule.getActivity(), "onPause", new Class<?>[]{}));
        main(() -> {
            assertNull(field(preview, "web")); assertNull(field(rule.getActivity(), "trailerPreview"));
            assertEquals(View.VISIBLE, chrome.getVisibility()); assertEquals(1f, chrome.getAlpha(), 0f);
        });
    }

    @Test public void subframesCannotSpoofPlaybackReadiness() {
        fixture();
        main(() -> web.evaluateJavascript("window.addEventListener('message',function(e){if(e.data==='subframe-sent')window.subframeSent=true;});"
            + "var f=document.createElement('iframe');f.src='" + TrailerPreview.ORIGIN + "/child.html';document.body.appendChild(f);", null));
        AtomicBoolean sent = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 3000;
        while (!sent.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> web.evaluateJavascript("window.subframeSent === true", result -> sent.set("true".equals(result))));
            SystemClock.sleep(50);
        }
        assertTrue("Subframe actually sent the message", sent.get());
        SystemClock.sleep(250);
        main(() -> { assertFalse((boolean) field(preview, "showing")); assertEquals(View.VISIBLE, chrome.getVisibility()); });
        shown(); // The same message from the trusted main document works.
    }

    @Test public void bothDirectionsAnimateAndPageDisposalCancelsPendingFades() {
        fixture();
        state(1);
        await(() -> chrome.getAlpha() > 0.05f && chrome.getAlpha() < 0.95f);
        await(() -> chrome.getVisibility() == View.INVISIBLE);
        main(() -> preview.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        await(() -> chrome.getAlpha() > 0.05f && chrome.getAlpha() < 0.95f);
        main(() -> {
            assertNotNull(field(preview, "web")); // Still crossfading, not a hard cut.
            preview.close(); // Leaving the page must immediately finish the fade and release it.
            assertNull(field(preview, "web")); assertEquals(1f, chrome.getAlpha(), 0f);
            assertEquals(View.VISIBLE, chrome.getVisibility());
        });
        SystemClock.sleep(500);
        main(() -> assertEquals(View.VISIBLE, chrome.getVisibility()));
    }

    @Test public void liveYouTubeProbe() {
        String id = InstrumentationRegistry.getArguments().getString("liveTrailer");
        org.junit.Assume.assumeTrue("Opt-in external service diagnostic, not a deterministic regression", id != null);
        assertTrue(id.matches("[A-Za-z0-9_-]{11}"));
        main(() -> {
            set(rule.getActivity(), "generation", (int) field(rule.getActivity(), "generation") + 1);
            root = (FrameLayout) field(rule.getActivity(), "root"); root.removeAllViews();
            root.addView(new View(rule.getActivity()), new FrameLayout.LayoutParams(-1, -1));
            chrome = new FrameLayout(rule.getActivity());
            root.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
            preview = new TrailerPreview(rule.getActivity(), root, chrome);
            set(rule.getActivity(), "trailerPreview", preview);
            invoke(preview, "load", new Class<?>[]{String.class}, id);
        });
        long deadline = SystemClock.uptimeMillis() + 23000;
        AtomicBoolean complete = new AtomicBoolean();
        while (!complete.get() && SystemClock.uptimeMillis() < deadline) {
            main(() -> complete.set((boolean) field(preview, "showing") || (boolean) field(preview, "closed")));
            SystemClock.sleep(200);
        }
        main(() -> android.util.Log.i("TrailerLiveProbe", "showing=" + field(preview, "showing")
            + " closed=" + field(preview, "closed") + " lastEvent=" + field(preview, "lastEvent")));
        if ("true".equals(InstrumentationRegistry.getArguments().getString("requireLiveAudio"))) {
            main(() -> {
                assertTrue("Live YouTube preview starts playing", (boolean) field(preview, "showing"));
                try {
                    JSONObject event = new JSONObject((String) field(preview, "lastEvent"));
                    assertEquals(1, event.getInt("value"));
                    assertFalse("YouTube reports sound enabled", event.getBoolean("muted"));
                    assertEquals(100, event.getInt("volume"));
                } catch (org.json.JSONException error) { throw new AssertionError(error); }
            });
        }
        if ("true".equals(InstrumentationRegistry.getArguments().getString("requireLiveResume"))) {
            WebView[] original = new WebView[1];
            main(() -> {
                assertTrue((boolean) field(preview, "showing"));
                original[0] = (WebView) field(preview, "web");
            });
            instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(1600);
            main(() -> assertFalse("Controls remain available before the idle timeout", (boolean) field(preview, "showing")));
            await(() -> (boolean) field(preview, "showing"));
            main(() -> {
                assertSame("Live resume reuses the player", original[0], field(preview, "web"));
                android.util.Log.i("TrailerLiveProbe", "resumed=true samePlayer=true lastEvent=" + field(preview, "lastEvent"));
            });
        }
    }

    @After public void cleanup() { if (preview != null) main(preview::close); }
}
