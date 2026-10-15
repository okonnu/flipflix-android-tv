package com.okonu.flipflix;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class CatalogFiltersTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private FakeApi fake;
    private Map<String, ?> saved;
    private MainActivity activity() { return rule.getActivity(); }
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private void key(int code) { instrumentation().sendKeyDownUpSync(code); instrumentation().waitForIdleSync(); }
    private Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void set(String name, Object value) {
        try { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity(), value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private Object invoke(String name, Object... args) {
        try {
            for (Method method : MainActivity.class.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                method.setAccessible(true); return method.invoke(activity(), args);
            }
            throw new NoSuchMethodException(name);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private List<Button> buttons() { return (List<Button>) field(activity(), "filterButtons"); }
    @SuppressWarnings("unchecked") private List<View> tiles() { return (List<View>) field(field(activity(), "fullCollection"), "tiles"); }
    private CatalogFilters filters() { return (CatalogFilters) field(activity(), "activeFilters"); }
    private TextView status() { return (TextView) field(activity(), "collectionStatus"); }
    private Button more() { return (Button) field(activity(), "loadMoreButton"); }
    private SharedPreferences prefs() { return activity().getSharedPreferences("catalog_filters", 0); }

    private static final class Request {
        final String path; final CatalogApi.Result callback;
        Request(String path, CatalogApi.Result callback) { this.path = path; this.callback = callback; }
    }
    private static final class FakeApi extends CatalogApi {
        final List<Request> requests = new ArrayList<>();
        @Override void object(String path, Result callback) { requests.add(new Request(path, callback)); }
        @Override void array(String path, ArrayResult callback) { callback.done(new JSONArray(), null); }
        @Override void image(ImageView view, String path, boolean sports) { }
        @Override void image(ImageView view, String path, boolean sports, int width) { }
        Request last() { return requests.get(requests.size() - 1); }
    }
    private JSONObject page(int start, int count, int totalPages) {
        try {
            JSONArray entries = new JSONArray();
            for (int i = 0; i < count; i++) entries.put(new JSONObject().put("id", start + i)
                .put("title", "Filter fixture " + (start + i)).put("release_date", "2024-01-01").put("vote_average", 8));
            return new JSONObject().put("results", entries).put("total_pages", totalPages).put("total_results", totalPages * 10);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private void reply(int count) { fake.last().callback.done(page(1, count, 2), null); }
    private void choose(int index, String value) {
        main(() -> {
            assertTrue(filters().select(index, value));
            set("lastFilterIndex", index);
            invoke("applyCatalogFilters");
        });
        instrumentation().waitForIdleSync();
    }
    @Before public void setup() {
        saved = prefs().getAll();
        main(() -> {
            ((CatalogApi) field(activity(), "api")).close();
            fake = new FakeApi(); set("api", fake);
            ((CatalogFilters) field(activity(), "movieFilters")).reset();
            ((CatalogFilters) field(activity(), "tvFilters")).reset();
            invoke("openSection", "Movies");
        });
        instrumentation().waitForIdleSync();
    }
    @After public void restorePreferences() {
        SharedPreferences.Editor editor = prefs().edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) editor.putString(entry.getKey(), (String) entry.getValue());
        assertTrue(editor.commit());
        activity().getSharedPreferences("filter_test", 0).edit().clear().commit();
    }

    @Test public void discoveryQueryUsesCorrectMovieAndSeriesParameters() {
        for (String kind : new String[]{"movie", "tv"}) {
            CatalogFilters filters = new CatalogFilters(kind);
            filters.select(CatalogFilters.GENRE, "18"); filters.select(CatalogFilters.YEAR, "2024");
            filters.select(CatalogFilters.RATING, "7"); filters.select(CatalogFilters.COUNTRY, "US");
            filters.select(CatalogFilters.SORT, "newest");
            Uri query = Uri.parse(filters.pathPrefix() + "2");
            assertEquals("/api/tmdb/discover/" + kind, query.getPath());
            assertEquals("18", query.getQueryParameter("with_genres"));
            assertEquals("2024", query.getQueryParameter(kind.equals("movie") ? "primary_release_year" : "first_air_date_year"));
            assertNull(query.getQueryParameter(kind.equals("movie") ? "first_air_date_year" : "primary_release_year"));
            assertEquals("7", query.getQueryParameter("vote_average.gte"));
            assertEquals("US", query.getQueryParameter("with_origin_country"));
            assertEquals("2", query.getQueryParameter("page"));
            String date = kind.equals("movie") ? "primary_release_date" : "first_air_date";
            assertEquals(date + ".desc", query.getQueryParameter("sort_by"));
            filters.select(CatalogFilters.SORT, "oldest"); assertTrue(filters.pathPrefix().contains("sort_by=" + date + ".asc"));
            filters.select(CatalogFilters.SORT, "rated"); assertTrue(filters.pathPrefix().contains("sort_by=vote_average.desc"));
            assertFalse(filters.select(CatalogFilters.COUNTRY, "US&include_adult=true"));
            filters.reset();
            assertFalse(filters.pathPrefix().contains("with_genres="));
            assertFalse(filters.pathPrefix().contains("vote_average.gte="));
            assertTrue(filters.pathPrefix().contains("sort_by=popularity.desc"));
        }
        assertFalse(new CatalogFilters("tv").select(CatalogFilters.GENRE, "28"));
        assertTrue(new CatalogFilters("tv").select(CatalogFilters.GENRE, "10759"));
    }

    @Test public void preferencesRestoreEachSectionAndResetIndependently() {
        SharedPreferences preferences = activity().getSharedPreferences("filter_test", 0);
        preferences.edit().clear().commit();
        CatalogFilters movies = new CatalogFilters("movie"), tv = new CatalogFilters("tv");
        movies.select(CatalogFilters.GENRE, "28"); movies.select(CatalogFilters.YEAR, "2024"); movies.save(preferences);
        tv.select(CatalogFilters.GENRE, "10765"); tv.select(CatalogFilters.RATING, "8"); tv.save(preferences);
        movies = new CatalogFilters("movie"); tv = new CatalogFilters("tv");
        movies.restore(preferences); tv.restore(preferences);
        assertEquals("28", movies.value(CatalogFilters.GENRE)); assertEquals("2024", movies.value(CatalogFilters.YEAR));
        assertEquals("10765", tv.value(CatalogFilters.GENRE)); assertEquals("8", tv.value(CatalogFilters.RATING));
        movies.reset(); movies.save(preferences);
        tv = new CatalogFilters("tv"); tv.restore(preferences); assertEquals("8", tv.value(CatalogFilters.RATING));
        preferences.edit().putString("movie.0", "not-a-genre").commit();
        movies = new CatalogFilters("movie"); movies.restore(preferences); assertEquals("", movies.value(CatalogFilters.GENRE));
    }

    @Test public void remoteCanSelectEveryFilterAndReturnFromTheGrid() {
        key(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> assertSame(field(activity(), "topAction"), activity().getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        for (int i = 0; i < 5; i++) {
            final int index = i;
            String[] names = new String[2];
            main(() -> {
                assertSame(buttons().get(index), activity().getCurrentFocus());
                names[0] = filters().options(index).get(0).label;
                names[1] = filters().options(index).get(1).label;
            });
            key(KeyEvent.KEYCODE_DPAD_CENTER); awaitDialogFocus(names[0]);
            key(KeyEvent.KEYCODE_DPAD_DOWN); awaitDialogFocus(names[1]);
            key(KeyEvent.KEYCODE_DPAD_CENTER); awaitActivityWindow();
            main(() -> {
                assertEquals(filters().options(index).get(1).value, filters().value(index));
                reply(10);
                assertSame("Results never steal filter focus", buttons().get(index), activity().getCurrentFocus());
            });
            if (i < 4) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> assertSame(tiles().get(4), activity().getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> assertSame(buttons().get(4), activity().getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_CENTER); awaitDialogFocus("Highest rated");
        key(KeyEvent.KEYCODE_DPAD_DOWN); awaitDialogFocus("Newest first");
        key(KeyEvent.KEYCODE_BACK); awaitActivityWindow();
        main(() -> assertEquals("rated", filters().value(CatalogFilters.SORT)));
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> {
            assertEquals("", filters().value(CatalogFilters.GENRE));
            assertEquals("popular", filters().value(CatalogFilters.SORT));
            assertTrue(fake.last().path.endsWith("page=1"));
        });
    }

    @Test public void changesRejectStaleResponsesAndPaginationKeepsFilters() {
        Request[] old = new Request[1];
        main(() -> { old[0] = fake.last(); reply(10); });
        choose(CatalogFilters.GENRE, "28");
        main(() -> {
            assertTrue(tiles().isEmpty());
            old[0].callback.done(page(900, 5, 2), null);
            assertTrue("Old query must not append to the new grid", tiles().isEmpty());
            assertEquals("Loading...", status().getText().toString());
            reply(10);
            assertEquals(10, tiles().size());
            assertSame(buttons().get(0), activity().getCurrentFocus());
            more().requestFocus(); more().performClick();
            assertTrue(fake.last().path.contains("with_genres=28"));
            assertTrue(fake.last().path.endsWith("page=2"));
            fake.last().callback.done(page(11, 4, 2), null);
            assertEquals(14, tiles().size());
            assertSame(tiles().get(10), activity().getCurrentFocus());
            assertEquals(View.GONE, more().getVisibility());
        });
        choose(CatalogFilters.YEAR, "2024");
        main(() -> {
            assertTrue(fake.last().path.contains("with_genres=28"));
            assertTrue(fake.last().path.contains("primary_release_year=2024"));
            assertTrue(fake.last().path.endsWith("page=1"));
            assertEquals(0, tiles().size());
        });
    }

    @Test public void emptyLoadingAndFailureStatesKeepFiltersReachable() {
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> assertSame(buttons().get(0), activity().getCurrentFocus()));
        main(() -> fake.last().callback.done(page(0, 0, 0), null));
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> {
            assertTrue(status().getText().toString().startsWith("No matches"));
            assertSame(buttons().get(1), activity().getCurrentFocus());
        });
        choose(CatalogFilters.YEAR, "2024");
        main(() -> fake.last().callback.done(null, new IllegalStateException("Fixture offline")));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        main(() -> assertSame(more(), activity().getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> assertSame(buttons().get(1), activity().getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
        main(() -> {
            assertTrue(fake.last().path.contains("primary_release_year=2024"));
            assertTrue(fake.last().path.endsWith("page=1"));
            reply(3);
            assertEquals(3, tiles().size());
            assertSame(tiles().get(0), activity().getCurrentFocus());
        });
    }

    @Test public void detailsBackAndSectionChangesPreserveIndependentSelections() {
        choose(CatalogFilters.GENRE, "28");
        main(() -> { reply(1); tiles().get(0).performClick(); });
        key(KeyEvent.KEYCODE_BACK);
        main(() -> {
            assertEquals("28", filters().value(CatalogFilters.GENRE));
            invoke("openSection", "TV Shows");
            assertEquals("", filters().value(CatalogFilters.GENRE));
        });
        choose(CatalogFilters.GENRE, "10759");
        main(() -> {
            assertTrue(fake.last().path.startsWith("/api/tmdb/discover/tv?"));
            invoke("openSection", "Movies"); assertEquals("28", filters().value(CatalogFilters.GENRE));
            invoke("openSection", "TV Shows"); assertEquals("10759", filters().value(CatalogFilters.GENRE));
            buttons().get(5).performClick();
            assertEquals("", filters().value(CatalogFilters.GENRE));
            invoke("openSection", "Movies"); assertEquals("28", filters().value(CatalogFilters.GENRE));
            invoke("openSection", "Home");
            assertTrue(buttons().isEmpty());
            for (Request request : fake.requests) if (request.path.equals(invoke("moviePath") + "1")) return;
            fail("Home still loads the unfiltered Movies row");
        });
    }

    private void awaitDialogFocus(String text) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        String focused = "";
        while (SystemClock.uptimeMillis() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation().getUiAutomation().getRootInActiveWindow();
            android.view.accessibility.AccessibilityNodeInfo input = root == null ? null : root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            focused = input == null ? "" : String.valueOf(input.getText());
            if (text.equals(focused)) return;
            // A native single-choice ListView owns input focus; its selected row
            // (unlike a standalone Button) carries the option's accessible text.
            if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                if (text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isSelected()) return;
            }
            SystemClock.sleep(50);
        }
        assertEquals("Filter dialog has remote focus", text, focused);
    }
    private void awaitActivityWindow() {
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean[] focused = {false};
        while (!focused[0] && SystemClock.uptimeMillis() < deadline) {
            main(() -> focused[0] = activity().hasWindowFocus());
            if (!focused[0]) SystemClock.sleep(50);
        }
        assertTrue("Dialog closed", focused[0]);
        instrumentation().waitForIdleSync();
    }
}
