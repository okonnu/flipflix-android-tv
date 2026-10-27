package com.okonu.flipflix;

import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Exercises the real Activity and cross-origin provider protocol, not injected Java state. */
@RunWith(AndroidJUnit4.class)
public final class BoltHandoffTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
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
        AtomicBoolean done = new AtomicBoolean(); long end = SystemClock.uptimeMillis() + 8000;
        while (!done.get() && SystemClock.uptimeMillis() < end) { main(() -> done.set(check.ready())); SystemClock.sleep(50); }
        assertTrue(message, done.get());
    }
    @Test public void boltMessagesSurviveSwitchToRiftAndPersistHistory() throws Exception {
        JSONObject catalog = new JSONObject().put("id", 45745).put("media_type", "movie").put("title", "Protocol fixture");
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, catalog, "movie");
        main(() -> {
            rule.getActivity().getSharedPreferences("watch_history", 0).edit().clear().commit();
            invoke("showPlayerAtPosition", item, 1, 1, 0, -1L);
            WebView web = (WebView) field("player"); web.stopLoading();
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    String host = request.getUrl().getHost();
                    String fixture = "vidbolt.xyz".equals(host) ? BOLT : RIFT;
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(fixture.getBytes(StandardCharsets.UTF_8)));
                }
            });
            invoke("loadSource", item, 0, 1, 1);
        });
        try {
            await("Bolt's documented timeupdate must reach the app even without an exposed HTML video", () ->
                ((PlaybackBackend) invoke("playbackBackend")).state().position == 225000);
            main(() -> {
                invoke("loadSource", item, 1, 1, 1);
                assertEquals(225000, ((PlaybackRecovery) field("recovery")).restartPosition());
            });
            await("Rift must acknowledge Bolt's saved position", () -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                return ((PlaybackBackend) invoke("playbackBackend")).state().position == 225000;
            });
            main(() -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                WatchHistory store = new WatchHistory(rule.getActivity().getSharedPreferences("watch_history", 0));
                assertNotNull(store.get("movie:45745"));
                assertEquals(225000, store.get("movie:45745").resumePosition());
                assertEquals(1, store.get("movie:45745").source);
                ((PlaybackBackend) invoke("playbackBackend")).command("seek", "300000");
            });
            await("Rift advanced before switching back", () -> ((PlaybackBackend) invoke("playbackBackend")).state().position == 300000);
            main(() -> invoke("loadSource", item, 0, 1, 1));
            await("Switching back to Bolt restores the newer shared checkpoint", () -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                return ((PlaybackBackend) invoke("playbackBackend")).state().position == 300000;
            });
            main(() -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                invoke("closePlayer");
                WatchHistory store = new WatchHistory(rule.getActivity().getSharedPreferences("watch_history", 0));
                assertEquals(300000, store.get("movie:45745").resumePosition());
                assertEquals(0, store.get("movie:45745").source);
            });
        } finally { main(() -> { invoke("closePlayer"); rule.getActivity().getSharedPreferences("watch_history", 0).edit().clear().commit(); }); }
    }
    // Documented Bolt postMessage contract. No queryable video in this document.
    private static final String BOLT = "<html><body><script>var t=Number(new URL(location.href).searchParams.get('startAt')||225);"
        + "function emit(x){parent.postMessage(x,'*')}"
        + "addEventListener('message',function(e){if(e.data.type==='seek'){t=e.data.time;emit({type:'timeupdate',time:t})}});"
        + "addEventListener('load',function(){emit({type:'ready'});emit({type:'durationchange',duration:900});"
        + "emit({type:'play'});emit({type:'timeupdate',time:t})});</script></body></html>";
    private static final String RIFT = "<html><body><script>var t=0;function emit(){parent.postMessage({type:'vidrift:state',"
        + "tmdbId:45745,mediaType:'movie',currentTime:t,duration:900,paused:false},'*')}"
        + "addEventListener('message',function(e){if(e.data.type==='vidrift:resume'){t=e.data.currentTime;emit()}"
        + "if(e.data.type==='vidrift:get-state')emit()});</script></body></html>";
}
