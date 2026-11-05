package com.okonu.flipflix;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;

/** Bolt's documented playback messages, with existing DOM-only options as a fallback. */
final class BoltPlayback implements PlaybackBackend, AutoCloseable {
    static final String ORIGIN = "https://com.okonu.flipflix";
    private final WebView web;
    private final PlaybackBackend fallback;
    private final String session = UUID.randomUUID().toString();
    private final State reported = new State();
    private boolean closed, ready, hasTime;

    static boolean supported() { return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER); }
    BoltPlayback(WebView web, PlaybackBackend fallback) {
        this.web = web; this.fallback = fallback;
        WebViewCompat.addWebMessageListener(web, "DagflixBolt", Collections.singleton(ORIGIN),
            (view, message, origin, mainFrame, reply) -> {
                if (!closed && view == web && mainFrame && ORIGIN.equals(origin.toString())) receive(message.getData());
            });
    }
    void load(String url, long position) {
        load(url, position, false);
    }
    void load(String url, long position, boolean directInput) {
        try (InputStream input = web.getContext().getAssets().open("bolt-player.html");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) out.write(bytes, 0, count);
            // Provider-native startup hint complements the acknowledged seek after readiness.
            url += "&nextEpisodeButton=false&episodesButton=false";
            if (position >= 0) url += "&startAt=" + (position / 1000.0);
            JSONObject config = new JSONObject().put("url", url).put("session", session).put("directInput", directInput);
            web.loadDataWithBaseURL(ORIGIN + "/bolt/", out.toString("UTF-8").replace("__BOLT_CONFIG__", config.toString()), "text/html", "UTF-8", null);
        } catch (Exception failure) { web.loadUrl(url); }
    }
    private static boolean seconds(JSONObject data, String key) {
        double value = data.optDouble(key, Double.NaN);
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0 && value <= 864000;
    }
    void receive(String message) {
        if (closed || message == null || message.length() > 16384) return;
        try {
            JSONObject envelope = new JSONObject(message);
            if (!session.equals(envelope.optString("session"))) return;
            JSONObject data = envelope.getJSONObject("event");
            switch (data.optString("type")) {
                case "ready": ready = true; break;
                case "durationchange":
                    if (seconds(data, "duration")) reported.duration = Math.round(data.getDouble("duration") * 1000);
                    break;
                case "timeupdate":
                    if (seconds(data, "time")) { reported.position = Math.round(data.getDouble("time") * 1000); hasTime = true; }
                    break;
                case "play": ready = true; reported.paused = false; reported.ended = false; break;
                case "pause": reported.paused = true; break;
                case "ended": reported.ended = true; reported.paused = true; break;
                default: return;
            }
            reported.available = (ready || hasTime) && reported.duration > 0;
            reported.canStart = ready; reported.seekable = reported.available;
        } catch (Exception ignored) { }
    }
    @Override public State state() {
        State dom = fallback.state();
        if (!reported.available) return dom;
        // Progress has exactly one owner. Do not replace provider time with a hidden/ad video clock.
        reported.subtitles.clear(); reported.subtitles.addAll(dom.subtitles);
        reported.qualities.clear(); reported.qualities.addAll(dom.qualities);
        reported.settings.clear(); reported.settings.putAll(dom.settings);
        reported.canResize = dom.canResize; reported.fillScreen = dom.fillScreen;
        if (dom.available) reported.muted = dom.muted;
        return reported;
    }
    @Override public void refresh() { fallback.refresh(); }
    @Override public void command(String name, String value) {
        if (closed) return;
        if (!ready && !reported.available) { fallback.command(name, value); return; }
        try {
            if (name.equals("toggle")) name = state().paused ? "play" : "pause";
            JSONObject command = new JSONObject().put("type", name);
            if (name.equals("seek")) command.put("time", Math.max(0, Long.parseLong(value)) / 1000.0);
            else if (name.equals("mute")) {
                reported.muted = !state().muted;
                command.put("type", "volume").put("volume", reported.muted ? 0 : 1);
            } else if (!name.equals("play") && !name.equals("pause")) { fallback.command(name, value); return; }
            web.evaluateJavascript("window.boltCommand && window.boltCommand(" + JSONObject.quote(session) + "," + command + ")", null);
        } catch (Exception ignored) { }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; WebViewCompat.removeWebMessageListener(web, "DagflixBolt");
    }
}
