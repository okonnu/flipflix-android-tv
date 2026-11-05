package com.okonu.flipflix;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Device-only history; movies and each individual episode have independent positions. */
final class WatchHistory {
    static final int LIMIT = 300;
    private final SharedPreferences prefs;
    WatchHistory(SharedPreferences prefs) { this.prefs = prefs; }
    static String key(String kind, int id, int season, int episode) {
        if (id <= 0 || (!kind.equals("movie") && !kind.equals("tv"))) return "";
        return kind + ":" + id + (kind.equals("tv") ? ":" + season + ":" + episode : "");
    }
    static final class Entry {
        final String key;
        final JSONObject catalog;
        final int season, episode, source;
        final long position, duration, updated;
        final boolean finished;
        Entry(String key, JSONObject value) throws Exception {
            this.key = key; catalog = value.getJSONObject("catalog");
            season = value.optInt("season", 1); episode = value.optInt("episode", 1);
            source = PlaybackChoice.normalize(value.optInt("source"));
            position = Math.max(0, value.optLong("position")); duration = Math.max(0, value.optLong("duration"));
            updated = value.optLong("updated"); finished = value.optBoolean("finished");
        }
        long resumePosition() { return !finished && position >= 10000 && duration > position + 30000 ? position : 0; }
    }
    Entry get(String key) {
        if (key.isEmpty()) return null;
        try { return new Entry(key, new JSONObject(prefs.getString(key, ""))); }
        catch (Exception ignored) { return null; }
    }
    List<Entry> all() {
        List<Entry> entries = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) { Entry entry = get(key); if (entry != null) entries.add(entry); }
        java.util.Collections.sort(entries, (a, b) -> Long.compare(b.updated, a.updated));
        return entries;
    }
    void save(String key, JSONObject catalog, int season, int episode, int source, PlaybackBackend.State state) {
        if (key.isEmpty() || !state.available || state.duration < 60000 || state.position <= 0) return;
        long position = Math.min(state.position, state.duration);
        Entry previous = get(key);
        boolean finished = state.ended || state.duration - position <= 30000;
        if (previous != null && previous.position == position && previous.duration == state.duration && previous.source == source && previous.finished == finished) return;
        try {
            JSONObject json = new JSONObject().put("catalog", catalog).put("season", season).put("episode", episode)
                .put("source", source).put("position", position).put("duration", state.duration).put("finished", finished)
                .put("updated", System.currentTimeMillis());
            SharedPreferences.Editor edit = prefs.edit().putString(key, json.toString());
            // Normal five-second saves touch one record, not the entire history.
            if (previous == null && prefs.getAll().size() >= LIMIT) {
                List<Entry> entries = all();
                int kept = 1;
                for (Entry entry : entries) if (!entry.key.equals(key) && ++kept > LIMIT) edit.remove(entry.key);
            }
            edit.apply();
        } catch (Exception ignored) { }
    }
    void startOver(String key) {
        Entry entry = get(key); if (entry == null) return;
        try {
            JSONObject json = new JSONObject(prefs.getString(key, ""));
            json.put("position", 0).put("finished", false);
            prefs.edit().putString(key, json.toString()).apply();
        } catch (Exception ignored) { }
    }
    static String time(long millis) {
        long sec = Math.max(0, millis) / 1000;
        return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
            : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }
}
