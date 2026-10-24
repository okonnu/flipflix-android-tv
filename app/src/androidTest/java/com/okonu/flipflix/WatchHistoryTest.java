package com.okonu.flipflix;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WatchHistoryTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private WatchHistory store;
    private SharedPreferences prefs;
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Object field(Object target, String name) {
        try { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private Object invoke(String name, Object... args) {
        try {
            for (java.lang.reflect.Method m : MainActivity.class.getDeclaredMethods()) if (m.getName().equals(name)) { m.setAccessible(true); return m.invoke(rule.getActivity(), args); }
            throw new NoSuchMethodException(name);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    @Before public void setup() {
        prefs = rule.getActivity().getSharedPreferences("watch_history", 0); prefs.edit().clear().commit(); store = new WatchHistory(prefs);
    }
    @After public void cleanup() { main(() -> { invoke("finishRecovery"); prefs.edit().clear().commit(); }); }
    private JSONObject catalog(String kind, int id) {
        try { return new JSONObject().put("id", id).put("media_type", kind).put("title", "History fixture").put("year", "2010").put("vote_average", 8).put("overview", "Test title"); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private PlaybackBackend.State state(long position) {
        PlaybackBackend.State s = new PlaybackBackend.State(); s.available = s.seekable = true; s.paused = false; s.position = position; s.duration = 600000; return s;
    }
    @Test public void moviesEpisodesAndSourcesHaveIndependentPersistentPositions() {
        store.save("movie:12", catalog("movie", 12), 1, 1, 0, state(120000));
        store.save("tv:12:1:1", catalog("tv", 12), 1, 1, 1, state(240000));
        store.save("tv:12:1:2", catalog("tv", 12), 1, 2, 2, state(350000));
        WatchHistory restored = new WatchHistory(prefs);
        assertEquals(120000, restored.get("movie:12").resumePosition());
        assertEquals(240000, restored.get("tv:12:1:1").resumePosition());
        assertEquals(350000, restored.get("tv:12:1:2").resumePosition());
        store.save("movie:12", catalog("movie", 12), 1, 1, 1, state(150000));
        assertEquals(3, store.all().size()); assertEquals(1, store.get("movie:12").source);
        assertEquals("", WatchHistory.key("sport", 12, 1, 1));
    }
    @Test public void completedAndRestartedTitlesStayInHistoryButDoNotResume() {
        store.save("movie:12", catalog("movie", 12), 1, 1, 1, state(590000));
        assertEquals(0, store.get("movie:12").resumePosition()); assertTrue(store.get("movie:12").finished);
        store.save("movie:12", catalog("movie", 12), 1, 1, 1, state(200000));
        store.startOver("movie:12");
        assertNotNull(store.get("movie:12")); assertEquals(0, store.get("movie:12").resumePosition());
    }
    @Test public void historyIsBoundedAndDropsOldestOnlyWhenAddingNewTitles() throws Exception {
        SharedPreferences.Editor edit = prefs.edit();
        for (int i = 1; i <= WatchHistory.LIMIT; i++) edit.putString("movie:" + i,
            new JSONObject().put("catalog", catalog("movie", i)).put("position", 120000).put("duration", 600000).put("updated", i).toString());
        edit.commit();
        store.save("movie:301", catalog("movie", 301), 1, 1, 1, state(120000));
        assertEquals(WatchHistory.LIMIT, store.all().size()); assertNull(store.get("movie:1"));
        assertEquals("movie:301", store.all().get(0).key);
        store.save("movie:301", catalog("movie", 301), 1, 1, 1, state(125000));
        assertEquals(WatchHistory.LIMIT, store.all().size()); assertNotNull(store.get("movie:2"));
    }
    @Test public void loadingCannotOverwriteBookmarkAndSeekWaitsForMetadata() {
        main(() -> {
            store.save("movie:12", catalog("movie", 12), 1, 1, 1, state(120000));
            class Backend implements PlaybackBackend {
                State s = new State(); long commanded = -1; int seeks;
                public State state() { return s; }
                public void command(String name, String value) { if (name.equals("seek")) { seeks++; commanded = Long.parseLong(value); } }
            }
            Backend b = new Backend();
            PlaybackRecovery recovery = new PlaybackRecovery(store, "movie:12", catalog("movie", 12), 1, 1, 1, 120000, () -> b, message -> {});
            long now = SystemClock.uptimeMillis(); recovery.sample(now);
            assertEquals(0, b.seeks); assertEquals(120000, store.get("movie:12").position);
            b.s = state(0); recovery.sample(now + 1000);
            assertEquals(120000, b.commanded); assertEquals(120000, recovery.restartPosition());
            recovery.save(); assertEquals(120000, store.get("movie:12").position);
            b.s.position = 121000; recovery.sample(now + 2000);
            b.s.position = 145000; recovery.close();
            assertEquals(145000, store.get("movie:12").position);
        });
    }
    @Test public void failedRestoreIsBoundedAndPreservesOldProgress() {
        main(() -> {
            store.save("movie:12", catalog("movie", 12), 1, 1, 1, state(120000));
            final int[] calls = {0}, notices = {0};
            PlaybackBackend b = new PlaybackBackend() { public State state() { return WatchHistoryTest.this.state(1000); } public void command(String n, String v) { calls[0]++; } };
            PlaybackRecovery r = new PlaybackRecovery(store, "movie:12", catalog("movie", 12), 1, 1, 1, 120000, () -> b, message -> notices[0]++);
            long now = SystemClock.uptimeMillis(); r.sample(now); r.sample(now + 4001); r.sample(now + 8002); r.sample(now + 17000); r.close();
            assertEquals(3, calls[0]); assertEquals(1, notices[0]); assertEquals(120000, store.get("movie:12").position);
        });
    }
    @Test public void historyIconIsReachableAndEntryResumesCorrectEpisodeDirectly() {
        main(() -> {
            store.save("tv:12:2:3", catalog("tv", 12), 2, 3, 0, state(240000));
            invoke("openSection", "Home");
            DiscoveryView home = (DiscoveryView) field(rule.getActivity(), "discovery");
            NavigationRail rail = (NavigationRail) field(home, "rail"); rail.buttons.get(3).requestFocus();
            home.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            assertEquals("History", rule.getActivity().getCurrentFocus().getContentDescription());
            rail.buttons.get(4).performClick();
            DiscoveryView history = (DiscoveryView) field(rule.getActivity(), "discovery");
            DiscoveryItem card = history.rows.get(0).items.get(0);
            assertTrue(card.history); assertEquals(240000, card.resumePosition); assertTrue(card.title.contains("S2 E3"));
            invoke("openDiscoveryItem", card);
            WebView web = (WebView) field(rule.getActivity(), "player"); web.stopLoading(); web.loadUrl("about:blank");
            assertEquals(true, field(rule.getActivity(), "playerOpen")); assertNull(field(rule.getActivity(), "resumeDialog"));
            assertEquals(2, field(rule.getActivity(), "season")); assertEquals(3, field(rule.getActivity(), "episode"));
            assertEquals(240000, ((PlaybackRecovery) field(rule.getActivity(), "recovery")).restartPosition());
            invoke("closePlayer"); assertEquals("History", field(rule.getActivity(), "section"));
            assertNotNull(field(rule.getActivity(), "discovery"));
        });
    }
    @Test public void catalogOffersResumeOrStartOverWithoutOpeningPlayerUntilChosen() throws Exception {
        JSONObject json = catalog("movie", 12);
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, json, "movie");
        main(() -> {
            store.save("movie:12", json, 1, 1, 1, state(120000));
            invoke("showPlayer", item, 1, 1, 0);
            assertEquals(false, field(rule.getActivity(), "playerOpen"));
            AlertDialog dialog = (AlertDialog) field(rule.getActivity(), "resumeDialog");
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().contains("2:00"));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        main(() -> {
            WebView web = (WebView) field(rule.getActivity(), "player"); web.stopLoading(); web.loadUrl("about:blank");
            assertEquals(true, field(rule.getActivity(), "playerOpen")); assertEquals(0, store.get("movie:12").resumePosition());
            assertEquals(0, ((PlaybackRecovery) field(rule.getActivity(), "recovery")).restartPosition());
            invoke("closePlayer");
        });
    }
    @Test public void liveRiftRestoresSavedPosition() throws Exception {
        String live = InstrumentationRegistry.getArguments().getString("liveRift");
        org.junit.Assume.assumeTrue("Optional live provider diagnostic", live != null);
        int id = Integer.parseInt(live); JSONObject json = catalog("movie", id).put("title", "Sintel resume check");
        Class<?> type = Class.forName("com.okonu.flipflix.MainActivity$Item");
        java.lang.reflect.Method from = type.getDeclaredMethod("from", JSONObject.class, String.class); from.setAccessible(true);
        Object item = from.invoke(null, json, "movie");
        main(() -> {
            store.save("movie:" + id, json, 1, 1, 1, state(120000));
            invoke("showPlayerAtPosition", item, 1, 1, 1, 120000L);
        });
        java.util.concurrent.atomic.AtomicBoolean restored = new java.util.concurrent.atomic.AtomicBoolean();
        long end = SystemClock.uptimeMillis() + 60000;
        while (!restored.get() && SystemClock.uptimeMillis() < end) {
            main(() -> {
                RiftPlayback rift = (RiftPlayback) field(rule.getActivity(), "riftPlayer");
                if (rift != null) { PlaybackBackend.State s = rift.state(); restored.set(s.available && s.position >= 118000 && s.position < 155000 && !s.paused); }
            });
            if (!restored.get()) SystemClock.sleep(250);
        }
        main(() -> {
            RiftPlayback rift = (RiftPlayback) field(rule.getActivity(), "riftPlayer");
            if (rift != null) android.util.Log.i("RecoveryProbe", "position=" + rift.state().position + " duration=" + rift.state().duration + " paused=" + rift.state().paused);
            invoke("closePlayer");
        });
        assertTrue("Rift actually played from the requested two-minute bookmark", restored.get());
        assertTrue(store.get("movie:" + id).resumePosition() >= 118000);
    }
}
