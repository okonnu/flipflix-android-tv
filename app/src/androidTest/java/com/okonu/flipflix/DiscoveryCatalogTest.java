package com.okonu.flipflix;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the actual Home definitions, not a separate sample catalog. */
@RunWith(AndroidJUnit4.class)
public final class DiscoveryCatalogTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class, false, false);
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void set(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private Object invoke(MainActivity target, String name, Object... args) {
        try {
            for (Method m : MainActivity.class.getDeclaredMethods()) if (m.getName().equals(name)) { m.setAccessible(true); return m.invoke(target, args); }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private List<DiscoveryRow> rows(MainActivity a) { return (List<DiscoveryRow>) field(a, "discoveryRows"); }
    private static final class Request {
        final String path; final CatalogApi.Result callback;
        Request(String path, CatalogApi.Result callback) { this.path = path; this.callback = callback; }
    }
    private static final class FakeApi extends CatalogApi {
        final List<Request> requests = new ArrayList<>();
        @Override void object(String path, Result result) { requests.add(new Request(path, result)); }
        @Override void array(String path, ArrayResult result) { result.done(new JSONArray(), null); }
        @Override void imageUrl(ImageView v, String path, Runnable ready) { }
        @Override void image(ImageView v, String path, boolean sport) { }
        @Override void image(ImageView v, String path, boolean sport, int width) { }
        Request last() { return requests.get(requests.size() - 1); }
    }
    private FakeApi attach(MainActivity a) {
        ((CatalogApi) field(a, "api")).close();
        FakeApi api = new FakeApi(); set(a, "api", api); set(a, "discoveryRows", null);
        invoke(a, "openSection", "Home"); return api;
    }
    private JSONObject page(int id) {
        try { return new JSONObject().put("total_pages", 1).put("results", new JSONArray().put(new JSONObject()
            .put("id", id).put("media_type", "movie").put("title", "Catalog title " + id).put("backdrop_path", "/real.jpg"))); }
        catch (org.json.JSONException e) { throw new AssertionError(e); }
    }
    @Test public void productionRowsNeverSeedDemoRecommendations() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MainActivity activity = new MainActivity();
            try {
                Method build = MainActivity.class.getDeclaredMethod("buildDiscoveryRows");
                build.setAccessible(true);
                @SuppressWarnings("unchecked") List<DiscoveryRow> rows = (List<DiscoveryRow>) build.invoke(activity);
                assertFalse(rows.isEmpty());
                for (DiscoveryRow row : rows) {
                    assertTrue(row.title + " must start empty and obtain its titles from the catalog, not bundled demos", row.items.isEmpty());
                    assertTrue(row.kind.equals("sport") || row.path.startsWith("/api/tmdb/"));
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally {
                try { Field api = MainActivity.class.getDeclaredField("api"); api.setAccessible(true); ((CatalogApi) api.get(activity)).close(); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }
        });
    }

    @Test public void homeUsesCatalogResultsAndErrorsNeverSubstituteDemos() {
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            FakeApi api = attach(a);
            DiscoveryRow featured = rows(a).get(0);
            assertTrue(featured.items.isEmpty());
            Request first = api.requests.get(0);
            assertEquals("/api/tmdb/trending/all/day?page=1", first.path);
            first.callback.done(page(101), null);
            assertEquals(1, featured.items.size()); assertEquals("movie:101", featured.items.get(0).key);
            assertNotNull(featured.items.get(0).catalog);
            assertTrue(featured.items.get(0).previewUrl.isEmpty());
            DiscoveryRow trending = rows(a).get(1);
            api.requests.get(1).callback.done(null, new java.io.IOException("offline"));
            assertTrue(trending.items.isEmpty()); assertTrue(trending.failed);
            // The row and its heading must use the same weekly movie collection.
            DiscoveryRow spotlight = rows(a).get(6);
            assertEquals("Spotlight", spotlight.section);
            invoke(a, "openSection", spotlight.section);
            assertEquals(spotlight.path + "1", api.last().path);
        });
    }

    @Test public void homeRefreshIsBoundedAndRejectsOldResponses() {
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            FakeApi api = attach(a); set(a, "discoveryRows", null);
            invoke(a, "prepareDiscoveryRows", 1000L);
            List<DiscoveryRow> before = rows(a);
            invoke(a, "loadDiscoveryRow", before.get(0)); Request late = api.last();
            invoke(a, "prepareDiscoveryRows", 1000L + 3599999L);
            assertSame("Normal returns preserve the current rows", before, rows(a));
            invoke(a, "prepareDiscoveryRows", 1000L + 3600000L);
            assertNotSame("An hour-old Home is rebuilt on return", before, rows(a));
            late.callback.done(page(1), null);
            assertTrue("Abandoned requests cannot repopulate old rows", before.get(0).items.isEmpty());
            DiscoveryRow fresh = rows(a).get(0); invoke(a, "loadDiscoveryRow", fresh);
            api.last().callback.done(page(2), null);
            assertEquals("movie:2", fresh.items.get(0).key);
            assertEquals(0, ((DiscoveryView.Position) field(a, "discoveryPosition")).card);
        });
    }

    @Test public void malformedCatalogResponseBecomesAnErrorNotEndlessLoading() {
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            FakeApi api = attach(a);
            api.requests.get(0).callback.done(new JSONObject(), null);
            DiscoveryRow row = rows(a).get(0);
            assertTrue("A response without results must report unavailable", row.failed);
            assertTrue(row.complete); assertFalse(row.loading); assertTrue(row.items.isEmpty());
        });
    }

    @Test public void homeEntryRefreshesExpiredRowsButKeepsRecentBrowsingPosition() {
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            attach(a);
            List<DiscoveryRow> before = rows(a);
            invoke(a, "openSection", "Movies");
            invoke(a, "openSection", "Home");
            assertSame(before, rows(a));
            set(a, "discoveryLoadedAt", android.os.SystemClock.elapsedRealtime() - 3600000L);
            invoke(a, "openSection", "Movies");
            invoke(a, "openSection", "Home");
            assertNotSame(before, rows(a));
            assertTrue(rows(a).get(0).loading);
        });
    }

    @Test public void newReleasesUsesActualReleaseDatesAndNoFutureTitles() {
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            attach(a);
            DiscoveryRow row = rows(a).get(7);
            assertEquals("New releases", row.title); assertEquals(row.title, row.section);
            Uri query = Uri.parse(row.path + "1");
            assertEquals("primary_release_date.desc", query.getQueryParameter("sort_by"));
            assertEquals(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()), query.getQueryParameter("primary_release_date.lte"));
        });
    }

    @Test public void upgradingRemovesOnlyDemoBookmarksAndPreservesRealFilmsAndHistory() throws Exception {
        MainActivity a = rule.launchActivity(null);
        SharedPreferences prefs = a.getSharedPreferences("discovery", 0);
        String original = prefs.getString("library", "[]");
        java.util.Map<String, ?> history = a.getSharedPreferences("watch_history", 0).getAll();
        JSONArray library = new JSONArray()
            .put(new JSONObject().put("key", "open:sintel").put("title", "Sintel"))
            .put(new JSONObject().put("key", "open:bunny").put("title", "Big Buck Bunny"))
            .put(new JSONObject().put("key", "movie:45745").put("title", "Sintel").put("custom", "retain raw data"))
            .put(new JSONObject().put("key", "tv:123").put("title", "Saved series"));
        try {
            assertTrue(prefs.edit().putString("library", library.toString()).commit());
            main(() -> {
                List<DiscoveryItem> retained = DiscoveryView.saved(a);
                assertEquals(2, retained.size()); assertEquals("movie:45745", retained.get(0).key);
                assertEquals("tv:123", retained.get(1).key);
                assertEquals(2, DiscoveryView.saved(a).size());
                assertEquals(history, a.getSharedPreferences("watch_history", 0).getAll());
            });
            JSONArray stored = new JSONArray(prefs.getString("library", "[]"));
            assertEquals(2, stored.length()); assertEquals("retain raw data", stored.getJSONObject(0).getString("custom"));
        } finally { assertTrue(prefs.edit().putString("library", original).commit()); }
    }
}
