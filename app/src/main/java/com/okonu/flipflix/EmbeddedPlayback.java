package com.okonu.flipflix;

import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A message-only bridge. Pages cannot call Android APIs, load URLs, or open files. */
final class EmbeddedPlayback implements PlaybackBackend {
    private final WebView webView;
    private State latest = new State();
    private JavaScriptReplyProxy frame;
    private String script = "";
    final boolean documentStart;
    private final boolean messaging;

    EmbeddedPlayback(WebView view) {
        webView = view;
        messaging = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        documentStart = messaging && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (!messaging) return;
        try (InputStream input = view.getContext().getAssets().open("playback-bridge.js");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            script = out.toString("UTF-8");
        } catch (Exception ignored) { return; }
        Set<String> origins = new HashSet<>(Arrays.asList("https://vidbolt.xyz", "https://*.vidbolt.xyz",
            "https://bingr.one", "https://*.bingr.one", "https://embed.vidrift.in", "https://embed.st"));
        WebViewCompat.addWebMessageListener(view, "FlipflixPlayback", origins, (v, message, origin, mainFrame, reply) -> {
            String data = message.getData();
            if (data == null || data.length() > 65536) return;
            try {
                JSONObject json = new JSONObject(data);
                State next = new State();
                next.available = json.optBoolean("available");
                // Ignore loading / advertising frames while a full-length movie is playing.
                if (frame != null && frame != reply && latest.available && next.available
                    && latest.duration > 60000 && json.optLong("duration") < 60000) return;
                next.seekable = json.optBoolean("seekable"); next.paused = json.optBoolean("paused", true);
                next.muted = json.optBoolean("muted"); next.position = Math.max(0, json.optLong("position"));
                next.duration = Math.max(0, json.optLong("duration"));
                readOptions(json.optJSONArray("subtitles"), next.subtitles);
                readOptions(json.optJSONArray("qualities"), next.qualities);
                latest = next; frame = reply;
            } catch (Exception ignored) { }
        });
        if (documentStart) WebViewCompat.addDocumentStartJavaScript(view, script, origins);
    }

    private static void readOptions(JSONArray options, java.util.List<Option> into) {
        for (int i = 0; options != null && i < Math.min(100, options.length()); i++) {
            JSONObject o = options.optJSONObject(i);
            if (o != null) into.add(new Option(o.optString("id"), o.optString("label"), o.optBoolean("selected")));
        }
    }
    void reset() { latest = new State(); frame = null; }
    void pageFinished() {
        if (messaging && !documentStart && !script.isEmpty()) webView.evaluateJavascript(script, null);
    }
    @Override public State state() { return latest; }
    @Override public void refresh() { command("state", ""); }
    @Override public void command(String name, String value) {
        if (frame == null) return;
        try { frame.postMessage(new JSONObject().put("name", name).put("value", value).toString()); }
        catch (Exception ignored) { reset(); }
    }
}
