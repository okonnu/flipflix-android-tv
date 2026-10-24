package com.okonu.flipflix;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Rift's documented message API. No stream scraping, DOM scans, or state polling. */
final class RiftPlayback implements PlaybackBackend {
    interface Notice { void accept(String text); }
    static final String ORIGIN = "https://com.okonu.flipflix";
    private final WebView web;
    private final Notice notice;
    private final Runnable providerControls, exit;
    private final String session = UUID.randomUUID().toString();
    private State latest = new State();
    private boolean closed, fill;

    static boolean supported() { return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER); }

    RiftPlayback(WebView web, Notice notice, Runnable providerControls, Runnable exit) {
        this.web = web; this.notice = notice; this.providerControls = providerControls; this.exit = exit;
        WebViewCompat.addWebMessageListener(web, "FlipflixRift", Collections.singleton(ORIGIN),
            (view, message, origin, mainFrame, reply) -> {
                if (!closed && view == web && mainFrame && ORIGIN.equals(origin.toString())) receive(message.getData());
            });
    }

    void load(String url, int id, String kind, int season, int episode) {
        try (InputStream in = web.getContext().getAssets().open("rift-player.html");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            JSONObject config = new JSONObject().put("url", url + "?uiScale=1.5&fs=parent&exit=1&hide=next,episodes")
                .put("id", id).put("kind", kind).put("season", season).put("episode", episode).put("session", session);
            latest.providerControls = true;
            web.loadDataWithBaseURL(ORIGIN + "/rift/", out.toString("UTF-8").replace("__RIFT_CONFIG__", config.toString()), "text/html", "UTF-8", null);
        } catch (Exception failure) { notice.accept("Rift could not load. Choose Retry or another source."); }
    }

    void receive(String message) {
        if (closed || message == null || message.length() > 131072) return;
        try {
            JSONObject envelope = new JSONObject(message);
            if (!session.equals(envelope.optString("session"))) return;
            JSONObject data = envelope.getJSONObject("event");
            String type = data.optString("type");
            if (type.equals("vidrift:state")) {
                State next = new State();
                next.available = true; next.canStart = true; next.providerControls = true;
                next.position = millis(data.optDouble("currentTime")); next.duration = millis(data.optDouble("duration"));
                next.seekable = next.duration > 0; next.paused = data.optBoolean("paused", true);
                next.muted = data.optBoolean("muted"); next.canResize = true; next.fillScreen = fill;
                options(data, "subtitles", "subtitle", next.subtitles);
                options(data, "qualities", "quality", next.qualities);
                for (String[] pair : new String[][]{{"audios","audio"},{"servers","server"},{"sources","source"}}) {
                    List<Option> choices = new ArrayList<>(); options(data, pair[0], pair[1], choices);
                    if (!choices.isEmpty()) next.settings.put(pair[1], choices);
                }
                next.settings.put("speed", numbers(.25, 4, .25, data.optDouble("speed", 1), "x"));
                next.settings.put("volume", numbers(0, 1, .1, data.optDouble("volume", 1), ""));
                next.settings.put("subtitleOffset", numbers(-30, 30, .5, data.optDouble("subtitleOffset"), "s"));
                JSONObject style = data.optJSONObject("subtitleStyle");
                if (style != null) {
                    next.settings.put("subtitleSize", numbers(50, 200, 25, style.optDouble("size",100), "%"));
                    next.settings.put("subtitlePosition", numbers(0, 40, 5, style.optDouble("position"), "%"));
                    next.settings.put("subtitleColor", named(new String[]{"#ffffff","#ffff00","#00ffff","#00ff00"}, new String[]{"White","Yellow","Cyan","Green"}, style.optString("color")));
                    next.settings.put("subtitleBackground", named(new String[]{"box","shadow"}, new String[]{"Box","Shadow"}, style.optString("background")));
                    next.settings.put("subtitleFont", named(new String[]{"default","system","serif"}, new String[]{"Default","System","Serif"}, style.optString("font")));
                }
                if (!data.optString("cast", "unavailable").equals("unavailable")) next.settings.put("cast", Collections.emptyList());
                latest = next;
            } else if (type.equals("vidrift:progress") || type.equals("vidrift:paused") || type.equals("vidrift:unpaused") || type.equals("vidrift:ended")) {
                if (data.has("currentTime")) latest.position = millis(data.optDouble("currentTime"));
                if (data.has("duration")) { latest.duration = millis(data.optDouble("duration")); latest.seekable = latest.duration > 0; }
                if (!type.equals("vidrift:progress")) latest.paused = !type.equals("vidrift:unpaused");
            } else if (type.equals("vidrift:command-error")) {
                String reason = data.optString("reason");
                notice.accept(reason.equals("click-required") ? "Rift needs an action in its player first. Open Playback settings > Use Rift player controls."
                    : reason.equals("not-ready") ? "Rift is still preparing this source. Try again when ready."
                    : "Rift could not apply that option (" + reason + "). Reopen the menu for current choices.");
            } else if (type.equals("vidrift:exit")) exit.run();
        } catch (Exception ignored) { }
    }

    private static long millis(double seconds) { return Double.isNaN(seconds) || Double.isInfinite(seconds) ? 0 : (long) (Math.max(0, Math.min(seconds, 864000)) * 1000); }
    private static void options(JSONObject data, String list, String selected, List<Option> into) {
        JSONArray entries = data.optJSONArray(list);
        for (int i = 0; entries != null && i < Math.min(100, entries.length()); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !entry.has("value")) continue;
            String id = entry.optString("value");
            into.add(new Option(id, entry.optString("label", id), id.equals(data.optString(selected))));
        }
    }
    private static List<Option> numbers(double min, double max, double step, double selected, String suffix) {
        List<Option> choices = new ArrayList<>();
        for (int i = 0; i <= Math.round((max - min) / step); i++) {
            double value = min + step * i;
            String id = String.format(java.util.Locale.US, "%.2f", value);
            String label = suffix.isEmpty() ? Math.round(value * 100) + "%" : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + suffix;
            choices.add(new Option(id, label, Math.abs(selected - value) < .01));
        }
        return choices;
    }
    private static List<Option> named(String[] ids, String[] labels, String selected) {
        List<Option> choices = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) choices.add(new Option(ids[i], labels[i], ids[i].equals(selected)));
        return choices;
    }
    @Override public State state() { return latest; }
    @Override public void command(String name, String value) {
        if (closed) return;
        if (name.equals("player")) { providerControls.run(); return; }
        try {
            JSONObject command = new JSONObject();
            if (name.equals("toggle")) name = latest.paused ? "play" : "pause";
            if (name.equals("play") || name.equals("pause") || name.equals("cast")) command.put("type", "vidrift:" + name);
            else if (name.equals("seek")) command.put("type", "vidrift:resume").put("currentTime", Math.max(0, Math.min(latest.duration, Long.parseLong(value))) / 1000.0);
            else if (name.equals("resize")) {
                command.put("type", "vidrift:mobile-option").put("option", "fit").put("value", value.equals("fill") ? "cover" : "contain");
                fill = value.equals("fill"); latest.fillScreen = fill;
            } else {
                command.put("type", "vidrift:set");
                if (name.equals("mute")) command.put("muted", !latest.muted);
                else {
                    List<Option> choices = name.equals("quality") ? latest.qualities : name.equals("subtitle") ? latest.subtitles : latest.settings.get(name);
                    boolean valid = false;
                    if (choices != null) for (Option option : choices) if (option.id.equals(value)) valid = true;
                    if (!valid) { notice.accept("That choice is no longer available. Reopen the menu."); return; }
                    if (name.equals("speed") || name.equals("volume") || name.equals("subtitleOffset")) command.put(name, Double.parseDouble(value));
                    else if (name.startsWith("subtitle") && !name.equals("subtitle")) {
                        String key = name.substring(8).toLowerCase(java.util.Locale.US);
                        command.put("subtitleStyle", new JSONObject().put(key, key.equals("size") || key.equals("position") ? Double.parseDouble(value) : value));
                    } else command.put(name, value);
                }
            }
            web.evaluateJavascript("window.riftCommand && window.riftCommand(" + JSONObject.quote(session) + "," + command + ")", null);
        } catch (Exception ignored) { notice.accept("Rift could not apply that control."); }
    }
    void close() { if (closed) return; closed = true; latest = new State(); WebViewCompat.removeWebMessageListener(web, "FlipflixRift"); }
}
