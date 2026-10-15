package com.okonu.flipflix;

import android.app.Instrumentation;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.ByteArrayOutputStream;
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
        try (InputStream input = rule.getActivity().getAssets().open("sports-compat.js");
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

    private String fixture(String compat) {
        return "<!doctype html><html><head><script>" + compat + "</script></head><body><p id='status'>fixture</p><script>" +
            "function probe(id,data){var n=0,o=document.createElement('object');o.id=id;o.setAttribute('data',data);o.onerror=function(e){if(!e.isTrusted)n++};document.body.appendChild(o);o.dispatchEvent(new Event('error'));o.remove();return n;}" +
            "</script></body></html>";
    }

    private WebView load(String url, String html) throws Exception {
        WebView[] result = new WebView[1]; CountDownLatch loaded = new CountDownLatch(1);
        instrumentation().runOnMainSync(() -> {
            result[0] = new WebView(rule.getActivity());
            result[0].getSettings().setJavaScriptEnabled(true);
            result[0].setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String page) { loaded.countDown(); }
            });
            rule.getActivity().setContentView(result[0]);
            result[0].loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
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
