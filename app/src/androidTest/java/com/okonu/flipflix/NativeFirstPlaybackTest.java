package com.okonu.flipflix;

import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NativeFirstPlaybackTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private interface Check { boolean ready(); }
    private void await(String message, Check check) {
        AtomicBoolean result = new AtomicBoolean(); long end = SystemClock.uptimeMillis() + 20000;
        while (!result.get() && SystemClock.uptimeMillis() < end) { main(() -> result.set(check.ready())); SystemClock.sleep(80); }
        assertTrue(message, result.get());
    }
    private File fixture() throws Exception {
        File file = new File(rule.getActivity().getCacheDir(), "native-fallback.mp4");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("silent-black-25s.mp4"); FileOutputStream out = new FileOutputStream(file)) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        return file;
    }
    private NativePlayback install(File file) {
        NativePlayback[] playback = new NativePlayback[1];
        main(() -> {
            playback[0] = new NativePlayback(rule.getActivity(), file.toURI().toString(), "https://example.com/", null);
            rule.getActivity().setContentView(playback[0].view);
        });
        return playback[0];
    }

    private Object field(String name) {
        try { java.lang.reflect.Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(rule.getActivity()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private Object invoke(String name, Object... args) {
        try {
            for (java.lang.reflect.Method method : MainActivity.class.getDeclaredMethods()) if (method.getName().equals(name)) {
                method.setAccessible(true); return method.invoke(rule.getActivity(), args);
            }
            throw new NoSuchMethodException(name);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void liveNativeProbe() throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        org.junit.Assume.assumeTrue("Explicit live-provider diagnostic only", args.containsKey("nativeProvider"));
        int source = Integer.parseInt(args.getString("nativeProvider"));
        assertTrue(source >= 0 && source <= 2);
        JSONObject catalog = new JSONObject().put("id", 45745).put("media_type", "movie").put("title", "Sintel");
        Class<?> itemClass = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = itemClass.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, catalog, "movie");
        main(() -> invoke("showPlayerAtPosition", item, 1, 1, source, -1L));
        AtomicBoolean nativeReady = new AtomicBoolean(); String[] status = {"Preparing"};
        try {
            long end = SystemClock.uptimeMillis() + 90000;
            while (!nativeReady.get() && SystemClock.uptimeMillis() < end) {
                main(() -> {
                    NativePlayback nativeVideo = (NativePlayback) field("nativePlayer");
                    PlaybackBackend backend = (PlaybackBackend) invoke("playbackBackend");
                    PlaybackBackend.State s = backend.state();
                    nativeReady.set(nativeVideo != null && s.available && s.position > 1000);
                    status[0] = "source=" + source + " engine=" + (nativeVideo == null ? "Web" : nativeVideo.engine())
                        + " ready=" + s.available + " position=" + s.position + " duration=" + s.duration;
                });
                SystemClock.sleep(1000);
            }
            android.util.Log.i("DarkflixNativeProbe", status[0]);
            if (nativeReady.get()) {
                main(() -> ((NativePlayback) field("nativePlayer")).command("seek", "60000"));
                await("Live native seek acknowledged", () -> ((NativePlayback) field("nativePlayer")).state().available
                    && ((NativePlayback) field("nativePlayer")).state().position >= 59000);
                main(() -> ((NativePlayback) field("nativePlayer")).command("pause", ""));
            }
            main(() -> {
                invoke("revealPlayerOverlay", true);
                try {
                    java.lang.reflect.Field button = PlaybackControls.class.getDeclaredField("source"); button.setAccessible(true);
                    ((View) button.get(field("playerOverlay"))).requestFocus();
                } catch (Exception e) { throw new AssertionError(e); }
            });
            try (android.os.ParcelFileDescriptor pipe = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("screencap -p /sdcard/Download/darkflix-native-probe-" + source + ".png");
                    java.io.FileInputStream input = new java.io.FileInputStream(pipe.getFileDescriptor())) {
                byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
            }
            if ("true".equals(args.getString("requireNative"))) assertTrue(status[0], nativeReady.get());
            if ("true".equals(args.getString("requireVlc"))) {
                assertTrue(status[0], nativeReady.get());
                main(() -> ((NativePlayback) field("nativePlayer")).fallback());
                await("VLC plays the same live stream natively at the paused checkpoint", () -> {
                    NativePlayback p = (NativePlayback) field("nativePlayer");
                    return p.engine().equals("VLC") && p.state().available && p.state().paused && p.state().position >= 59000;
                });
                main(() -> android.util.Log.i("DarkflixNativeProbe", "VLC ready=true position=" + ((NativePlayback) field("nativePlayer")).state().position));
                try (android.os.ParcelFileDescriptor pipe = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .executeShellCommand("screencap -p /sdcard/Download/darkflix-native-vlc.png");
                        java.io.FileInputStream input = new java.io.FileInputStream(pipe.getFileDescriptor())) {
                    byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
                }
            }
        } finally { main(() -> invoke("closePlayer")); }
    }

    @Test public void activityHandoffRetainsHistoryAcrossProvidersAndBack() throws Exception {
        // Repeat the existing generated black fixture into a real 100-second MP4,
        // so history/ad guards exercise actual metadata rather than a fake clock.
        File clip = fixture(), movie = new File(rule.getActivity().getCacheDir(), "native-history.mp4");
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        extractor.setDataSource(clip.getAbsolutePath()); extractor.selectTrack(0);
        android.media.MediaFormat format = extractor.getTrackFormat(0);
        long clipDuration = format.getLong(android.media.MediaFormat.KEY_DURATION);
        android.media.MediaMuxer muxer = new android.media.MediaMuxer(movie.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = muxer.addTrack(format); muxer.start();
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(2 * 1024 * 1024);
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        for (int repeat = 0; repeat < 4; repeat++) {
            extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            while (true) {
                bytes.clear(); int size = extractor.readSampleData(bytes, 0); if (size < 0) break;
                info.set(0, size, extractor.getSampleTime() + repeat * clipDuration, extractor.getSampleFlags());
                muxer.writeSampleData(track, bytes, info); extractor.advance();
            }
        }
        muxer.stop(); muxer.release(); extractor.release();
        JSONObject catalog = new JSONObject().put("id", 990000001).put("media_type", "movie").put("title", "Native history fixture");
        Class<?> itemClass = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = itemClass.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, catalog, "movie");
        StreamDiscovery.Stream stream = new StreamDiscovery.Stream(movie.toURI().toString(), "https://example.com/", "test", null, 100000);
        main(() -> {
            invoke("showPlayerAtPosition", item, 1, 1, 0, 12000L);
            ((WebView) field("player")).stopLoading();
            invoke("handoffToNative", item, 0, 1, 1, stream, field("sourceRequest"), 12000L);
        });
        try {
            await("Activity resumes the actual native video", () -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                NativePlayback player = (NativePlayback) field("nativePlayer");
                return player != null && player.state().available && player.state().position >= 11000;
            });
            main(() -> {
                assertEquals(View.GONE, ((WebView) field("player")).getVisibility());
                assertNull(field("boltPlayer")); assertNull(field("riftPlayer"));
                ((NativePlayback) field("nativePlayer")).command("pause", "");
                ((NativePlayback) field("nativePlayer")).command("seek", "26000");
            });
            await("Native seek acknowledged", () -> ((NativePlayback) field("nativePlayer")).state().available && ((NativePlayback) field("nativePlayer")).state().position == 26000);
            main(() -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                int stale = (Integer) field("sourceRequest");
                invoke("loadSource", item, 1, 1, 1);
                ((WebView) field("player")).stopLoading();
                invoke("handoffToNative", item, 0, 1, 1, stream, stale, 0L);
                assertNull("Late old provider cannot take over", field("nativePlayer"));
                invoke("handoffToNative", item, 1, 1, 1, stream, field("sourceRequest"), 0L);
            });
            await("Second provider inherits progress", () -> {
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                NativePlayback player = (NativePlayback) field("nativePlayer");
                return player.state().available && player.state().position >= 25000;
            });
            main(() -> {
                invoke("onPause");
                assertTrue(((NativePlayback) field("nativePlayer")).state().paused);
                invoke("loadSource", item, 1, 1, 1);
                ((WebView) field("player")).stopLoading();
                invoke("handoffToNative", item, 1, 1, 1, stream, field("sourceRequest"), 0L);
                assertTrue("Late resolution must not autoplay in the background", ((NativePlayback) field("nativePlayer")).state().paused);
            });
            await("Background preparation keeps the saved position without playing", () -> {
                NativePlayback player = (NativePlayback) field("nativePlayer");
                return player.state().available && player.state().paused && player.state().position >= 25000;
            });
            main(() -> {
                invoke("onResume");
                ((PlaybackRecovery) field("recovery")).sample(SystemClock.uptimeMillis());
                invoke("closePlayer");
                assertNull(field("nativePlayer")); assertNull(field("streamDiscovery"));
                WatchHistory.Entry history = ((WatchHistory) field("watchHistory")).get("movie:990000001");
                assertNotNull(history); assertTrue(history.resumePosition() >= 25000); assertEquals(1, history.source);
            });
        } finally {
            main(() -> { invoke("closePlayer"); rule.getActivity().getSharedPreferences("watch_history", 0).edit().remove("movie:990000001").commit(); });
        }
    }

    @Test public void mediaEligibilityRejectsDrmAdsBlobsAndWrongOrigin() throws Exception {
        JSONObject candidate = new JSONObject().put("url", "https://cdn.example.com/movie.m3u8?token=example")
            .put("page", "https://vidbolt.xyz/movie/45745").put("duration", 900000);
        assertNotNull(StreamDiscovery.parse(candidate, "https://vidbolt.xyz", "test"));
        assertNull(StreamDiscovery.parse(candidate, "https://bingr.one", "test"));
        candidate.put("encrypted", true); assertNull(StreamDiscovery.parse(candidate, "https://vidbolt.xyz", "test"));
        candidate.put("encrypted", false).put("duration", 25000); assertNull(StreamDiscovery.parse(candidate, "https://vidbolt.xyz", "test"));
        for (String url : new String[]{"blob:https://vidbolt.xyz/123", "file:///private", "http://cdn.example/video", "https://localhost/video", "https://user:pass@cdn.example/video"}) assertFalse(url, StreamDiscovery.mediaUrl(url));
        assertEquals("https://example.com/sub/en.vtt", NativePlayback.captionUrl("/sub/en.vtt", "https://example.com/movie/1"));
    }

    @Test public void realExoFailureFallsBackToRealVlcDecoderOnce() throws Exception {
        NativePlayback playback = install(fixture());
        AtomicInteger changes = new AtomicInteger(), errors = new AtomicInteger();
        try {
            main(() -> {
                playback.setListener(new NativePlayback.Listener() {
                    public void ready() { }
                    public void failed() { errors.incrementAndGet(); }
                    public void engineChanged(String engine) { assertEquals("VLC", engine); changes.incrementAndGet(); }
                });
                // Fault injection only into Exo; the original fixture remains a
                // playable URL for the real VLC engine (no mocked decoding).
                playback.player.setMediaItem(MediaItem.fromUri("file:///missing-exo-fixture.mp4"));
                playback.start(9000);
            });
            await("VLC actually decodes the fixture after Exo error", () -> playback.engine().equals("VLC") && playback.state().available && playback.state().position >= 8000);
            main(() -> {
                assertEquals(1, changes.get()); assertEquals(0, errors.get());
                assertTrue(playback.state().duration >= 24000); assertTrue(playback.state().seekable);
                playback.fallback(); assertEquals(1, changes.get());
                playback.command("pause", ""); playback.command("seek", "14000");
                playback.command("mute", ""); playback.command("resize", "fill");
                assertTrue(playback.state().paused); assertTrue(playback.state().muted); assertTrue(playback.state().fillScreen);
            });
            await("VLC seek is acknowledged", () -> Math.abs(playback.state().position - 14000) < 1000);
            main(() -> { playback.command("play", ""); assertFalse(playback.state().paused); });
        } finally { main(() -> { playback.release(); playback.release(); assertFalse(playback.state().available); }); }
    }

    @Test public void nativeEngineChangeKeepsPausedPositionMuteAndCrop() throws Exception {
        NativePlayback playback = install(fixture());
        try {
            main(() -> playback.start(1000));
            await("Exo decodes", () -> playback.state().available);
            main(() -> { playback.command("pause", ""); playback.command("seek", "12000"); });
            await("Exo seek settled", () -> playback.state().available && playback.state().position == 12000);
            main(() -> { playback.command("mute", ""); playback.command("resize", "fill"); playback.fallback(); });
            await("Native fallback retains the seek and pause", () -> playback.state().available && Math.abs(playback.state().position - 12000) < 1000);
            main(() -> { assertTrue(playback.state().paused); assertTrue(playback.state().muted); assertTrue(playback.state().fillScreen); });
        } finally { main(playback::release); }
    }

    @Test public void bothNativeEnginesFailWithoutLoopingOrWebFallback() {
        NativePlayback playback = install(new File("/missing-both-players.mp4"));
        AtomicInteger errors = new AtomicInteger(), changes = new AtomicInteger();
        try {
            main(() -> {
                playback.setListener(new NativePlayback.Listener() {
                    public void ready() { fail("Missing file cannot be playable"); }
                    public void failed() { errors.incrementAndGet(); }
                    public void engineChanged(String engine) { changes.incrementAndGet(); }
                }); playback.start();
            });
            await("Failure reaches UI", () -> errors.get() == 1);
            main(() -> { playback.fallback(); assertEquals(1, errors.get()); assertEquals(1, changes.get()); assertFalse(playback.state().available); });
        } finally { main(playback::release); }
    }

    @Test public void discoveryUsesRealScopedWebBridgeAndCancelsOldSession() {
        WebView[] web = new WebView[1]; StreamDiscovery[] discovery = new StreamDiscovery[1];
        AtomicInteger found = new AtomicInteger(); AtomicBoolean loaded = new AtomicBoolean();
        main(() -> {
            web[0] = new WebView(rule.getActivity()); web[0].getSettings().setJavaScriptEnabled(true);
            rule.getActivity().setContentView(web[0]);
            web[0].setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream("<html><body><video id='v'></video></body></html>".getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView view, String url) { loaded.set(true); }
            });
            discovery[0] = new StreamDiscovery(web[0], "https://vidbolt.xyz/movie/45745", stream -> { assertEquals(900000, stream.duration); found.incrementAndGet(); });
            web[0].loadUrl("https://vidbolt.xyz/movie/45745");
        });
        try {
            await("Provider frame loaded", loaded::get);
            main(() -> web[0].evaluateJavascript("var v=document.getElementById('v');Object.defineProperties(v,{duration:{value:900},readyState:{value:1},currentSrc:{value:'https://cdn.example.com/movie.m3u8'}});v.dispatchEvent(new Event('loadedmetadata'));v.dispatchEvent(new Event('loadedmetadata'));", null));
            await("One eligible stream is discovered", () -> found.get() == 1);
            main(() -> {
                discovery[0].close();
                discovery[0] = new StreamDiscovery(web[0], "https://vidbolt.xyz/movie/2", stream -> found.incrementAndGet());
                web[0].evaluateJavascript("v.dispatchEvent(new Event('loadedmetadata'))", null);
            });
            SystemClock.sleep(300);
            assertEquals("Old document cannot feed the new lease", 1, found.get());
        } finally { main(() -> { discovery[0].close(); web[0].destroy(); }); }
    }
}
