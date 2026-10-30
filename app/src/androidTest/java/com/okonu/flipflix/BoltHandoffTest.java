package com.okonu.flipflix;

import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.webkit.WebViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real Activity policy: Rift resolves silently; Bolt can recover into its web player. */
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
    @Test @SdkSuppress(minSdkVersion = 26)
    public void riftStaysNativeOnlyButBoltRestoresWebAudioAndResumeAfterNativeFailure() throws Exception {
        JSONObject catalog = new JSONObject().put("id", 45745).put("media_type", "movie").put("title", "Protocol fixture");
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, catalog, "movie");
        AtomicBoolean loaded = new AtomicBoolean();
        AtomicReference<String> finishedPage = new AtomicReference<>("");
        main(() -> {
            rule.getActivity().getSharedPreferences("watch_history", 0).edit().clear().commit();
            PlaybackBackend.State saved = new PlaybackBackend.State();
            saved.available = true; saved.seekable = true; saved.position = 300000; saved.duration = 900000;
            ((WatchHistory) field("watchHistory")).save("movie:45745", catalog, 1, 1, 0, saved);
            invoke("showPlayer", item, 1, 1, 1);
            WebView web = (WebView) field("player"); web.stopLoading();
            WebViewClient productionClient = web.getWebViewClient();
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    String fixture = "vidbolt.xyz".equals(request.getUrl().getHost()) ? BOLT : RIFT;
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(fixture.getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView v, String url) {
                    productionClient.onPageFinished(v, url); loaded.set(true);
                    finishedPage.set(url);
                }
            });
            invoke("loadSource", item, 1, 1, 1);
        });
        try {
            await("Rift fixture loads", loaded::get);
            main(() -> {
                WebView web = (WebView) field("player");
                assertTrue(WebViewCompat.isAudioMuted(web));
                assertEquals(0f, web.getAlpha(), 0f); assertFalse(web.isFocusable());
                assertFalse(((PlaybackBackend) invoke("playbackBackend")).state().available);
                assertNull(field("riftPlayer"));
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                assertEquals(300000, ((PlaybackRecovery) field("recovery")).restartPosition());
                int old = (Integer) field("sourceRequest");
                Runnable expired = (Runnable) field("nativeDiscoveryDeadline");
                assertNotNull(expired); expired.run();
                assertNull(field("nativeDiscoveryDeadline")); assertNull(field("streamDiscovery"));
                assertEquals(View.GONE, web.getVisibility());
                invoke("loadSource", item, 1, 1, 1);
                int current = (Integer) field("sourceRequest");
                assertTrue(current > old); expired.run();
                assertEquals(current, field("sourceRequest"));
                assertNotNull(field("streamDiscovery")); assertNull(field("nativePlayer"));
                assertEquals(300000, ((PlaybackRecovery) field("recovery")).restartPosition());
                expired = (Runnable) field("nativeDiscoveryDeadline");
                invoke("loadSource", item, 2, 1, 1); expired.run();
                assertEquals(false, field("nativeOnlySource"));
                assertSame(field("embeddedPlayer"), invoke("playbackBackend"));
                assertNull(field("nativeDiscoveryDeadline"));
            });
            // Wait for the actual replacement document, not a late onPageFinished
            // from an outgoing retry. Audio changes at this committed-page boundary.
            await("Fast replacement restores WebView audio", () -> {
                WebView web = (WebView) field("player");
                return "https://pantyflix.com/".equals(finishedPage.get()) && !WebViewCompat.isAudioMuted(web);
            });
            main(() -> {
                invoke("loadSource", item, 1, 1, 1);
                assertTrue(WebViewCompat.isAudioMuted((WebView) field("player")));
                invoke("loadSource", item, 0, 1, 1);
            });
            await("Bolt remains a playable web fallback with restored audio and resume", () -> {
                PlaybackBackend backend = (PlaybackBackend) invoke("playbackBackend");
                return field("boltPlayer") == backend && backend.state().available && backend.state().position == 300000
                    && !WebViewCompat.isAudioMuted((WebView) field("player"));
            });
            main(() -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                assertEquals(1f, ((WebView) field("player")).getAlpha(), 0f);
                StreamDiscovery.Stream broken = new StreamDiscovery.Stream("file:///missing-native-source.mp4", "https://example.com/", "test", null, 0);
                invoke("handoffToNative", item, 0, 1, 1, broken, field("sourceRequest"), 0L);
            });
            await("Failed VLC attempt returns once to Bolt Web at the saved position", () -> {
                PlaybackBackend backend = (PlaybackBackend) invoke("playbackBackend");
                return field("nativePlayer") == null && field("boltPlayer") == backend
                    && backend.state().available && backend.state().position == 300000;
            });
            main(() -> {
                assertNull("Fallback must not loop into another native attempt", field("streamDiscovery"));
                assertNull(field("nativeDiscoveryDeadline"));
                assertFalse(WebViewCompat.isAudioMuted((WebView) field("player")));
                invoke("closePlayer");
                assertEquals(300000, ((WatchHistory) field("watchHistory")).get("movie:45745").resumePosition());
            });
        } finally { main(() -> { invoke("closePlayer"); rule.getActivity().getSharedPreferences("watch_history", 0).edit().clear().commit(); }); }
    }
    private static final String BOLT = "<html><body><script>var t=Number(new URL(location.href).searchParams.get('startAt')||225);"
        + "function emit(x){parent.postMessage(x,'*')}"
        + "addEventListener('message',function(e){if(e.data.type==='seek'){t=e.data.time;emit({type:'timeupdate',time:t})}});"
        + "addEventListener('load',function(){emit({type:'ready'});emit({type:'durationchange',duration:900});"
        + "emit({type:'play'});emit({type:'timeupdate',time:t})});</script></body></html>";
    private static final String RIFT = "<html><body><script>parent.postMessage({type:'vidrift:state',"
        + "tmdbId:45745,mediaType:'movie',currentTime:2,duration:900,paused:false},'*');</script></body></html>";
}
