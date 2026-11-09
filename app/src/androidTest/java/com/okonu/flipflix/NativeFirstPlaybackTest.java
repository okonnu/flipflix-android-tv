package com.okonu.flipflix;

import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
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


    static boolean audioDecoded(NativePlayback playback) {
        androidx.media3.exoplayer.DecoderCounters counters = exoPlayer(playback).getAudioDecoderCounters();
        if (counters == null) return false;
        counters.ensureUpdated();
        return counters.renderedOutputBufferCount > 0;
    }
    static androidx.media3.exoplayer.ExoPlayer exoPlayer(NativePlayback playback) {
        try {
            java.lang.reflect.Field field = NativePlayback.class.getDeclaredField("strategy"); field.setAccessible(true);
            return ((ExoPlayback) field.get(playback)).player;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    static int playerVolume(NativePlayback playback) {
        return Math.round(exoPlayer(playback).getVolume() * 100);
    }
    @Test public void exoIsTheDefaultNativeEngine() throws Exception {
        NativePlayback playback = install(fixture());
        try { main(() -> assertEquals("ExoPlayer", playback.engine())); }
        finally { main(playback::release); }
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
        int movieId = Integer.parseInt(args.getString("nativeMovie", "45745"));
        JSONObject catalog = new JSONObject().put("id", movieId).put("media_type", "movie")
            .put("title", args.getString("nativeTitle", movieId == 45745 ? "Sintel" : "Live playback check"));
        Class<?> itemClass = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = itemClass.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, catalog, "movie");
        main(() -> invoke("showPlayerAtPosition", item, 1, 1, source, -1L));
        AtomicBoolean nativeReady = new AtomicBoolean(), failed = new AtomicBoolean(); String[] status = {"Preparing"};
        try {
            long end = SystemClock.uptimeMillis() + 90000;
            while (!nativeReady.get() && !failed.get() && SystemClock.uptimeMillis() < end) {
                main(() -> {
                    NativePlayback nativeVideo = (NativePlayback) field("nativePlayer");
                    PlaybackBackend backend = (PlaybackBackend) invoke("playbackBackend");
                    assertNotNull("Playback view closed before the live native check completed", backend);
                    PlaybackBackend.State s = backend.state();
                    int actualSource = (Integer) field("currentSourceIndex");
                    nativeReady.set(actualSource == source && nativeVideo != null && s.available && s.position > 1000);
                    status[0] = "requestedSource=" + source + " actualSource=" + actualSource + " engine="
                        + (nativeVideo == null ? ((Boolean) field("nativeOnlySource") ? "Resolving" : "Web") : nativeVideo.engine())
                        + " ready=" + s.available + " position=" + s.position + " duration=" + s.duration;
                    failed.set(nativeVideo == null && field("streamDiscovery") == null && field("nativeDiscoveryDeadline") == null);
                });
                SystemClock.sleep(1000);
            }
            android.util.Log.i("DarkflixNativeProbe", status[0]);
            if (nativeReady.get()) main(() -> {
                NativePlayback p = (NativePlayback) field("nativePlayer");
                Object manifest = exoPlayer(p).getCurrentManifest();
                android.util.Log.i("DarkflixNativeProbe", "manifest=" + (manifest == null ? "none" : manifest.getClass().getSimpleName()));
                if (manifest instanceof androidx.media3.exoplayer.hls.HlsManifest) {
                    androidx.media3.exoplayer.hls.HlsManifest hls = (androidx.media3.exoplayer.hls.HlsManifest) manifest;
                    android.util.Log.i("DarkflixNativeProbe", "masterVariants=" + hls.multivariantPlaylist.variants.size());
                }
                for (androidx.media3.common.Tracks.Group group : exoPlayer(p).getCurrentTracks().getGroups())
                    for (int i = 0; i < group.length; i++) {
                        androidx.media3.common.Format f = group.getTrackFormat(i);
                        android.util.Log.i("DarkflixNativeProbe", "track type=" + group.getType() + " mime=" + f.sampleMimeType
                            + " height=" + f.height + " language=" + f.language + " supported=" + group.isTrackSupported(i));
                    }
                for (PlaybackBackend.Option option : p.state().qualities)
                    android.util.Log.i("DarkflixNativeProbe", "quality=" + option.label);
                if (args.containsKey("expectedQualities")) {
                    int expected = Integer.parseInt(args.getString("expectedQualities"));
                    assertEquals("Capabilities match the inspected live source", expected, p.state().qualities.size());
                    if (expected == 1) {
                        assertTrue(p.state().qualityMessage.contains("one supported video quality"));
                        if (manifest instanceof androidx.media3.exoplayer.hls.HlsManifest)
                            assertEquals(1, ((androidx.media3.exoplayer.hls.HlsManifest) manifest).multivariantPlaylist.variants.size());
                    }
                }
            });
            if ("true".equals(args.getString("requireAudio"))) {
                assertTrue(status[0], nativeReady.get());
                await("Native playback must select, decode and output audio", () -> audioDecoded((NativePlayback) field("nativePlayer")));
                main(() -> {
                    NativePlayback p = (NativePlayback) field("nativePlayer");
                    assertFalse("New native sessions start unmuted", p.state().muted);
                    assertEquals("Output starts at full player volume", 100, playerVolume(p));
                    p.command("mute", "");
                });
                await("Live engine acknowledges muted audio output", () -> playerVolume((NativePlayback) field("nativePlayer")) == 0);
                main(() -> ((NativePlayback) field("nativePlayer")).command("mute", ""));
                await("Live engine acknowledges restored audio output", () -> playerVolume((NativePlayback) field("nativePlayer")) == 100);
            }
            if (nativeReady.get()) {
                if ("true".equals(args.getString("requireQualities"))) main(() -> {
                    NativePlayback p = (NativePlayback) field("nativePlayer");
                    assertTrue("Live master must expose multiple supported video qualities", p.state().qualities.size() > 2);
                    p.command("quality", p.state().qualities.get(1).id);
                });
                main(() -> ((NativePlayback) field("nativePlayer")).command("seek", "60000"));
                await("Live native seek acknowledged", () -> ((NativePlayback) field("nativePlayer")).state().available
                    && ((NativePlayback) field("nativePlayer")).state().position >= 59000);
                main(() -> ((NativePlayback) field("nativePlayer")).command("pause", ""));
                if ("true".equals(args.getString("requireQualities"))) await("Live native quality override selected", () -> {
                    NativePlayback p = (NativePlayback) field("nativePlayer");
                    return p.state().qualities.size() > 1 && p.state().qualities.get(1).selected;
                });
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
                ((NativePlayback) field("nativePlayer")).command("mute", "");
            });
            await("Native seek acknowledged", () -> ((NativePlayback) field("nativePlayer")).state().available && Math.abs(((NativePlayback) field("nativePlayer")).state().position - 26000) < 1000);
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
                return player.state().available && player.state().position >= 25000
                    && !player.state().muted && playerVolume(player) == 100;
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

    @Test public void exoDecodesAudioAndSupportsPauseSeekMuteAndCrop() throws Exception {
        // Real licensed clip with non-silent audio, not only a muted state flag.
        File clip = new File(rule.getActivity().getCacheDir(), "native-sintel.mp4");
        try (InputStream in = rule.getActivity().getAssets().open("open-films/sintel.mp4"); FileOutputStream out = new FileOutputStream(clip)) {
            byte[] bytes = new byte[8192]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        }
        NativePlayback playback = install(clip);
        try {
            main(() -> playback.start(9000));
            await("Exo plays and outputs decoded audio", () -> playback.state().available && audioDecoded(playback));
            main(() -> {
                assertEquals("ExoPlayer", playback.engine());
                assertFalse("New native playback starts unmuted", playback.state().muted);
                assertEquals("New native playback starts at full player volume", 100, playerVolume(playback));
                assertTrue(playback.state().position >= 8000);
                assertTrue(playback.state().seekable);
                playback.command("pause", ""); playback.command("seek", "14000");
                playback.command("mute", ""); playback.command("resize", "fill");
                assertTrue(playback.state().paused); assertTrue(playback.state().muted); assertTrue(playback.state().fillScreen);
            });
            await("Exo acknowledges mute while seeking", () -> playerVolume(playback) == 0);
            await("Paused seek acknowledged", () -> Math.abs(playback.state().position - 14000) < 1000 && !exoPlayer(playback).isPlaying());
            main(() -> {
                playback.command("mute", "");
                playback.command("play", ""); assertFalse(playback.state().paused);
                assertTrue(playback.state().settings.containsKey("audio"));
            });
            await("Exo acknowledges unmuted output after seek", () -> playerVolume(playback) == 100);
        } finally { main(() -> { playback.release(); playback.release(); assertFalse(playback.state().available); }); }
    }

    @Test public void preparationKeepsPauseMuteAndCropWithoutAnotherEngine() throws Exception {
        NativePlayback playback = install(fixture());
        try {
            main(() -> {
                playback.start(12000);
                playback.command("pause", ""); playback.command("mute", ""); playback.command("resize", "fill");
            });
            await("Preparation retains saved position", () -> playback.state().available && Math.abs(playback.state().position - 12000) < 1500);
            main(() -> {
                assertTrue(playback.state().paused); assertTrue(playback.state().muted); assertTrue(playback.state().fillScreen);
                assertEquals(0, playerVolume(playback));
                assertEquals("ExoPlayer", playback.engine());
            });
        } finally { main(playback::release); }
    }

    @Test public void nativeFailureIsTerminalWithoutFallback() {
        NativePlayback playback = install(new File("/missing-native-fixture.mp4"));
        AtomicInteger errors = new AtomicInteger();
        try {
            main(() -> {
                playback.setListener(new NativePlayback.Listener() {
                    public void ready() { fail("Missing file cannot be playable"); }
                    public void failed() { errors.incrementAndGet(); }
                }); playback.start();
            });
            await("Failure reaches UI", () -> errors.get() == 1);
            main(() -> {
                playback.start(); playback.reject();
                assertEquals(1, errors.get()); assertEquals("ExoPlayer", playback.engine());
                assertFalse(playback.state().available); assertFalse(playback.state().settings.containsKey("engine"));
            });
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
            discovery[0] = new StreamDiscovery(web[0], "https://vidbolt.xyz/movie/45745", true, stream -> { assertEquals(900000, stream.duration); found.incrementAndGet(); });
            assertTrue(discovery[0].active());
            assertTrue(androidx.webkit.WebViewCompat.isAudioMuted(web[0]));
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

    @Test public void discoveryUsesActiveHlsMasterWhenNetworkOnlyContainsVideoRendition() {
        WebView[] web = new WebView[1]; StreamDiscovery[] discovery = new StreamDiscovery[1];
        java.util.concurrent.atomic.AtomicReference<StreamDiscovery.Stream> found = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicBoolean loaded = new AtomicBoolean();
        main(() -> {
            web[0] = new WebView(rule.getActivity()); web[0].getSettings().setJavaScriptEnabled(true);
            rule.getActivity().setContentView(web[0]);
            web[0].setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    boolean library = request.getUrl().getPath().endsWith("hls.js");
                    String body = library
                        ? "window.Hls=function(){};Hls.prototype.attachMedia=function(v){this.media=v;return 'unchanged';};Hls.prototype.loadSource=function(u){this.url=u;};"
                        : "<html><body><video id='v'></video><video id='other'></video><script src='/hls.js'></script><script>"
                        + "(function(){var v=document.getElementById('v'),h=new Hls(),decoy=new Hls();"
                        + "window.attachResult=h.attachMedia(v);h.loadSource('https://cdn.example.com/master.m3u8');"
                        + "decoy.attachMedia(document.getElementById('other'));decoy.loadSource('https://ads.example.com/wrong.m3u8');"
                        + "Object.defineProperties(v,{duration:{value:900},readyState:{value:1},currentSrc:{value:'blob:local'}});"
                        + "performance.getEntriesByType=function(){return [{name:'https://cdn.example.com/video-only.m3u8',startTime:10}];};"
                        + "})();</script></body></html>";
                    return new WebResourceResponse(library ? "text/javascript" : "text/html", "UTF-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView view, String url) { loaded.set(true); }
            });
            discovery[0] = new StreamDiscovery(web[0], "https://embed.vidrift.in/embed/movie/45745", true, found::set);
            assertTrue(discovery[0].active());
            web[0].loadUrl("https://embed.vidrift.in/embed/movie/45745");
        });
        try {
            await("Provider library and video loaded", loaded::get);
            main(() -> web[0].evaluateJavascript("document.getElementById('v').dispatchEvent(new Event('loadedmetadata'));", null));
            await("Active source discovered", () -> found.get() != null);
            assertEquals("Keep alternate audio in the master; never use a child video playlist or another player's URL",
                "https://cdn.example.com/master.m3u8", found.get().url);
            AtomicBoolean preserved = new AtomicBoolean();
            main(() -> web[0].evaluateJavascript("attachResult==='unchanged'", value -> preserved.set("true".equals(value))));
            await("Library method behavior is preserved", preserved::get);
        } finally { main(() -> { discovery[0].close(); web[0].destroy(); }); }
    }
}
