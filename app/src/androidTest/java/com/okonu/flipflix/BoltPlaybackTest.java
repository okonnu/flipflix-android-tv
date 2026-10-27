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
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BoltPlaybackTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private WebView web;
    private BoltPlayback bolt;
    private final PlaybackBackend.State dom = new PlaybackBackend.State();
    private String fallbackCommand = "";
    private void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private interface Check { boolean ready(); }
    private void await(Check check) {
        AtomicBoolean done = new AtomicBoolean(); long end = SystemClock.uptimeMillis() + 7000;
        while (!done.get() && SystemClock.uptimeMillis() < end) { main(() -> done.set(check.ready())); SystemClock.sleep(50); }
        assertTrue("Bolt message contract completed", done.get());
    }
    private void js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        main(() -> web.evaluateJavascript(script, value -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
    private void fixture(String expression) throws Exception {
        js("document.getElementById('bolt').contentWindow.postMessage({fixture:" + JSONObject.quote(expression) + "},'https://vidbolt.xyz')");
    }
    private void install() {
        main(() -> {
            web = new WebView(rule.getActivity()); web.getSettings().setJavaScriptEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8)));
                }
            });
            bolt = new BoltPlayback(web, new PlaybackBackend() {
                public State state() { return dom; }
                public void command(String name, String value) { fallbackCommand = name + ":" + value; }
            });
            rule.getActivity().setContentView(web);
            bolt.load("https://vidbolt.xyz/movie/45745?autoPlay=true", 120000);
        });
        await(() -> bolt.state().available && bolt.state().position == 120000);
    }
    @After public void close() { main(() -> { if (bolt != null) bolt.close(); if (web != null) web.destroy(); }); }

    @Test public void commandsUseSecondsAndKeepExistingOptions() throws Exception {
        install();
        main(() -> {
            dom.available = true; dom.position = 1000; dom.duration = 15000;
            dom.canResize = true; dom.subtitles.add(new PlaybackBackend.Option("off", "Off", true));
            assertEquals(120000, bolt.state().position); assertEquals(900000, bolt.state().duration);
            assertEquals(1, bolt.state().subtitles.size()); assertTrue(bolt.state().canResize);
            bolt.command("subtitle", "off"); assertEquals("subtitle:off", fallbackCommand);
            bolt.command("seek", "255500"); bolt.command("pause", "");
        });
        await(() -> bolt.state().position == 255500 && bolt.state().paused);
        main(() -> { bolt.command("toggle", ""); dom.available = false; bolt.command("mute", ""); assertTrue(bolt.state().muted); });
        await(() -> !bolt.state().paused);
        main(() -> { bolt.command("mute", ""); assertFalse(bolt.state().muted); });
        fixture("emit({type:'ended'})"); await(() -> bolt.state().ended);
    }
    @Test public void wrongOriginFrameAndSessionCannotReplaceProgress() throws Exception {
        install();
        // A same-document event has the wrong source, even if the origin string looks correct.
        js("dispatchEvent(new MessageEvent('message',{origin:'https://vidbolt.xyz',source:window,data:{type:'timeupdate',time:800}}))");
        fixture("parent.postMessage({type:'timeupdate',time:NaN},'*')");
        main(() -> {
            bolt.receive("{\"session\":\"stale\",\"event\":{\"type\":\"timeupdate\",\"time\":800}}");
            assertEquals(120000, bolt.state().position);
        });
        // A sibling iframe with the correct provider origin must also be rejected.
        js("var other=document.createElement('iframe');other.src='https://vidbolt.xyz/movie/999?startAt=800';document.body.appendChild(other)");
        fixture("emit({type:'timeupdate',time:121})"); await(() -> bolt.state().position == 121000);
        main(() -> { bolt.close(); });
        fixture("emit({type:'timeupdate',time:500})");
        main(() -> assertEquals(121000, bolt.state().position));
    }
    @Test public void renameKeepsUpgradeIdentity() {
        android.content.Context context = rule.getActivity();
        assertEquals("Dagflix Test", context.getApplicationInfo().loadLabel(context.getPackageManager()).toString());
        assertEquals("com.okonu.flipflix.validation", context.getPackageName());
    }
    private static final String FIXTURE = "<html><body><script>var t=Number(new URL(location.href).searchParams.get('startAt')||0);"
        + "function emit(x){parent.postMessage(x,'*')}"
        + "addEventListener('message',function(e){var d=e.data;if(d.fixture){eval(d.fixture);return}"
        + "if(d.type==='seek'){t=d.time;emit({type:'timeupdate',time:t})}"
        + "if(d.type==='pause'||d.type==='play')emit({type:d.type})});"
        + "addEventListener('load',function(){emit({type:'ready'});emit({type:'durationchange',duration:900});"
        + "emit({type:'play'});emit({type:'timeupdate',time:t})});</script></body></html>";
}
