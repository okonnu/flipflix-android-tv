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
            assertEquals(featured.path + "1", first.path);
            assertEquals("/api/tmdb/discover/movie", Uri.parse(first.path).getPath());
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

    @Test public void featuredPicksAndItsCollectionShareCurrentYearRatingFiltersAndPagination() throws Exception {
        MainActivity a = rule.launchActivity(null);
        JSONObject firstPage = page(101).put("total_pages", 2);
        main(() -> {
            FakeApi api = attach(a);
            DiscoveryRow row = rows(a).get(0);
            assertEquals("Featured picks", row.section); assertEquals("movie", row.kind);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            Uri path = Uri.parse(row.path + "1");
            assertEquals("/api/tmdb/discover/movie", path.getPath());
            assertEquals(today.substring(0, 4) + "-01-01", path.getQueryParameter("primary_release_date.gte"));
            assertEquals(today, path.getQueryParameter("primary_release_date.lte"));
            assertEquals("7", path.getQueryParameter("vote_average.gte"));
            assertEquals("100", path.getQueryParameter("vote_count.gte"));
            assertEquals("vote_average.desc", path.getQueryParameter("sort_by"));
            assertEquals("false", path.getQueryParameter("include_adult"));
            assertEquals("false", path.getQueryParameter("include_video"));
            api.requests.get(0).callback.done(firstPage, null);
            invoke(a, "loadDiscoveryRow", row);
            assertEquals(row.path + "2", api.last().path);
            api.last().callback.done(page(102), null);
            assertEquals(2, row.items.size());
            invoke(a, "openSection", row.section);
            assertEquals(row.path + "1", api.last().path);
            api.last().callback.done(firstPage, null);
            invoke(a, "loadCollectionPage", field(a, "fullCollection"));
            assertEquals(row.path + "2", api.last().path);
        });
    }

    @Test public void featuredYearRollsForwardAndHomeRefreshesOnCalendarChange() {
        Uri previous = Uri.parse(MainActivity.featuredMoviesPath("2026-12-31") + "1");
        Uri next = Uri.parse(MainActivity.featuredMoviesPath("2027-01-01") + "1");
        assertEquals("2026-01-01", previous.getQueryParameter("primary_release_date.gte"));
        assertEquals("2027-01-01", next.getQueryParameter("primary_release_date.gte"));
        assertEquals("2027-01-01", next.getQueryParameter("primary_release_date.lte"));
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            attach(a);
            List<DiscoveryRow> before = rows(a);
            set(a, "discoveryDay", "2000-12-31");
            invoke(a, "openSection", "Home");
            assertNotSame("Even a recent cache must not carry last year's titles into a new year", before, rows(a));
            assertTrue(rows(a).get(0).loading);
        });
    }

    @Test public void liveFeaturedPicksMeetRulesAndRemoteHeadingOpensSameCollection() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveFeatured")));
        MainActivity a = rule.launchActivity(null);
        await(() -> rows(a) != null && !rows(a).get(0).loading);
        String[] path = {""};
        main(() -> {
            DiscoveryRow row = rows(a).get(0);
            assertFalse("Live Featured picks must load", row.failed);
            assertFalse("Live catalog must return qualifying movies", row.items.isEmpty());
            path[0] = row.path;
            Uri query = Uri.parse(row.path + "1");
            String lower = query.getQueryParameter("primary_release_date.gte");
            String upper = query.getQueryParameter("primary_release_date.lte");
            for (DiscoveryItem item : row.items) {
                JSONObject film = item.catalog;
                assertEquals("movie", film.optString("media_type"));
                String release = film.optString("release_date");
                assertTrue(item.title + " release date", release.compareTo(lower) >= 0 && release.compareTo(upper) <= 0);
                assertTrue(item.title + " rating", film.optDouble("vote_average") >= 7);
                assertTrue(item.title + " vote count", film.optInt("vote_count") >= 100);
            }
            android.util.Log.i("DarkflixFeaturedTest", "qualifyingMovies=" + row.items.size() + " date=" + upper);
        });
        android.os.SystemClock.sleep(1500);
        capture("darkflix-featured-home.png");
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        main(() -> {
            java.util.ArrayList<android.view.View> matches = new java.util.ArrayList<>();
            a.getWindow().getDecorView().findViewsWithText(matches, "Featured picks, browse all", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            assertEquals(1, matches.size()); assertTrue(matches.get(0).requestFocus());
        });
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> field(a, "fullCollection") != null && !(boolean) field(field(a, "fullCollection"), "loading"));
        main(() -> {
            assertEquals("Featured picks", field(a, "section"));
            Object collection = field(a, "fullCollection");
            assertEquals(path[0], field(collection, "pathPrefix"));
            assertFalse(((List<?>) field(collection, "tiles")).isEmpty());
        });
        android.os.SystemClock.sleep(1500);
        capture("darkflix-featured-collection.png");
    }

    private void await(java.util.function.BooleanSupplier check) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        long end = android.os.SystemClock.uptimeMillis() + 30000;
        do {
            main(() -> done.set(check.getAsBoolean()));
            if (done.get()) return;
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < end);
        fail("Live catalog did not finish loading");
    }

    private void capture(String name) throws Exception {
        android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream("/sdcard/Download/" + name)) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
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
