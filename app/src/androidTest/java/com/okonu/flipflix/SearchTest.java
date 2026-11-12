package com.okonu.flipflix;

import android.app.Instrumentation;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Search through the real Activity, including request routing and remote input. */
@RunWith(AndroidJUnit4.class)
public final class SearchTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private CatalogApi testApi;
    private EditText query;
    private final List<String> paths = new ArrayList<>();
    private final List<CatalogApi.Result> callbacks = new ArrayList<>();
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private Object field(Object owner, String name) {
        try { java.lang.reflect.Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void open(String name) {
        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("openSection", String.class);
            m.setAccessible(true); m.invoke(rule.getActivity(), name);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private View root() { return rule.getActivity().getWindow().getDecorView(); }
    private <T extends View> T find(View view, Class<T> type, String label) {
        if (type.isInstance(view) && (label == null || view instanceof TextView && label.contentEquals(((TextView) view).getText())))
            return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type, label); if (found != null) return found;
        }
        return null;
    }
    private boolean shows(String label) { return find(root(), TextView.class, label) != null; }
    @SuppressWarnings("unchecked") private List<List<View>> rows() { return (List<List<View>>) field(rule.getActivity(), "focusRows"); }
    private void setup(boolean live) {
        instrumentation().setInTouchMode(false);
        main(() -> {
            testApi = live ? new CatalogApi() : new CatalogApi() {
                @Override void object(String path, Result result) { paths.add(path); callbacks.add(result); }
                @Override void image(ImageView view, String path, boolean sports, int width) { }
                @Override void imageUrl(ImageView view, String path, Runnable ready) { if (ready != null) ready.run(); }
            };
            try {
                java.lang.reflect.Field f = MainActivity.class.getDeclaredField("api"); f.setAccessible(true);
                ((CatalogApi) f.get(rule.getActivity())).close(); f.set(rule.getActivity(), testApi);
            } catch (Exception e) { throw new AssertionError(e); }
            open("Search"); query = find(root(), EditText.class, null); assertNotNull(query);
        });
    }
    @After public void close() { main(() -> { if (testApi != null) testApi.close(); }); }
    private void submit(String text) { main(() -> { query.setText(text); query.onEditorAction(EditorInfo.IME_ACTION_SEARCH); }); }
    private JSONObject item(int id, String kind, String title) throws Exception {
        return new JSONObject().put("id", id).put("media_type", kind).put(kind.equals("movie") ? "title" : "name", title)
            .put("release_date", "2025-01-01").put("first_air_date", "2025-01-01");
    }
    private JSONObject result(JSONObject... items) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject item : items) array.put(item);
        return new JSONObject().put("results", array);
    }
    private void await(String message, java.util.function.BooleanSupplier condition) {
        AtomicBoolean ready = new AtomicBoolean(); long end = SystemClock.uptimeMillis() + 25000;
        do { main(() -> ready.set(condition.getAsBoolean())); if (ready.get()) return; SystemClock.sleep(50); }
        while (SystemClock.uptimeMillis() < end);
        fail(message);
    }
    private void key(int code) { instrumentation().sendKeyDownUpSync(code); instrumentation().waitForIdleSync(); }

    @Test public void usesCurrentRouteAndOnlyShowsPlayableKinds() throws Exception {
        setup(false); submit("  leave & return  ");
        JSONObject response = result(item(11, "person", "Not a movie"), item(12, "movie", "Leave movie"), item(13, "tv", "Leave show"));
        main(() -> {
            Uri request = Uri.parse(paths.get(0));
            assertEquals("/api/tmdb/search/multi", request.getPath());
            assertEquals("leave & return", request.getQueryParameter("query"));
            assertEquals("false", request.getQueryParameter("include_adult"));
            assertNull(request.getQueryParameter("q"));
            callbacks.get(0).done(response, null);
            assertFalse(shows("Not a movie")); assertTrue(shows("Leave movie")); assertTrue(shows("Leave show"));
            assertEquals(2, rows().get(0).size());
        });
    }

    @Test public void newerSearchAndNavigationRejectStaleReplies() throws Exception {
        setup(false); submit("old"); submit("new");
        JSONObject old = result(item(11, "movie", "Old result")), recent = result(item(12, "movie", "New result"));
        main(() -> {
            callbacks.get(1).done(recent, null); callbacks.get(0).done(old, null);
            assertTrue(shows("New result")); assertFalse(shows("Old result"));
        });
        submit("late");
        main(() -> {
            open("Movies");
            assertEquals("Movies", field(rule.getActivity(), "section"));
            callbacks.get(2).done(null, new java.io.IOException("Offline"));
            assertFalse(shows("Search unavailable. Select Search to try again."));
        });
    }

    @Test public void failureKeepsQueryAndRetryHandlesEmptyAndMalformedResults() throws Exception {
        setup(false); submit("leave");
        main(() -> {
            callbacks.get(0).done(null, new java.io.IOException("HTTP 404"));
            assertEquals("leave", query.getText().toString());
            assertTrue(shows("Search unavailable. Select Search to try again."));
            find(root(), Button.class, "Search").performClick();
            callbacks.get(1).done(new JSONObject(), null);
            assertTrue(shows("Search unavailable. Select Search to try again."));
        });
        JSONObject empty = result(item(15, "person", "Person only"));
        main(() -> {
            find(root(), Button.class, "Search").performClick(); callbacks.get(2).done(empty, null);
            assertTrue(shows("No results")); assertEquals("leave", query.getText().toString());
        });
    }

    @Test public void remoteSubmitsAndOpensSeriesAsSeries() throws Exception {
        setup(false);
        main(() -> { query.setText("leave"); assertTrue(find(root(), Button.class, "Search").requestFocus()); });
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        JSONObject response = result(item(13, "tv", "Leave show"));
        main(() -> { assertEquals(1, paths.size()); callbacks.get(0).done(response, null); });
        await("Result receives remote focus", () -> !rows().isEmpty() && rows().get(0).get(0).hasFocus());
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> {
            Object selected = field(rule.getActivity(), "selected");
            assertEquals("tv", field(selected, "kind")); assertEquals(13, field(selected, "id"));
            assertTrue(paths.contains("/api/tmdb/tv/13"));
        });
    }

    @Test public void liveSearchReturnsLeaveResults() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSearch")));
        setup(true); submit("leave");
        await("Real search must return a title grid instead of Search unavailable", () -> !shows("Searching..."));
        main(() -> {
            assertTrue("Actual search results expected", shows("Leave the World Behind"));
            assertTrue("Actual TV result expected", shows("Leave It to Beaver"));
            assertFalse(rows().isEmpty()); assertTrue(rows().get(0).get(0).hasFocus());
            android.util.Log.i("DarkflixSearchTest", "query=leave movie=true tv=true resultRows=" + rows().size());
        });
        instrumentation().waitForIdleSync(); SystemClock.sleep(1500);
        android.graphics.Bitmap screenshot = instrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream("/sdcard/Download/darkflix-search-fixed.png")) {
            assertNotNull(screenshot); screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { if (screenshot != null) screenshot.recycle(); }
    }
}
