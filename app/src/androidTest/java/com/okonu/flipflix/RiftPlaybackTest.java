package com.okonu.flipflix;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.os.SystemClock;
import androidx.test.rule.ActivityTestRule;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real WebView, cross-origin iframe, and native bridge; deterministic provider protocol. */
@RunWith(AndroidJUnit4.class)
public final class RiftPlaybackTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private WebView web;
    private RiftPlayback rift;
    private String notice = "";
    private boolean direct, exited;
    private void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private interface Check { boolean ready(); }
    private void await(Check check) {
        AtomicBoolean done = new AtomicBoolean(); long end = SystemClock.uptimeMillis() + 10000;
        while (!done.get() && SystemClock.uptimeMillis() < end) { main(() -> done.set(check.ready())); SystemClock.sleep(50); }
        assertTrue("Rift fixture completed", done.get());
    }
    private String js(String script) throws Exception {
        AtomicReference<String> value = new AtomicReference<>(); CountDownLatch latch = new CountDownLatch(1);
        main(() -> web.evaluateJavascript(script, result -> { value.set(result); latch.countDown(); }));
        assertTrue(latch.await(5, TimeUnit.SECONDS)); return value.get();
    }
    private void fixture(String expression) throws Exception {
        js("document.getElementById('rift').contentWindow.postMessage({fixture:" + JSONObject.quote(expression) + "},'https://embed.vidrift.in')");
    }
    private void install() {
        main(() -> {
            web = new WebView(rule.getActivity()); web.getSettings().setJavaScriptEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if ("embed.vidrift.in".equals(request.getUrl().getHost())) return new WebResourceResponse("text/html", "UTF-8",
                        new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8)));
                    return null;
                }
            });
            rift = new RiftPlayback(web, text -> notice = text, () -> direct = true, () -> exited = true);
            rule.getActivity().setContentView(web);
            rift.load("https://embed.vidrift.in/embed/tv/42/1/2",42,"tv",1,2);
        });
        await(() -> rift.state().available);
    }
    @After public void close() { main(() -> { if (rift != null) rift.close(); if (web != null) web.destroy(); }); }

    @Test public void controlsUseProviderIdsAndRefreshAfterFailover() throws Exception {
        install();
        main(() -> {
            assertEquals(100000,rift.state().position); assertTrue(rift.state().seekable);
            assertEquals(2,rift.state().settings.get("audio").size());
            rift.command("audio","ko"); rift.command("quality","hd"); rift.command("subtitle","en");
            rift.command("speed","1.50"); rift.command("volume","0.40");
            rift.command("subtitleSize","150.00"); rift.command("subtitleFont","serif");
            rift.command("subtitleOffset","2.50"); rift.command("seek","250000"); rift.command("pause","");
        });
        await(() -> rift.state().position == 250000 && rift.state().paused);
        main(() -> {
            assertTrue(rift.state().settings.get("audio").get(1).selected);
            assertTrue(rift.state().qualities.get(1).selected);
            assertTrue(rift.state().subtitles.get(1).selected);
            assertSelected("speed","1.50"); assertSelected("volume","0.40");
            assertSelected("subtitleSize","150.00"); assertSelected("subtitleFont","serif"); assertSelected("subtitleOffset","2.50");
            rift.command("resize","fill"); assertTrue(rift.state().fillScreen);
            rift.command("play",""); rift.command("mute","");
        });
        await(() -> !rift.state().paused && rift.state().muted);
        fixture("s.audios=[];s.audio='';s.qualities=[{value:'auto',label:'Auto'}];s.quality='auto';emit(s)");
        await(() -> !rift.state().settings.containsKey("audio") && rift.state().qualities.size()==1);
        main(() -> { rift.command("audio","ko"); assertTrue(notice.contains("no longer available")); });
    }
    private void assertSelected(String key, String id) {
        boolean selected = false;
        for (PlaybackBackend.Option o : rift.state().settings.get(key)) if (o.id.equals(id) && o.selected) selected = true;
        assertTrue(key, selected);
    }
    @Test public void errorsAndProviderOnlyControlsStayAccessible() throws Exception {
        install();
        fixture("emit({type:'vidrift:command-error',command:'vidrift:set',field:'muted',reason:'click-required'})");
        await(() -> notice.contains("Rift needs"));
        main(() -> { rift.command("player",""); assertTrue(direct); });
        fixture("emit({type:'vidrift:exit'})"); await(() -> exited);
    }
    @Test public void rejectsWrongOriginsFramesEpisodesAndClosedSessions() throws Exception {
        install();
        String event = "{type:'vidrift:state',tmdbId:42,mediaType:'tv',season:1,episode:2,currentTime:999,duration:1000}";
        js("window.dispatchEvent(new MessageEvent('message',{origin:'https://embed.vidrift.in.evil.example',source:document.getElementById('rift').contentWindow,data:"+event+"}))");
        js("window.dispatchEvent(new MessageEvent('message',{origin:'https://embed.vidrift.in',source:window,data:"+event+"}))");
        fixture("emit({type:'vidrift:progress',episode:3,currentTime:999})");
        SystemClock.sleep(300); main(() -> assertEquals(100000,rift.state().position));
        main(() -> rift.close());
        fixture("s.currentTime=444;emit(s)"); SystemClock.sleep(300);
        main(() -> assertFalse(rift.state().available));
    }

    @Test public void liveRiftProbe() {
        String id = InstrumentationRegistry.getArguments().getString("liveRift", "");
        org.junit.Assume.assumeTrue("Optional live-provider probe", id.matches("[0-9]+"));
        main(() -> {
            web = new WebView(rule.getActivity());
            web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setMediaPlaybackRequiresUserGesture(false);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                    if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                        android.util.Log.i("RiftProbe", "Provider JavaScript error: " + message.message().substring(0, Math.min(180, message.message().length())));
                    return true;
                }
            });
            rift = new RiftPlayback(web, text -> notice = text, () -> direct = true, () -> exited = true);
            rule.getActivity().setContentView(web);
            rift.load("https://embed.vidrift.in/embed/movie/" + id, Integer.parseInt(id), "movie", 1, 1);
        });
        AtomicBoolean ready = new AtomicBoolean(), playing = new AtomicBoolean();
        long end = SystemClock.uptimeMillis() + 35000;
        while (!playing.get() && SystemClock.uptimeMillis() < end) {
            main(() -> {
                ready.set(rift.state().available);
                playing.set(rift.state().duration > 0 && rift.state().position > 0 && !rift.state().paused);
            }); SystemClock.sleep(250);
        }
        main(() -> android.util.Log.i("RiftProbe", "WebView=" + androidx.webkit.WebViewCompat.getCurrentWebViewPackage(rule.getActivity()).versionName
            + " state=" + rift.state().available + " duration=" + rift.state().duration + " playing=" + playing.get()
            + " settings=" + rift.state().settings.keySet() + " notice=" + notice));
        assertTrue("Live Rift exposes its documented state in this WebView", ready.get());
    }

    private static final String FIXTURE = "<!doctype html><script>"
        + "var s={type:'vidrift:state',tmdbId:42,mediaType:'tv',season:1,episode:2,paused:false,currentTime:100,duration:3600,volume:1,muted:false,speed:1,"
        + "audio:'en',audios:[{value:'en',label:'English'},{value:'ko',label:'Korean'}],server:'vela',servers:[{value:'vela',label:'Vela'},{value:'orion',label:'Orion'}],"
        + "source:'0',sources:[{value:'0',label:'English'},{value:'1',label:'Dub'}],quality:'auto',qualities:[{value:'auto',label:'Auto'},{value:'hd',label:'1080p'}],"
        + "subtitle:'off',subtitles:[{value:'off',label:'Off'},{value:'en',label:'English'}],subtitleOffset:0,subtitleStyle:{size:100,color:'#ffffff',background:'box',font:'default',position:0},cast:'unavailable'};"
        + "function emit(d){parent.postMessage(Object.assign({tmdbId:42,mediaType:'tv',season:1,episode:2},d),'https://com.okonu.flipflix')}"
        + "addEventListener('message',function(e){if(e.origin!=='https://com.okonu.flipflix')return;var d=e.data;if(d.fixture){eval(d.fixture);return;}"
        + "if(d.type==='vidrift:set'){Object.keys(d).forEach(function(k){if(k==='subtitleStyle')Object.assign(s.subtitleStyle,d[k]);else if(k!=='type')s[k]=d[k]})}"
        + "if(d.type==='vidrift:resume')s.currentTime=d.currentTime;if(d.type==='vidrift:pause')s.paused=true;if(d.type==='vidrift:play')s.paused=false;emit(s)})</script>";
}
