package com.okonu.flipflix;

import android.net.Uri;
import android.webkit.WebView;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;

/** One source-load lease. Observe ordinary media loading; never decode provider payloads. */
final class StreamDiscovery {
    interface Listener { void found(Stream stream); }
    static final class Stream {
        final String url, referer, agent;
        final JSONArray captions;
        final long duration;
        Stream(String url, String referer, String agent, JSONArray captions, long duration) {
            this.url = url; this.referer = referer; this.agent = agent; this.captions = captions; this.duration = duration;
        }
    }
    private final WebView web;
    private final String session = UUID.randomUUID().toString();
    private ScriptHandler script;
    private boolean registered, closed, delivered;

    StreamDiscovery(WebView web, String sourceUrl, Listener listener) {
        this(web, sourceUrl, false, listener);
    }

    StreamDiscovery(WebView web, String sourceUrl, boolean resolverOnly, Listener listener) {
        this.web = web;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        // Muting the entire WebView also covers cross-origin child frames and
        // Web Audio. Never let a hidden resolver become an audible web player.
        if (resolverOnly) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) return;
            WebViewCompat.setAudioMuted(web, true);
        }
        Uri source = Uri.parse(sourceUrl);
        String origin = source.getScheme() + "://" + source.getAuthority();
        try (InputStream in = web.getContext().getAssets().open("stream-discovery.js"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            WebViewCompat.addWebMessageListener(web, "DarkflixStream", Collections.singleton(origin), (v, message, sender, main, reply) -> {
                if (closed || delivered || !origin.equals(sender.toString()) || message.getData() == null || message.getData().length() > 65536) return;
                try {
                    JSONObject data = new JSONObject(message.getData());
                    if (!session.equals(data.optString("session"))) return;
                    Stream stream = parse(data, origin, web.getSettings().getUserAgentString());
                    if (stream == null) return;
                    delivered = true;
                    // Stop the actual frame before native playback, avoiding double audio.
                    reply.postMessage("stop");
                    listener.found(stream);
                } catch (Exception ignored) { }
            });
            registered = true;
            script = WebViewCompat.addDocumentStartJavaScript(web, out.toString("UTF-8").replace("__SESSION__", session), Collections.singleton(origin));
        } catch (Exception ignored) { close(); }
    }

    boolean active() { return registered && script != null && !closed; }

    static Stream parse(JSONObject data, String origin, String agent) {
        long duration = data.optLong("duration");
        String url = data.optString("url"), page = data.optString("page");
        if (data.optBoolean("encrypted") || duration < 60000 || duration > 86400000 || !mediaUrl(url)) return null;
        Uri p = Uri.parse(page);
        if (!(p.getScheme() + "://" + p.getAuthority()).equals(origin)) return null;
        // Credentials are neither persisted nor guessed. Signed URLs remain in memory only.
        return new Stream(url, page.split("#")[0], agent, data.optJSONArray("captions"), duration);
    }
    static boolean mediaUrl(String value) {
        if (value == null || value.length() > 16384 || value.contains("\n") || value.contains("\r")) return false;
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null && !host.isEmpty()
            && uri.getUserInfo() == null && !host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("::1");
    }
    void close() {
        if (closed) return;
        closed = true;
        if (script != null) script.remove();
        if (registered) WebViewCompat.removeWebMessageListener(web, "DarkflixStream");
    }
}
