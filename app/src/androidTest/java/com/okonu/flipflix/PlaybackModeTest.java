package com.okonu.flipflix;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.webkit.WebViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Activity choices, cross-origin web input and shared movie progress. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 26)
public final class PlaybackModeTest {
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
    private interface Check { boolean ready(); }
    private void await(String message, Check check) {
        AtomicBoolean done = new AtomicBoolean(); long until = SystemClock.uptimeMillis() + 15000;
        while (!done.get() && SystemClock.uptimeMillis() < until) { main(() -> done.set(check.ready())); SystemClock.sleep(60); }
        assertTrue(message, done.get());
    }
    private Object item() throws Exception {
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        return from.invoke(null, new JSONObject().put("id", 9933001).put("media_type", "movie").put("title", "Playback mode fixture"), "movie");
    }
    public static final class InputProbe {
        final AtomicInteger keys = new AtomicInteger(), clicks = new AtomicInteger();
        @JavascriptInterface public void key() { keys.incrementAndGet(); }
        @JavascriptInterface public void click() { clicks.incrementAndGet(); }
    }
    private void fixture(InputProbe input) {
        WebView web = (WebView) field("player"); web.stopLoading();
        WebViewClient production = web.getWebViewClient();
        web.addJavascriptInterface(input, "TestInput");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(PROVIDER.getBytes(StandardCharsets.UTF_8)));
            }
            @Override public void onPageFinished(WebView view, String url) { production.onPageFinished(view, url); }
        });
    }
    private void key(int code) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void capture(String name) {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureUi"))) return;
        android.graphics.Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(shot);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream("/sdcard/Download/" + name + ".png")) {
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        finally { shot.recycle(); }
    }

    @Test public void webMenuAndSourceChooserAreRemoteReachable() throws Exception {
        Object item = item();
        main(() -> { invoke("showPlayer", item, 1, 1, 0); fixture(new InputProbe()); invoke("loadSource", item, 4, 1, 1); });
        try {
            await("Web input ready", () -> ((WebView) field("player")).hasFocus()
                && ((PlaybackBackend) invoke("playbackBackend")).state().available);
            for (int i = 0; i < 2; i++) {
                key(KeyEvent.KEYCODE_MENU);
                await("Web menu has window focus", () -> field("webActionsDialog") != null
                    && ((AlertDialog) field("webActionsDialog")).getWindow().getDecorView().hasWindowFocus());
                capture("darkflix-web-actions");
                key(KeyEvent.KEYCODE_BACK);
                await("Return to web input", () -> field("webActionsDialog") == null && rule.getActivity().hasWindowFocus());
            }
            key(KeyEvent.KEYCODE_BACK);
            await("Back opens web options", () -> field("webActionsDialog") != null
                && ((AlertDialog) field("webActionsDialog")).getWindow().getDecorView().hasWindowFocus());
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
            await("Source picker receives focus", () -> field("playerSourceDialog") != null
                && ((AlertDialog) field("playerSourceDialog")).getWindow().getDecorView().hasWindowFocus());
            capture("darkflix-source-modes");
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_CENTER);
            await("Remote selection explicitly switches Rift Web to Native", () -> (Integer) field("currentSourceIndex") == 1 && (Boolean) field("nativeOnlySource"));
            main(() -> { assertFalse((Boolean) field("providerInput")); assertNotNull(field("streamDiscovery")); });
        } finally { main(() -> invoke("closePlayer")); }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Capture the newly laid-out detail page, not the outgoing player frame.
        SystemClock.sleep(400);
        capture("darkflix-detail-modes");
    }

    @Test public void liveWebProbe() throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        org.junit.Assume.assumeTrue("Explicit live provider check only", args.containsKey("webChoice"));
        int choice = Integer.parseInt(args.getString("webChoice"));
        assertTrue(choice >= 3 && choice < 6);
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        int movieId = Integer.parseInt(args.getString("webMovie", "45745"));
        Object item = from.invoke(null, new JSONObject().put("id",movieId).put("media_type","movie")
            .put("title", args.getString("webTitle", movieId == 45745 ? "Sintel" : "Live playback check")), "movie");
        main(() -> invoke("showPlayer", item, 1, 1, choice));
        try {
            AtomicBoolean playing = new AtomicBoolean(); long deadline = SystemClock.uptimeMillis() + 90000;
            while (!playing.get() && SystemClock.uptimeMillis() < deadline) {
                main(() -> {
                    PlaybackBackend.State state = ((PlaybackBackend) invoke("playbackBackend")).state();
                    playing.set(state.available && state.position > 1000 && state.duration > 60000);
                });
                SystemClock.sleep(500);
            }
            assertTrue("Selected live provider reports video progress in Web mode", playing.get());
            main(() -> { assertNull(field("nativePlayer")); assertNull(field("streamDiscovery")); assertTrue((Boolean) field("providerInput")); });
            key(KeyEvent.KEYCODE_DPAD_UP); capture("darkflix-live-web-" + choice);
            key(KeyEvent.KEYCODE_MENU);
            await("Live provider cannot trap the remote", () -> field("webActionsDialog") != null);
        } finally { main(() -> invoke("closePlayer")); }
    }

    @Test public void choicesHaveStableProviderAndModeIds() {
        assertArrayEquals(new int[]{0,3,1,4,2,5}, PlaybackChoice.displayOrder());
        for (int provider = 0; provider < 3; provider++) {
            assertTrue(PlaybackChoice.nativeMode(provider)); assertFalse(PlaybackChoice.nativeMode(provider + 3));
            assertEquals(provider, PlaybackChoice.provider(provider + 3));
            assertEquals(provider + 3, PlaybackChoice.normalize(provider + 3));
        }
        assertEquals(0, PlaybackChoice.normalize(99));
    }

    @Test public void nativeChoicesNeverFallBackAndWebChoicesReceiveRemoteInput() throws Exception {
        Object item = item(); InputProbe input = new InputProbe();
        main(() -> { invoke("showPlayer", item, 1, 1, 0); fixture(input); });
        try {
            for (int provider = 0; provider < 3; provider++) {
                final int id = provider;
                main(() -> {
                    invoke("loadSource", item, id, 1, 1);
                    assertTrue((Boolean) field("nativeOnlySource")); assertFalse((Boolean) field("providerInput"));
                    assertNotNull(field("streamDiscovery"));
                    assertTrue(WebViewCompat.isAudioMuted((WebView) field("player")));
                    ((Runnable) field("nativeDiscoveryDeadline")).run();
                    assertEquals(id, field("currentSourceIndex"));
                    assertNull(field("boltPlayer")); assertNull(field("riftPlayer")); assertNull(field("nativePlayer"));
                    assertFalse(((PlaybackBackend) invoke("playbackBackend")).state().available);
                    invoke("loadSource", item, id + 3, 1, 1);
                    assertFalse((Boolean) field("nativeOnlySource")); assertTrue((Boolean) field("providerInput"));
                    assertNull(field("streamDiscovery")); assertNull(field("nativeDiscoveryDeadline"));
                    // Even a queued direct URL must not override an explicit Web selection.
                    invoke("handoffToNative", item, id + 3, 1, 1,
                        new StreamDiscovery.Stream("https://example.com/direct.mp4", "https://example.com/", "test", null, 900000), field("sourceRequest"), 0L);
                    assertNull(field("nativePlayer"));
                });
                await("Web mode is visible, unmuted, focused and has no app control overlay", () -> {
                    WebView web = (WebView) field("player");
                    return web.hasFocus() && !WebViewCompat.isAudioMuted(web)
                        && ((PlaybackBackend) invoke("playbackBackend")).state().available
                        && ((View) field("playerOverlay")).getVisibility() == View.GONE;
                });
                int keys = input.keys.get(), clicks = input.clicks.get();
                key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
                await("Provider iframe receives real D-pad and OK input", () -> input.keys.get() > keys && input.clicks.get() > clicks);
                key(KeyEvent.KEYCODE_MENU);
                await("Menu opens web actions without closing the video", () -> field("webActionsDialog") != null
                    && ((AlertDialog) field("webActionsDialog")).getWindow().getDecorView().hasWindowFocus());
                key(KeyEvent.KEYCODE_BACK);
                await("Back dismisses actions and restores provider input", () -> field("webActionsDialog") == null
                    && (Boolean) field("playerOpen") && ((WebView) field("player")).hasFocus());
            }
            main(() -> {
                invoke("loadSource", item, 0, 1, 1);
                invoke("handoffToNative", item, 0, 1, 1,
                    new StreamDiscovery.Stream("file:///missing-native-source.mp4", "https://example.com/", "test", null, 0), field("sourceRequest"), 0L);
            });
            await("Native error is shown instead of auto-switching Bolt to Web", () -> field("nativePlayer") != null
                && ((android.widget.TextView) field("playerStatus")).getText().toString().contains("Native playback unavailable"));
            main(() -> { assertNull(field("boltPlayer")); assertEquals(0, field("currentSourceIndex")); assertFalse((Boolean) field("providerInput")); });
        } finally { main(() -> invoke("closePlayer")); }
    }

    @Test public void webModesShareProgressAndHistoryRetainsSelectedMode() throws Exception {
        Object item = item();
        main(() -> { invoke("showPlayer", item, 1, 1, 0); fixture(new InputProbe()); });
        try {
            for (int choice : new int[]{3,4,5}) {
                main(() -> invoke("loadSource", item, choice, 1, 1));
                await("Selected web backend reports progress", () -> {
                    PlaybackBackend.State state = ((PlaybackBackend) invoke("playbackBackend")).state();
                    ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                    return state.available && state.position == 225000;
                });
                main(() -> {
                    ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                    ((PlaybackRecovery) field("recovery")).save();
                });
                await("History retains Web choice and shared position", () -> {
                    WatchHistory.Entry saved = ((WatchHistory) field("watchHistory")).get("movie:9933001");
                    ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                    return saved != null && saved.source == choice && saved.resumePosition() == 225000;
                });
                main(() -> {
                    invoke("loadSource", item, choice - 3, 1, 1);
                    assertEquals(225000, ((PlaybackRecovery) field("recovery")).restartPosition());
                });
            }
        } finally { main(() -> { invoke("closePlayer"); rule.getActivity().getSharedPreferences("watch_history", 0).edit().remove("movie:9933001").commit(); }); }
    }

    private static final String PROVIDER = "<html><body style='background:#10131a;color:white'><h1>Provider controls fixture</h1>"
        + "<button id='play'>Play / pause</button><button id='quality' onclick='TestInput.click()'>Subtitles / quality</button><video id='v'></video><script>"
        + "var v=document.getElementById('v'),t=225;Object.defineProperties(v,{duration:{value:900},readyState:{value:1},paused:{value:false},"
        + "currentSrc:{value:'https://example.com/direct.mp4'},seekable:{value:{length:1,start:function(){return 0},end:function(){return 900}}},"
        + "currentTime:{get:function(){return t},set:function(x){t=x;v.dispatchEvent(new Event('seeked'))}}});"
        + "function report(){parent.postMessage({type:'vidrift:state',tmdbId:9933001,mediaType:'movie',currentTime:t,duration:900,paused:false},'*');"
        + "parent.postMessage({type:'ready'},'*');parent.postMessage({type:'durationchange',duration:900},'*');parent.postMessage({type:'timeupdate',time:t},'*');}"
        + "addEventListener('message',function(e){if(e.data.type==='seek')t=e.data.time;if(e.data.type==='vidrift:resume')t=e.data.currentTime;report()});"
        + "addEventListener('keydown',function(e){TestInput.key();if(e.keyCode===39){document.getElementById('quality').focus();e.preventDefault()}});"
        + "addEventListener('load',function(){document.getElementById('play').focus();v.dispatchEvent(new Event('loadedmetadata'));report()});"
        + "</script></body></html>";
}
