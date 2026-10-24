package com.okonu.flipflix;

import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import org.json.JSONObject;

/** One owner for provider chrome; controls can be restored without reloading the stream. */
final class RiftChrome implements AutoCloseable {
    private final WebView web;
    private final String session;
    private ScriptHandler script;
    private JavaScriptReplyProxy frame;
    private boolean nativeControls = true, closed;

    RiftChrome(WebView web, String session) {
        this.web = web; this.session = session;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        try (InputStream input = web.getContext().getAssets().open("rift-chrome.js");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int n;
            while ((n = input.read(bytes)) != -1) out.write(bytes, 0, n);
            WebViewCompat.addWebMessageListener(web, "FlipflixRiftChrome", Collections.singleton("https://embed.vidrift.in"),
                (view, message, origin, mainFrame, reply) -> {
                    if (closed || view != web || mainFrame || !"https://embed.vidrift.in".equals(origin.toString())
                        || !session.equals(message.getData())) return;
                    frame = reply; apply();
                });
            script = WebViewCompat.addDocumentStartJavaScript(web,
                out.toString("UTF-8").replace("__SESSION__", JSONObject.quote(session)),
                Collections.singleton("https://embed.vidrift.in"));
        } catch (Exception ignored) {
            WebViewCompat.removeWebMessageListener(web, "FlipflixRiftChrome");
        }
    }
    boolean supported() { return script != null; }
    void nativeControls(boolean enabled) { nativeControls = enabled; apply(); }
    private void apply() {
        if (closed || frame == null) return;
        try { frame.postMessage(new JSONObject().put("session", session).put("native", nativeControls).toString()); }
        catch (Exception ignored) { frame = null; }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; frame = null;
        if (script != null) { script.remove(); script = null; WebViewCompat.removeWebMessageListener(web, "FlipflixRiftChrome"); }
    }
}
