package com.okonu.flipflix;

import android.app.Instrumentation;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SportsCompatibilityTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }

    private String script() throws Exception {
        return script("sports-compat.js");
    }
    private String script(String name) throws Exception {
        try (InputStream input = rule.getActivity().getAssets().open(name);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return bytes.toString("UTF-8");
        }
    }

    @Test public void compatibilityIsRestrictedToTheSportsDocument() {
        assertTrue(SportsEmbedCompat.isSportsPage(Uri.parse("https://embed.st/embed/admin/test/1")));
        for (String url : new String[]{"http://embed.st/embed/a", "https://embed.st.evil.test/embed/a",
            "https://embed.st:8443/embed/a", "https://user@embed.st/embed/a", "https://embed.st/ad.html",
            "https://embed.st/", "https://vidbolt.xyz/embed/a"}) {
            assertFalse(url, SportsEmbedCompat.isSportsPage(Uri.parse(url)));
        }
        String html = "<!doctype html><html><head><meta charset='utf-8'><SCRIPT>provider()</SCRIPT></head></html>";
        String injected = SportsEmbedCompat.inject(html, "compat()");
        assertTrue(injected.startsWith("<!doctype html>"));
        assertTrue(injected.indexOf("compat()") < injected.indexOf("provider()"));
        assertTrue(injected.contains("<SCRIPT>provider()</SCRIPT>"));
        assertEquals("<html>no script</html>", SportsEmbedCompat.inject("<html>no script</html>", "compat()"));
    }

    @Test public void onlyTheKnownPdfFalsePositiveIsSuppressed() throws Exception {
        String fixture = fixture(script());
        WebView web = load("https://embed.st/embed/admin/test/1", fixture);
        try {
            // The PDF probe must not replace the document, but all other object
            // errors must still reach their own handler. No prototype changes.
            assertEquals("\"0,1,1,1\"", evaluate(web, "[probe('sandDetect','data:application/pdf;base64,aG1t'),probe('other','data:application/pdf;base64,aG1t'),probe('sandDetect','data:text/plain,other'),probe('sandDetect','data:application/pdf;base64,OTHER')].join(',')"));
            assertEquals("\"fixture\"", evaluate(web, "document.getElementById('status').textContent"));
        } finally { instrumentation().runOnMainSync(web::destroy); }
        WebView other = load("https://example.com/embed/admin/test/1", fixture);
        try { assertEquals("1", evaluate(other, "probe('sandDetect','data:application/pdf;base64,aG1t')")); }
        finally { instrumentation().runOnMainSync(other::destroy); }
    }

    @Test public void iframeErrorsAreNeverSuppressed() throws Exception {
        String encoded = android.util.Base64.encodeToString(fixture(script()).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        WebView web = load("https://embed.st/embed/admin/test/1", "<!doctype html><html><body><iframe id='frame'></iframe><script>document.getElementById('frame').srcdoc=atob('" + encoded + "');</script></body></html>");
        try {
            // srcdoc inherits this origin, but it is still framed and must be left alone.
            assertEquals("1", evaluate(web, "document.getElementById('frame').contentWindow.probe('sandDetect','data:application/pdf;base64,aG1t')"));
        } finally { instrumentation().runOnMainSync(web::destroy); }
    }

    @Test public void sportsStartupWaitsForPlayerAndNeverOverridesLaterPause() throws Exception {
        String start = script("sports-start.js");
        WebView web = load("https://embed.st/embed/admin/test/1", startupFixture(start, "", ""));
        try {
            assertEquals("0", evaluate(web, "calls"));
            evaluate(web, "window.jwplayer=function(id){target=id;return {getState:function(){return playerState},play:function(){calls++;playerState='buffering'}}};runTimers(750)");
            assertEquals("\"player,1\"", evaluate(web, "[target,calls].join(',')"));
            // Reinjection is idempotent, and later timer slots cannot undo pause.
            evaluate(web, start);
            evaluate(web, "playerState='paused';runTimers(30000)");
            assertEquals("1", evaluate(web, "calls"));
            assertEquals("0", evaluate(web, "pendingTimers()"));
            assertEquals("true", evaluate(web, "window.__flipflixSportsStart.play()"));
            assertEquals("2", evaluate(web, "calls"));
            evaluate(web, "window.__flipflixSportsStart.play()");
            assertEquals("Buffering must not trigger another start", "2", evaluate(web, "calls"));
        } finally { instrumentation().runOnMainSync(web::destroy); }
    }

    @Test public void sportsStartupClicksOnlyTheObservedProviderPlayButton() throws Exception {
        String buttons = "<button onclick='ads++' aria-label='Play'>Play ad</button>" +
            "<div id='player'><button onclick='wrong++' aria-label='Play'>Other play</button>" +
            "<div class='jw-display-icon-display'><div role='button' aria-label='Play' class='jw-icon-display' " +
            "style='width:80px;height:80px' onclick='calls++'>Play</div></div></div>";
        WebView web = load("https://embed.st/embed/admin/test/1", startupFixture(script("sports-start.js"), buttons, ""));
        try {
            assertEquals("\"1,0,0\"", evaluate(web, "[calls,ads,wrong].join(',')"));
            evaluate(web, "runTimers(30000)");
            assertEquals("1", evaluate(web, "calls"));
        } finally { instrumentation().runOnMainSync(web::destroy); }
    }

    @Test public void sportsStartupIsBoundedScopedAndCanBeCancelled() throws Exception {
        String start = script("sports-start.js");
        for (String url : new String[]{"https://example.com/embed/test", "https://embed.st/ad.html", "http://embed.st/embed/a"}) {
            WebView other = load(url, startupFixture(start, "", ""));
            try { assertEquals("\"undefined\"", evaluate(other, "typeof window.__flipflixSportsStart")); }
            finally { instrumentation().runOnMainSync(other::destroy); }
        }
        String encoded = android.util.Base64.encodeToString(startupFixture(start, "", "").getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        WebView framed = load("https://embed.st/embed/admin/test/1", "<iframe id='child'></iframe><script>document.getElementById('child').srcdoc=atob('" + encoded + "')</script>");
        try { assertEquals("\"undefined\"", evaluate(framed, "typeof document.getElementById('child').contentWindow.__flipflixSportsStart")); }
        finally { instrumentation().runOnMainSync(framed::destroy); }

        WebView web = load("https://embed.st/embed/admin/test/1", startupFixture(start, "", ""));
        try {
            evaluate(web, "runTimers(30000)");
            assertEquals("0", evaluate(web, "pendingTimers()"));
            assertEquals("No provider means no clicks", "0", evaluate(web, "calls"));
            evaluate(web, "window.jwplayer=function(){return {getState:function(){return 'idle'},play:function(){calls++;return Promise.reject(new Error('autoplay refused'))}}};window.__flipflixSportsStart.play()");
            assertEquals("1", evaluate(web, "calls"));
            assertEquals("0", evaluate(web, "pendingTimers()"));
            evaluate(web, "window.dispatchEvent(new Event('pagehide'));window.__flipflixSportsStart.play()");
            assertEquals("Page exit stops all starts", "1", evaluate(web, "calls"));
        } finally { instrumentation().runOnMainSync(web::destroy); }
    }

    @Test public void nativeSportsPlayWorksBeforeAnyVideoMessageAndPauseCancelsAutostart() throws Exception {
        WebView web = load("https://embed.st/embed/admin/test/1", startupFixture(script("sports-start.js"), "", ""), true);
        EmbeddedPlayback[] bridge = new EmbeddedPlayback[1];
        try {
            instrumentation().runOnMainSync(() -> {
                bridge[0] = new EmbeddedPlayback(web);
                assertFalse(bridge[0].state().available);
                assertTrue("Sports navigation at " + web.getUrl(), bridge[0].state().canStart);
                bridge[0].command("pause", "");
            });
            evaluate(web, "window.jwplayer=function(){return {getState:function(){return 'idle'},play:function(){calls++}}};runTimers(30000)");
            assertEquals("Explicit pause cancels startup", "0", evaluate(web, "calls"));
            instrumentation().runOnMainSync(() -> bridge[0].command("play", ""));
            assertEquals("1", evaluate(web, "calls"));
            instrumentation().runOnMainSync(() -> bridge[0].command("toggle", ""));
            assertEquals("2", evaluate(web, "calls"));
        } finally { instrumentation().runOnMainSync(web::destroy); }
    }

    // Deterministic clock advances all readiness checks without a 21-second
    // sleep. The actual script still runs in the emulator's real WebView.
    private String startupFixture(String start, String body, String provider) {
        return "<!doctype html><html><head><script>var calls=0,ads=0,wrong=0,target='',playerState='idle',timers=[];" +
            "window.setTimeout=function(fn,delay){timers.push({fn:fn,delay:delay,live:true});return timers.length};" +
            "window.clearTimeout=function(id){if(timers[id-1])timers[id-1].live=false};" +
            "function runTimers(limit){timers.forEach(function(t){if(t.live&&t.delay<=limit){t.live=false;t.fn()}})}" +
            "function pendingTimers(){return timers.filter(function(t){return t.live}).length}" +
            "</script><script>" + start + "</script></head><body>" +
            (body.isEmpty() ? "<div id='player'></div>" : body) + "<script>" + provider + "</script></body></html>";
    }

    private String fixture(String compat) {
        return "<!doctype html><html><head><script>" + compat + "</script></head><body><p id='status'>fixture</p><script>" +
            "function probe(id,data){var n=0,o=document.createElement('object');o.id=id;o.setAttribute('data',data);o.onerror=function(e){if(!e.isTrusted)n++};document.body.appendChild(o);o.dispatchEvent(new Event('error'));o.remove();return n;}" +
            "</script></body></html>";
    }

    private WebView load(String url, String html) throws Exception {
        return load(url, html, false);
    }
    private WebView load(String url, String html, boolean navigate) throws Exception {
        WebView[] result = new WebView[1]; CountDownLatch loaded = new CountDownLatch(1);
        instrumentation().runOnMainSync(() -> {
            result[0] = new WebView(rule.getActivity());
            result[0].getSettings().setJavaScriptEnabled(true);
            result[0].setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String page) { loaded.countDown(); }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (!navigate) return null;
                    String body = request.isForMainFrame() && url.equals(request.getUrl().toString()) ? html : "";
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                }
            });
            rule.getActivity().setContentView(result[0]);
            if (navigate) result[0].loadUrl(url);
            else result[0].loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
        });
        assertTrue("fixture loads", loaded.await(5, TimeUnit.SECONDS));
        return result[0];
    }

    private String evaluate(WebView web, String expression) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        instrumentation().runOnMainSync(() -> web.evaluateJavascript(expression, value -> { result.set(value); done.countDown(); }));
        assertTrue("JavaScript response", done.await(3, TimeUnit.SECONDS));
        return result.get();
    }
}
