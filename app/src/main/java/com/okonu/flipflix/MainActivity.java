package com.okonu.flipflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int BG = AppStyle.BG;
    private static final int PANEL = AppStyle.PANEL;
    private static final int CONTROL = AppStyle.CONTROL;
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = AppStyle.MUTED;
    private static final int ACCENT = AppStyle.ACCENT;
    private static final Typeface REGULAR = AppStyle.REGULAR;
    private static final Typeface MEDIUM = AppStyle.MEDIUM;
    private CatalogApi api = new CatalogApi();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppUpdater updater;
    private WatchHistory watchHistory;
    private PlaybackRecovery recovery;
    private long initialPlaybackPosition = -1;
    private final List<List<View>> focusRows = new ArrayList<>();
    private final List<CatalogRow> catalogRows = new ArrayList<>();
    private final CatalogFilters movieFilters = new CatalogFilters("movie");
    private final CatalogFilters tvFilters = new CatalogFilters("tv");
    private final List<Button> filterButtons = new ArrayList<>();
    private CatalogFilters activeFilters;
    private TextView collectionStatus;
    private int lastFilterIndex;
    private FullCollection fullCollection;
    private Button loadMoreButton;
    private FrameLayout root;
    private StartupIntro startupIntro;
    private boolean foreground;
    private FrameLayout pageChrome;
    private NavigationRail navigationRail;
    private View lastContentFocus;
    private LinearLayout content;
    private WebView player;
    private NativePlayback nativePlayer;
    private StreamDiscovery streamDiscovery;
    private boolean nativeOnlySource;
    private Runnable nativeDiscoveryDeadline;
    private static final PlaybackBackend WAITING_FOR_NATIVE = new PlaybackBackend() {
        @Override public State state() { return new State(); }
        @Override public void command(String name, String value) { }
    };
    private EmbeddedPlayback embeddedPlayer;
    private BoltPlayback boltPlayer;
    private RiftPlayback riftPlayer;
    private AlertDialog playerSourceDialog;
    private AlertDialog webActionsDialog;
    private boolean providerInput;
    private int providerReturnKey = -1;
    private TextView playerStatus;
    private FrameLayout playerScreen;
    private PlaybackControls playerOverlay;
    private TrailerPreview trailerPreview;
    private DiscoveryView discovery;
    private List<DiscoveryRow> discoveryRows;
    private static final long HOME_REFRESH_MS = 60 * 60 * 1000L;
    private long discoveryLoadedAt;
    private final DiscoveryView.Position discoveryPosition = new DiscoveryView.Position();
    private final DiscoveryView.Position libraryPosition = new DiscoveryView.Position();
    private final DiscoveryView.Position historyPosition = new DiscoveryView.Position();
    private View topAction;
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customVideoCallback;
    private int currentSourceIndex;
    private int sourceRequest;
    private Item selected;
    private String section = "Home";
    private boolean detailOpen;
    private boolean playerOpen;
    private boolean playerBackKeyDown;
    private int generation;
    private int season = 1;
    private int episode = 1;

    private static final class Item {
        int id;
        String kind, title, poster, backdrop, overview, year, rating;
        String genres = "";
        JSONArray genreIds;
        static Item from(JSONObject o, String fallbackKind) {
            Item item = new Item();
            item.id = o.optInt("id");
            item.kind = o.optString("media_type", fallbackKind);
            if (!item.kind.equals("movie") && !item.kind.equals("tv")) item.kind = fallbackKind;
            item.title = o.optString("title", o.optString("name", "Untitled"));
            item.poster = o.optString("poster_path", "");
            item.backdrop = o.optString("backdrop_path", "");
            item.overview = o.optString("overview", "");
            item.year = o.optString("year", "");
            if (item.year.isEmpty()) {
                String date = o.optString("release_date", o.optString("first_air_date", ""));
                item.year = date.length() >= 4 ? date.substring(0, 4) : "";
            }
            double vote = o.optDouble("vote_average", 0);
            item.rating = vote > 0 ? String.format(java.util.Locale.US, "%.1f", vote) : "";
            item.genreIds = o.optJSONArray("genre_ids");
            item.genres = genreNames(o.optJSONArray("genres"));
            return item;
        }

        static String genreNames(JSONArray genres) {
            List<String> names = new ArrayList<>();
            if (genres != null) for (int i = 0; i < genres.length(); i++) {
                JSONObject genre = genres.optJSONObject(i);
                if (genre == null || !(genre.opt("name") instanceof String)) continue;
                String name = genre.optString("name").trim();
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
            return TextUtils.join(", ", names);
        }

        String metadata() {
            List<String> parts = new ArrayList<>();
            if (!TextUtils.isEmpty(year)) parts.add(year);
            parts.add(kind.equals("tv") ? "TV show" : "Movie");
            if (!TextUtils.isEmpty(rating)) parts.add(rating);
            if (!genres.isEmpty()) parts.add(genres);
            return TextUtils.join("  |  ", parts);
        }
    }

    private static final class CatalogRow {
        final String pathPrefix;
        final String kind;
        final LinearLayout cards;
        final List<View> tiles = new ArrayList<>();
        View headingView;
        int nextPage = 1;
        boolean loading;
        boolean complete;
        View pendingFrom;

        CatalogRow(String pathPrefix, String kind, LinearLayout cards) {
            this.pathPrefix = pathPrefix;
            this.kind = kind;
            this.cards = cards;
        }
    }

    private static final class FullCollection {
        final String pathPrefix;
        final String kind;
        final LinearLayout grid;
        final List<View> tiles = new ArrayList<>();
        int nextPage = 1;
        boolean loading;
        boolean complete;

        FullCollection(String pathPrefix, String kind, LinearLayout grid) {
            this.pathPrefix = pathPrefix;
            this.kind = kind;
            this.grid = grid;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        watchHistory = new WatchHistory(getSharedPreferences("watch_history", MODE_PRIVATE));
        movieFilters.restore(getSharedPreferences("catalog_filters", MODE_PRIVATE));
        tvFilters.restore(getSharedPreferences("catalog_filters", MODE_PRIVATE));
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        FrameLayout surface = new FrameLayout(this);
        surface.setBackgroundColor(Color.BLACK);
        surface.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(surface);
        openSection("Home");
        // Test-only opt-out cannot disable the intro in the non-debuggable release.
        boolean testSkip = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && getIntent().getBooleanExtra("skip_startup_intro", false);
        if (state == null && !testSkip && StartupIntro.motionEnabled(this)) {
            startupIntro = new StartupIntro(this, surface, root, () -> {
                if (discovery != null) discovery.setActive(foreground && hasWindowFocus());
                if (updater != null && foreground) updater.stateChanged();
            });
            if (discovery != null) discovery.setActive(false);
            startupIntro.start();
        }
        updater = new AppUpdater(this, () -> !playerOpen && !introActive());
    }

    private boolean introActive() { return startupIntro != null && startupIntro.isActive(); }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable shape(int color, int border) {
        return AppStyle.shape(this, color, border);
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        t.setTypeface(bold ? MEDIUM : REGULAR);
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void gap(LinearLayout l, int height) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String label, Runnable action) {
        return button(label, action, false);
    }
    private Button button(String label, Runnable action, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(primary ? BG : WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(MEDIUM);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setFocusableInTouchMode(true);
        AppStyle.button(b, primary, false);
        String glyph = null;
        if (label.equals("Bolt") || label.equals("Rift") || label.equals("Fast") || label.equals("Stream 1")) glyph = "play";
        else if (label.equals("Search")) glyph = "search";
        else if (label.equals("Home")) glyph = "home";
        else if (label.startsWith("Back")) glyph = "back";
        else if (label.startsWith("Previous")) glyph = "previous";
        else if (label.startsWith("Next")) glyph = "next";
        else if (label.startsWith("Source:")) glyph = "source";
        else if (label.equals("Retry")) glyph = "retry";
        else if (label.toLowerCase(java.util.Locale.US).contains("fullscreen")) glyph = "fullscreen";
        final ControlIcon icon = glyph == null ? null : new ControlIcon(glyph, primary ? BG : WHITE);
        if (icon != null) {
            icon.setBounds(0, 0, dp(20), dp(20));
            b.setCompoundDrawables(icon, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, focused) -> {
            AppStyle.button(b, primary, focused);
            if (icon != null) icon.color(primary || focused ? BG : WHITE);
            b.setElevation(focused ? dp(4) : 0);
        });
        return b;
    }

    private void shell(String title) {
        finishRecovery();
        releaseTrailerPreview();
        releaseDiscovery();
        if (playerOverlay != null) playerOverlay.deactivate();
        clearCustomVideoView();
        sourceRequest++;
        releaseNativePlayer();
        playerOverlay = null;
        focusRows.clear();
        catalogRows.clear();
        filterButtons.clear();
        activeFilters = null;
        collectionStatus = null;
        lastFilterIndex = 0;
        fullCollection = null;
        loadMoreButton = null;
        topAction = null;
        getWindow().getDecorView().setSystemUiVisibility(0);
        releaseRiftPlayer();
        releaseBoltPlayer();
        if (player != null) { player.stopLoading(); player.destroy(); player = null; }
        embeddedPlayer = null;
        playerScreen = null;
        playerStatus = null;
        root.removeAllViews();
        lastContentFocus = null;
        pageChrome = new FrameLayout(this);
        root.addView(pageChrome, new FrameLayout.LayoutParams(-1, -1));
        TvScrollView scroller = new TvScrollView(this);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1);
        scrollParams.leftMargin = dp(62);
        pageChrome.addView(scroller, scrollParams);
        content = column();
        content.setPadding(dp(25), dp(24), dp(25), dp(34));
        content.setClipChildren(false);
        scroller.addView(content);
        navigationRail = new NavigationRail(this, this::openSection, () -> {});
        pageChrome.addView(navigationRail, new FrameLayout.LayoutParams(dp(62), -1));
        LinearLayout header = row();
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));
        boolean brandTitle = title.equals("Darkflix");
        TextView brand = text(brandTitle ? "DETAILS" : title, brandTitle ? 11 : 30, brandTitle ? MUTED : WHITE, true);
        if (brandTitle) brand.setLetterSpacing(.17f);
        header.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        Button action = button(section.equals("Home") ? "Search" : "Home", () -> openSection(section.equals("Home") ? "Search" : "Home"));
        topAction = action;
        header.addView(action, new LinearLayout.LayoutParams(dp(110), dp(36)));
        gap(content, 16);
    }

    private void openSection(String name) {
        if (name.equals("Browse")) { showBrowseMenu(); return; }
        section = name;
        detailOpen = false;
        playerOpen = false;
        generation++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shell(name.equals("Home") ? "Darkflix" : name);
        if (name.equals("Search")) searchScreen();
        else if (name.equals("Home") || name.equals("Library") || name.equals("History")) showDiscovery(name.equals("Library"));
        else showCollection(name);
    }

    private void showBrowseMenu() {
        String[] destinations = {"Home", "Movies", "TV Shows", "Trending", "Anime", "Live Sports", "Library", "History"};
        String[] labels = {"Home", "Movies - browse & filter", "TV Shows - browse & filter", "Trending", "Anime", "Live Sports", "Library", "History", "App updates"};
        View restore = getCurrentFocus();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Browse")
            .setItems(labels, (d, index) -> {
                if (index < destinations.length) openSection(destinations[index]);
                else ui.post(() -> { if (updater != null) updater.showStatus(); });
            }).setNegativeButton("Cancel", null).create();
        dialog.setOnDismissListener(d -> {
            if (restore != null && restore.isAttachedToWindow() && restore.isShown()) restore.requestFocus();
        });
        dialog.show(); AppStyle.dialog(dialog, 440, -2);
        dialog.getListView().requestFocus();
        int selectedIndex = java.util.Arrays.asList(destinations).indexOf(section);
        dialog.getListView().setSelection(Math.max(0, selectedIndex));
    }

    private boolean enterRail() {
        if (navigationRail == null || !navigationRail.isAttachedToWindow()) return false;
        lastContentFocus = getCurrentFocus(); navigationRail.focusBrowse(); return true;
    }
    private void returnFromRail() {
        View target = lastContentFocus;
        if (target == null || !target.isAttachedToWindow() || !target.isShown()) {
            target = filterButtons.isEmpty() ? topAction : filterButtons.get(lastFilterIndex);
        }
        if (target != null) focusTile(target);
    }

    private List<DiscoveryRow> buildDiscoveryRows() {
        List<DiscoveryRow> result = new ArrayList<>();
        result.add(new DiscoveryRow("Featured picks", "Trending", "/api/tmdb/trending/all/day?page=", "movie", false));
        result.add(new DiscoveryRow("Trending", "Trending", "/api/tmdb/trending/all/day?page=", "movie", false));
        result.add(new DiscoveryRow("Movies", "Movies", moviePath(), "movie", false));
        result.add(new DiscoveryRow("TV Shows", "TV Shows", tvPath(), "tv", false));
        result.add(new DiscoveryRow("Crime TV", "Crime TV", discoveryPath("Crime TV"), "tv", false));
        result.add(new DiscoveryRow("Comedy TV", "Comedy TV", discoveryPath("Comedy TV"), "tv", false));
        result.add(new DiscoveryRow("Spotlight", "Spotlight", "/api/tmdb/trending/movie/week?page=", "movie", true));
        result.add(new DiscoveryRow("New releases", "New releases", discoveryPath("New releases"), "movie", false));
        result.add(new DiscoveryRow("Comedy movies", "Comedy movies", discoveryPath("Comedy movies"), "movie", false));
        result.add(new DiscoveryRow("Anime", "Anime", "/api/tmdb/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&include_adult=false&page=", "tv", false));
        result.add(new DiscoveryRow("Live Sports", "Live Sports", null, "sport", false));
        return result;
    }
    private String discoveryPath(String name) {
        if (name.equals("Crime TV")) return "/api/tmdb/discover/tv?with_genres=80&sort_by=popularity.desc&include_adult=false&page=";
        if (name.equals("Comedy TV")) return "/api/tmdb/discover/tv?with_genres=35&sort_by=popularity.desc&include_adult=false&page=";
        if (name.equals("Comedy movies")) return "/api/tmdb/discover/movie?with_genres=35&sort_by=popularity.desc&include_adult=false&page=";
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        return "/api/tmdb/discover/movie?sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=80&include_adult=false&page=";
    }
    private boolean discoveryExpired(long now) {
        return discoveryRows == null || now - discoveryLoadedAt >= HOME_REFRESH_MS;
    }
    private void prepareDiscoveryRows(long now) {
        if (!discoveryExpired(now)) return;
        discoveryRows = buildDiscoveryRows();
        discoveryLoadedAt = now;
        discoveryPosition.row = discoveryPosition.card = 0;
    }
    private void showDiscovery(boolean library) {
        boolean history = section.equals("History");
        root.removeAllViews();
        navigationRail = null; pageChrome = null;
        if (!library && !history) prepareDiscoveryRows(android.os.SystemClock.elapsedRealtime());
        List<DiscoveryRow> shown = discoveryRows;
        if (library) {
            shown = new ArrayList<>();
            List<DiscoveryItem> saved = DiscoveryView.saved(this);
            if (!saved.isEmpty()) { DiscoveryRow row = new DiscoveryRow("Your saved titles", "Home", null, "saved", false); row.items.addAll(saved); row.complete = true; shown.add(row); }
        }
        if (history) shown = historyRows();
        discovery = new DiscoveryView(this, api, shown, new DiscoveryView.Actions() {
            @Override public void open(DiscoveryItem item) { openDiscoveryItem(item); }
            @Override public void section(String name) { openSection(name); }
            @Override public void more(DiscoveryRow row) { loadDiscoveryRow(row); }
        }, history ? historyPosition : library ? libraryPosition : discoveryPosition);
        if (history) discovery.historyEmptyState();
        root.addView(discovery, new FrameLayout.LayoutParams(-1, -1));
        if (!library && !history) for (DiscoveryRow row : discoveryRows) if (row.items.size() <= 2) loadDiscoveryRow(row);
    }
    private List<DiscoveryRow> historyRows() {
        List<DiscoveryRow> rows = new ArrayList<>();
        DiscoveryRow row = null;
        for (WatchHistory.Entry entry : watchHistory.all()) {
            if (row == null || row.items.size() == 20) {
                row = new DiscoveryRow(rows.isEmpty() ? "Recently played" : "Earlier", "History", null, "history", false);
                row.complete = true; rows.add(row);
            }
            Item item = Item.from(entry.catalog, entry.catalog.optString("media_type", "movie"));
            DiscoveryItem card = new DiscoveryItem(); card.key = "history:" + entry.key; card.catalog = entry.catalog;
            card.title = item.title + (item.kind.equals("tv") ? " - S" + entry.season + " E" + entry.episode : "");
            card.category = "Watch history"; card.description = item.overview;
            card.metadata = (entry.finished ? "Finished" : "Continue at " + WatchHistory.time(entry.position)) + "  |  " + item.metadata();
            card.thumbnail = "https://image.tmdb.org/t/p/w500" + (item.backdrop.isEmpty() ? item.poster : item.backdrop);
            card.heroImage = "https://image.tmdb.org/t/p/w1280" + (item.backdrop.isEmpty() ? item.poster : item.backdrop);
            card.progress = entry.duration > 0 ? (float) entry.position / entry.duration : -1;
            card.history = true; card.resumePosition = entry.resumePosition(); card.resumeSource = entry.source;
            card.resumeSeason = entry.season; card.resumeEpisode = entry.episode; row.items.add(card);
        }
        return rows;
    }
    private void loadDiscoveryRow(DiscoveryRow row) {
        if (row.loading || row.complete) return;
        row.loading = true;
        if (row.kind.equals("sport")) {
            api.array("/api/streamed/matches/live", (json, error) -> {
                if (isDestroyed() || discoveryRows == null || !discoveryRows.contains(row)) return;
                row.loading = false; row.complete = true; row.failed = error != null;
                if (json != null) for (int n = 0; n < json.length(); n++) {
                    JSONObject o = json.optJSONObject(n); if (o == null) continue;
                    DiscoveryItem i = new DiscoveryItem(); i.key = "sport:" + o.optString("id"); i.title = o.optString("title", "Live event");
                    i.category = "Live Sports"; i.metadata = o.optString("category", "Live"); i.description = "Live event. Choose a stream on the next screen.";
                    i.thumbnail = i.heroImage = "https://streamed.pk" + o.optString("poster"); i.live = true; i.catalog = o;
                    row.items.add(i);
                }
                refreshDiscovery(row);
            }); return;
        }
        if (row.path == null) { row.loading = false; row.complete = true; return; }
        int page = row.page;
        api.object(row.path + page, (json, error) -> {
            if (isDestroyed() || discoveryRows == null || !discoveryRows.contains(row)) return;
            row.loading = false;
            JSONArray entries = json == null ? null : json.optJSONArray("results");
            if (entries != null) for (int n = 0; n < entries.length(); n++) {
                JSONObject o = entries.optJSONObject(n); if (o == null) continue;
                Item source = Item.from(o, row.kind);
                DiscoveryItem i = new DiscoveryItem(); i.key = source.kind + ":" + source.id; i.title = source.title; i.category = row.title;
                i.description = source.overview; i.metadata = source.metadata();
                i.thumbnail = source.backdrop.isEmpty() ? "https://image.tmdb.org/t/p/w500" + source.poster : "https://image.tmdb.org/t/p/w500" + source.backdrop;
                if (row.featured && n > 0 && !source.poster.isEmpty()) i.thumbnail = "https://image.tmdb.org/t/p/w500" + source.poster;
                i.heroImage = "https://image.tmdb.org/t/p/w1280" + (source.backdrop.isEmpty() ? source.poster : source.backdrop);
                try { o.put("media_type", source.kind); } catch (org.json.JSONException ignored) { }
                i.catalog = o;
                boolean duplicate = false; for (DiscoveryItem old : row.items) if (old.key.equals(i.key)) duplicate = true;
                if (!duplicate) row.items.add(i);
            }
            if (entries != null) { row.page++; row.complete = entries.length() == 0 || page >= json.optInt("total_pages", 1); }
            // Retry errors only when the user asks by revisiting the category, not in a background loop.
            if (error != null || entries == null) { row.complete = true; row.failed = true; }
            refreshDiscovery(row);
        });
    }
    private void refreshDiscovery(DiscoveryRow row) {
        if (discovery == null) return;
        int index = discovery.rows.indexOf(row); if (index >= 0) discovery.refreshRow(index);
    }
    private void openDiscoveryItem(DiscoveryItem entry) {
        if (entry.history && entry.catalog != null) {
            Item item = Item.from(entry.catalog, entry.catalog.optString("media_type", "movie"));
            showPlayer(item, entry.resumeSeason, entry.resumeEpisode, entry.resumeSource);
            return;
        }
        if (entry.catalog == null) return;
        if (entry.live) {
            JSONObject o = entry.catalog; Item item = new Item(); item.kind = "sport"; item.title = entry.title; item.poster = o.optString("poster");
            item.year = entry.metadata; item.rating = ""; item.overview = o.optString("id");
            JSONArray sources = o.optJSONArray("sources"); JSONObject source = sources == null ? null : sources.optJSONObject(0);
            item.backdrop = source == null ? "" : source.optString("source") + "/" + source.optString("id"); showSportsPlayer(item);
        } else openTitle(Item.from(entry.catalog, entry.catalog.optString("media_type", "movie")));
    }
    private void releaseDiscovery() { if (discovery != null) { discovery.close(); discovery = null; } }

    private String moviePath() { return "/api/tmdb/discover/movie?sort_by=popularity.desc&include_adult=false&vote_count.gte=80&include_video=false&page="; }
    private String tvPath() { return "/api/tmdb/discover/tv?sort_by=popularity.desc&include_adult=false&vote_count.gte=80&include_video=false&page="; }

    private CatalogRow createRow(String heading, String pathPrefix, String kind) {
        TextView title = text(heading, 20, WHITE, true);
        title.setFocusableInTouchMode(true);
        title.setClickable(true);
        title.setOnClickListener(v -> openSection(heading));
        title.setOnFocusChangeListener((v, focused) -> {
            title.setTextColor(focused ? WHITE : 0xffe6e6e6);
            title.setBackground(shape(focused ? CONTROL : Color.TRANSPARENT, focused ? WHITE : Color.TRANSPARENT));
        });
        title.setPadding(dp(8), 0, dp(12), 0);
        content.addView(title, new LinearLayout.LayoutParams(-2, dp(36)));
        gap(content, 2);
        HorizontalScrollView scroll = new TvCarousel(this);
        LinearLayout cards = row();
        cards.setPadding(dp(8), dp(6), dp(8), dp(6));
        cards.setClipChildren(false);
        scroll.addView(cards);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        ((TvScrollView) content.getParent()).addFocusGroup(title, scroll);
        gap(content, 16);
        CatalogRow state = new CatalogRow(pathPrefix, kind, cards);
        state.headingView = title;
        catalogRows.add(state);
        focusRows.add(state.tiles);
        return state;
    }

    private void loadRow(String heading, String pathPrefix, String kind) {
        CatalogRow state = createRow(heading, pathPrefix, kind);
        loadNextPage(state);
    }

    private void loadNextPage(CatalogRow state) {
        if (state.loading || state.complete || state.pathPrefix == null) return;
        state.loading = true;
        int page = state.nextPage;
        int request = generation;
        api.object(state.pathPrefix + page, (json, error) -> {
            if (request != generation) return;
            state.loading = false;
            if (error != null || json == null) {
                if (state.tiles.isEmpty()) state.cards.addView(text("Catalog unavailable", 16, MUTED, false));
                return;
            }
            JSONArray entries = json.optJSONArray("results");
            if (entries == null || entries.length() == 0) { state.complete = true; return; }
            int previousCount = state.tiles.size();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.optJSONObject(i);
                if (o != null) {
                    View tile = card(Item.from(o, state.kind), false, () -> maybeLoadMore(state));
                    state.cards.addView(tile);
                    state.tiles.add(tile);
                }
            }
            state.nextPage++;
            int totalPages = json.optInt("total_pages", Integer.MAX_VALUE);
            if (state.nextPage > totalPages) state.complete = true;
            if (state.pendingFrom != null && getCurrentFocus() == state.pendingFrom && state.tiles.size() > previousCount) {
                focusTile(state.tiles.get(previousCount));
            }
            state.pendingFrom = null;
            if (catalogRows.indexOf(state) == 0 && previousCount == 0 && !state.tiles.isEmpty() && section.equals("Home")) {
                focusTile(state.tiles.get(0));
            }
        });
    }

    private void maybeLoadMore(CatalogRow state) {
        int index = state.tiles.indexOf(getCurrentFocus());
        if (index >= state.tiles.size() - 4) loadNextPage(state);
    }

    private void fillGrid(LinearLayout target, JSONArray entries, String kind) {
        target.removeAllViews();
        focusRows.clear();
        if (entries == null || entries.length() == 0) { target.addView(text("No results", 17, MUTED, false)); return; }
        LinearLayout line = null;
        List<View> focusRow = null;
        for (int i = 0; i < Math.min(entries.length(), 60); i++) {
            if (i % 5 == 0) {
                line = gridRow(); target.addView(line);
                focusRow = new ArrayList<>(); focusRows.add(focusRow);
            }
            JSONObject o = entries.optJSONObject(i);
            if (o != null) {
                View tile = card(Item.from(o, kind), false, null);
                line.addView(tile);
                focusRow.add(tile);
            }
        }
        if (!focusRows.isEmpty() && !focusRows.get(0).isEmpty()) focusRows.get(0).get(0).requestFocus();
    }

    private View card(Item item, boolean sports, Runnable onFocused) {
        int width = (getResources().getDisplayMetrics().widthPixels - dp(170)) / 5;
        if (section.equals("Home")) width = (getResources().getDisplayMetrics().widthPixels - dp(88) - dp(30)) / 4;
        FrameLayout box = new FrameLayout(this);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setClipChildren(false);
        int pictureHeight = Math.round(width * 9f / 16f);
        FrameLayout picture = new FrameLayout(this);
        picture.setTag("catalog-artwork");
        picture.setBackground(shape(PANEL, Color.TRANSPARENT));
        picture.setClipToOutline(true);
        box.addView(picture, new FrameLayout.LayoutParams(-1, pictureHeight));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(image, new FrameLayout.LayoutParams(-1, -1));
        String artwork = !sports && item.backdrop != null && !item.backdrop.isEmpty() ? item.backdrop : item.poster;
        api.image(image, artwork, sports);
        LinearLayout copy = column();
        copy.setTag("catalog-label"); copy.setPadding(dp(2), dp(7), dp(2), 0);
        FrameLayout.LayoutParams copyParams = new FrameLayout.LayoutParams(-1, -2); copyParams.topMargin = pictureHeight;
        box.addView(copy, copyParams);
        TextView label = text(item.title, 12, WHITE, true);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView info = text(item.year + (item.rating.isEmpty() ? "" : "  |  " + item.rating), 10, MUTED, false);
        info.setSingleLine(true);
        info.setPadding(0, dp(3), 0, 0);
        copy.addView(info, new LinearLayout.LayoutParams(-1, -2));
        box.setContentDescription(item.title + ". " + info.getText());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, pictureHeight + dp(40));
        p.rightMargin = dp(10);
        p.bottomMargin = dp(12);
        box.setLayoutParams(p);
        box.setOnClickListener(v -> { if (sports) showSportsPlayer(item); else openTitle(item); });
        box.setOnFocusChangeListener((v, focus) -> {
            picture.setForeground(shape(Color.TRANSPARENT, focus ? WHITE : Color.TRANSPARENT));
            box.animate().scaleX(focus ? 1.025f : 1).scaleY(focus ? 1.025f : 1).setDuration(220).start();
            box.setElevation(focus ? dp(5) : 0);
            if (focus && onFocused != null) onFocused.run();
        });
        return box;
    }

    private LinearLayout gridRow() {
        LinearLayout line = row(); line.setGravity(Gravity.TOP); line.setClipChildren(false); line.setClipToPadding(false);
        line.setPadding(dp(4), dp(4), dp(4), dp(4)); return line;
    }

    private void searchScreen() {
        LinearLayout search = row();
        content.addView(search);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Search movies and TV shows");
        query.setHintTextColor(MUTED);
        query.setTextColor(WHITE);
        query.setTextSize(16);
        query.setTypeface(REGULAR);
        query.setPadding(dp(16), 0, dp(16), 0);
        query.setBackground(shape(PANEL, AppStyle.BORDER));
        query.setOnFocusChangeListener((v, focused) -> query.setBackground(shape(PANEL, focused ? WHITE : AppStyle.BORDER)));
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout results = column();
        Runnable submit = () -> {
            String term = query.getText().toString().trim();
            if (term.isEmpty()) return;
            results.removeAllViews();
            results.addView(text("Searching...", 17, MUTED, false));
            int request = generation;
            api.object("/api/search?q=" + android.net.Uri.encode(term), (json, error) -> {
                if (request != generation) return;
                if (error != null || json == null) { results.removeAllViews(); results.addView(text("Search unavailable", 17, MUTED, false)); return; }
                fillGrid(results, json.optJSONArray("results"), "movie");
            });
        };
        Button go = button("Search", submit);
        LinearLayout.LayoutParams searchAction = new LinearLayout.LayoutParams(dp(110), dp(44));
        searchAction.leftMargin = dp(12);
        search.addView(go, searchAction);
        query.setOnEditorActionListener((v, actionId, event) -> { submit.run(); return true; });
        gap(content, 22);
        content.addView(results);
        query.requestFocus();
    }

    private void loadSportsRow() {
        CatalogRow state = createRow("Live Sports", null, "sport");
        state.complete = true;
        int request = generation;
        api.array("/api/streamed/matches/live", (json, error) -> {
            if (request != generation) return;
            if (error != null || json == null) { state.cards.addView(text("Sports unavailable", 18, MUTED, false)); return; }
            for (int i = 0; i < json.length(); i++) {
                JSONObject o = json.optJSONObject(i);
                if (o == null) continue;
                Item item = new Item();
                item.id = i;
                item.kind = "sport";
                item.title = o.optString("title", "Live event");
                item.poster = o.optString("poster", "");
                item.year = o.optString("category", "Live");
                item.rating = "";
                item.overview = o.optString("id", "");
                JSONArray sources = o.optJSONArray("sources");
                if (sources != null && sources.length() > 0) {
                    JSONObject source = sources.optJSONObject(0);
                    if (source != null) item.backdrop = source.optString("source") + "/" + source.optString("id");
                }
                View tile = card(item, true, null);
                state.cards.addView(tile);
                state.tiles.add(tile);
            }
            if (state.tiles.isEmpty()) state.cards.addView(text("No live events", 16, MUTED, false));
        });
    }

    private void showCollection(String name) {
        if (name.equals("Movies") || name.equals("TV Shows")) {
            activeFilters = name.equals("Movies") ? movieFilters : tvFilters;
            addCatalogFilters();
            collectionStatus = text("Loading...", 14, MUTED, false);
            collectionStatus.setPadding(0, dp(8), 0, dp(8));
            content.addView(collectionStatus, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        gap(content, 4);
        LinearLayout grid = column();
        content.addView(grid);
        String path;
        String kind;
        if (name.equals("Trending")) { path = "/api/tmdb/trending/all/day?page="; kind = "movie"; }
        else if (name.equals("Spotlight")) { path = "/api/tmdb/trending/movie/week?page="; kind = "movie"; }
        else if (name.equals("Movies")) { path = movieFilters.pathPrefix(); kind = "movie"; }
        else if (name.equals("TV Shows")) { path = tvFilters.pathPrefix(); kind = "tv"; }
        else if (name.equals("Crime TV") || name.equals("Comedy TV") || name.equals("Comedy movies") || name.equals("New releases")) {
            path = discoveryPath(name); kind = name.endsWith("TV") ? "tv" : "movie";
        }
        else if (name.equals("Anime")) {
            path = "/api/tmdb/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&include_adult=false&page=";
            kind = "tv";
        } else if (name.equals("Live Sports")) { path = null; kind = "sport"; }
        else { content.addView(text("Collection unavailable", 17, MUTED, false)); return; }
        FullCollection state = new FullCollection(path, kind, grid);
        fullCollection = state;
        if (path == null) {
            loadSportsCollection(state);
            return;
        }
        Button more = button("Load more", () -> { if (fullCollection != null) loadCollectionPage(fullCollection); });
        loadMoreButton = more;
        content.addView(more, new LinearLayout.LayoutParams(dp(180), dp(52)));
        more.setEnabled(false);
        more.setText("Loading...");
        if (!filterButtons.isEmpty()) filterButtons.get(0).requestFocus();
        loadCollectionPage(state);
    }

    private void addCatalogFilters() {
        TextView caption = text("FILTERS", 10, MUTED, true); caption.setLetterSpacing(.17f);
        content.addView(caption); gap(content, 8);
        LinearLayout bar = row();
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(54)));
        for (int i = 0; i <= CatalogFilters.TITLES.length; i++) {
            final int field = i;
            Button control = button("", () -> {
                lastFilterIndex = field;
                if (field == CatalogFilters.TITLES.length) {
                    activeFilters.reset();
                    applyCatalogFilters();
                } else showFilterOptions(field);
            });
            control.setSingleLine(false);
            control.setMaxLines(2);
            control.setTextSize(12);
            control.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, i == CatalogFilters.TITLES.length ? 0.7f : 1);
            if (i > 0) params.leftMargin = dp(8);
            bar.addView(control, params);
            filterButtons.add(control);
        }
        updateFilterLabels();
    }

    private void updateFilterLabels() {
        for (int i = 0; i < CatalogFilters.TITLES.length; i++) {
            Button button = filterButtons.get(i);
            button.setText((i == CatalogFilters.RATING ? "Rating" : CatalogFilters.TITLES[i]) + "\n" + activeFilters.label(i));
            button.setContentDescription(CatalogFilters.TITLES[i] + ": " + activeFilters.label(i));
        }
        filterButtons.get(CatalogFilters.TITLES.length).setText("Reset\nfilters");
    }

    private void showFilterOptions(int field) {
        CatalogFilters filters = activeFilters;
        List<CatalogFilters.Option> choices = filters.options(field);
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(CatalogFilters.TITLES[field] + (field == CatalogFilters.YEAR && section.equals("TV Shows") ? " (first aired)" : ""))
            .setSingleChoiceItems(labels, filters.selectedIndex(field), (d, index) -> {
                if (activeFilters == filters && filters.select(field, choices.get(index).value)) applyCatalogFilters();
                d.dismiss();
            }).setNegativeButton("Cancel", null).create();
        dialog.setOnDismissListener(d -> {
            if (activeFilters == filters && !filterButtons.isEmpty()) focusTile(filterButtons.get(field));
        });
        dialog.show();
        AppStyle.dialog(dialog, 460, 420);
        dialog.getListView().requestFocus();
        dialog.getListView().setSelection(filters.selectedIndex(field));
    }

    private void applyCatalogFilters() {
        if (activeFilters == null || fullCollection == null) return;
        activeFilters.save(getSharedPreferences("catalog_filters", MODE_PRIVATE));
        updateFilterLabels();
        // A new state owns page one and rejects any response from the old query.
        FullCollection previous = fullCollection;
        previous.grid.removeAllViews();
        focusRows.clear();
        fullCollection = new FullCollection(activeFilters.pathPrefix(), previous.kind, previous.grid);
        loadMoreButton.setVisibility(View.VISIBLE);
        focusTile(filterButtons.get(lastFilterIndex));
        loadCollectionPage(fullCollection);
    }

    private void loadCollectionPage(FullCollection state) {
        if (state.loading || state.complete || state.pathPrefix == null) return;
        state.loading = true;
        if (collectionStatus != null) collectionStatus.setText(state.tiles.isEmpty() ? "Loading..." : "Loading more...");
        boolean fromButton = getCurrentFocus() == loadMoreButton;
        if (loadMoreButton != null) { loadMoreButton.setEnabled(false); loadMoreButton.setText("Loading..."); }
        int page = state.nextPage;
        int request = generation;
        api.object(state.pathPrefix + page, (json, error) -> {
            if (request != generation || fullCollection != state) return;
            state.loading = false;
            if (error != null || json == null) {
                if (collectionStatus != null) collectionStatus.setText("Catalog unavailable. Select Retry loading or change filters.");
                else if (state.tiles.isEmpty()) {
                    state.grid.removeAllViews();
                    state.grid.addView(text("Catalog unavailable", 16, MUTED, false));
                }
                if (loadMoreButton != null) { loadMoreButton.setEnabled(true); loadMoreButton.setText("Retry loading"); }
                return;
            }
            if (state.tiles.isEmpty()) state.grid.removeAllViews();
            JSONArray entries = json.optJSONArray("results");
            if (entries == null || entries.length() == 0) state.complete = true;
            int start = state.tiles.size();
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject o = entries.optJSONObject(i);
                    if (o != null) appendCollectionItem(state, Item.from(o, state.kind));
                }
            }
            state.nextPage++;
            if (state.nextPage > json.optInt("total_pages", Integer.MAX_VALUE)) state.complete = true;
            if (loadMoreButton != null) {
                loadMoreButton.setVisibility(state.complete ? View.GONE : View.VISIBLE);
                loadMoreButton.setEnabled(!state.complete);
                loadMoreButton.setText("Load more");
            }
            if (collectionStatus != null) collectionStatus.setText(state.tiles.isEmpty()
                ? "No matches. Change a filter or select Reset filters."
                : state.tiles.size() + " shown" + (json.has("total_results") ? " of " + json.optInt("total_results") : ""));
            else if (state.tiles.isEmpty()) state.grid.addView(text("No results", 17, MUTED, false));
            if (start < state.tiles.size() && ((start == 0 && activeFilters == null) || fromButton)) {
                focusTile(state.tiles.get(start));
            } else if (fromButton && !state.tiles.isEmpty()) {
                focusTile(state.tiles.get(state.tiles.size() - 1));
            }
        });
    }

    private void appendCollectionItem(FullCollection state, Item item) {
        if (state.tiles.size() % 5 == 0) {
            LinearLayout line = gridRow();
            state.grid.setClipChildren(false);
            state.grid.addView(line);
            focusRows.add(new ArrayList<>());
        }
        View tile = card(item, item.kind.equals("sport"), null);
        ((LinearLayout) state.grid.getChildAt(state.grid.getChildCount() - 1)).addView(tile);
        focusRows.get(focusRows.size() - 1).add(tile);
        state.tiles.add(tile);
    }

    private void loadSportsCollection(FullCollection state) {
        int request = generation;
        api.array("/api/streamed/matches/live", (json, error) -> {
            if (request != generation || fullCollection != state) return;
            if (error != null || json == null) { state.grid.addView(text("Sports unavailable", 16, MUTED, false)); return; }
            for (int i = 0; i < json.length(); i++) {
                JSONObject o = json.optJSONObject(i);
                if (o == null) continue;
                Item item = new Item();
                item.id = i;
                item.kind = "sport";
                item.title = o.optString("title", "Live event");
                item.poster = o.optString("poster", "");
                item.year = o.optString("category", "Live");
                item.rating = "";
                item.overview = o.optString("id", "");
                JSONArray sources = o.optJSONArray("sources");
                if (sources != null && sources.length() > 0) {
                    JSONObject source = sources.optJSONObject(0);
                    if (source != null) item.backdrop = source.optString("source") + "/" + source.optString("id");
                }
                appendCollectionItem(state, item);
            }
            if (state.tiles.isEmpty()) state.grid.addView(text("No live events", 16, MUTED, false));
            else focusTile(state.tiles.get(0));
        });
    }

    private void openTitle(Item item) {
        // Opening a watched movie is a continue-watching action. Keep showDetail
        // separate so Back from playback never loops straight into playback again.
        WatchHistory.Entry saved = item.kind.equals("movie")
            ? watchHistory.get(WatchHistory.key(item.kind, item.id, 1, 1)) : null;
        if (saved != null && saved.resumePosition() > 0) showPlayer(item, 1, 1, saved.source);
        else showDetail(item);
    }

    private void showDetail(Item item) {
        android.view.inputmethod.InputMethodManager keyboard =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        selected = item;
        detailOpen = true;
        playerOpen = false;
        generation++;
        shell("Darkflix");
        // Artwork belongs to the full window, not the inset, scrolling preview card.
        ImageView hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(hero, 0, new FrameLayout.LayoutParams(-1, -1));
        api.image(hero, item.backdrop, false, 1280);
        View shade = new View(this);
        shade.setBackground(AppStyle.leftShade());
        root.addView(shade, 1, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00070a11, BG}));
        root.addView(fade, 2, new FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM));
        Button back = button("Back to " + section, () -> openSection(section));
        content.addView(back, new LinearLayout.LayoutParams(dp(200), dp(40)));
        gap(content, 12);
        FrameLayout preview = new FrameLayout(this);
        preview.setMinimumHeight(dp(304));
        content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout details = column();
        details.setPadding(0, dp(20), dp(20), dp(20));
        int detailWidth = Math.min(520, (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 140);
        preview.addView(details, new FrameLayout.LayoutParams(dp(detailWidth), -2, Gravity.BOTTOM | Gravity.LEFT));
        TextView name = text(item.title, 34, WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        gap(details, 10);
        // Catalog rows already carry genre IDs. Reuse the filter vocabulary so
        // the details screen does not need to wait for another network request.
        if (item.genres.isEmpty() && item.genreIds != null) {
            List<String> names = new ArrayList<>();
            List<CatalogFilters.Option> options = (item.kind.equals("tv") ? tvFilters : movieFilters).options(CatalogFilters.GENRE);
            for (int i = 0; i < item.genreIds.length(); i++) {
                String id = String.valueOf(item.genreIds.optInt(i, -1));
                for (CatalogFilters.Option option : options) {
                    if (option.value.equals(id) && !names.contains(option.label)) names.add(option.label);
                }
            }
            item.genres = TextUtils.join(", ", names);
        }
        TextView meta = text(item.metadata(), 12, AppStyle.TEXT, false);
        meta.setTag("title-metadata");
        meta.setMaxLines(2);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(meta);
        gap(details, 10);
        TextView synopsis = text(item.overview, 13, AppStyle.TEXT, false);
        synopsis.setMaxLines(3);
        synopsis.setEllipsize(TextUtils.TruncateAt.END);
        synopsis.setLineSpacing(dp(2), 1);
        details.addView(synopsis);
        gap(details, 16);
        String[] names = sourceNames(item);
        LinearLayout sources = null;
        for (int i = 0; i < names.length; i++) {
            if (i % 3 == 0) { sources = row(); details.addView(sources); }
            final int source = i;
            Button choice = button(names[i], () -> showPlayer(item, season, episode, source));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
            p.rightMargin = dp(8); p.bottomMargin = dp(8);
            sources.addView(choice, p);
            if (i == 0) choice.requestFocus();
        }
        trailerPreview = new TrailerPreview(this, root, shade, fade, pageChrome);
        trailerPreview.start(api, item.kind, item.id);
        int request = generation;
        api.object("/api/tmdb/" + item.kind + "/" + item.id, (json, error) -> {
            if (request != generation || json == null) return;
            item.overview = json.optString("overview", item.overview);
            synopsis.setText(item.overview);
            JSONArray genres = json.optJSONArray("genres");
            if (genres != null) {
                item.genres = Item.genreNames(genres);
                meta.setText(item.metadata());
            }
            if (item.kind.equals("tv")) addSeasons(item, json.optJSONArray("seasons"));
        });
    }

    private void addSeasons(Item item, JSONArray seasons) {
        if (seasons == null) return;
        gap(content, 22);
        content.addView(text("Episodes", 20, WHITE, true));
        gap(content, 12);
        HorizontalScrollView scroll = new TvHorizontalScrollView(this);
        LinearLayout choices = row();
        scroll.addView(choices);
        content.addView(scroll);
        LinearLayout episodes = column();
        content.addView(episodes);
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject o = seasons.optJSONObject(i);
            if (o == null) continue;
            int number = o.optInt("season_number", -1);
            if (number < 1) continue;
            Button b = button("Season " + number, () -> loadEpisodes(item, number, episodes));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(124), dp(38));
            p.rightMargin = dp(10);
            choices.addView(b, p);
        }
        loadEpisodes(item, 1, episodes);
    }

    private void loadEpisodes(Item item, int number, LinearLayout target) {
        season = number;
        target.removeAllViews();
        gap(target, 15);
        target.addView(text("Loading episodes...", 16, MUTED, false));
        int request = generation;
        api.object("/api/tmdb/tv/" + item.id + "/season/" + number, (json, error) -> {
            if (request != generation) return;
            target.removeAllViews();
            JSONArray entries = json == null ? null : json.optJSONArray("episodes");
            if (entries == null) { target.addView(text("Episodes unavailable", 16, MUTED, false)); return; }
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e == null) continue;
                int ep = e.optInt("episode_number", i + 1);
                LinearLayout entry = row();
                entry.setPadding(dp(12), dp(10), dp(16), dp(10));
                entry.setFocusableInTouchMode(true);
                entry.setClickable(true);
                entry.setBackground(shape(PANEL, Color.TRANSPARENT));
                entry.setOnClickListener(v -> chooseSource(item, number, ep));
                entry.setOnFocusChangeListener((v, focused) -> entry.setBackground(shape(focused ? CONTROL : PANEL, focused ? WHITE : Color.TRANSPARENT)));
                TextView ordinal = text(String.valueOf(ep), 20, MUTED, false);
                entry.addView(ordinal, new LinearLayout.LayoutParams(dp(40), -1));
                ImageView still = new ImageView(this);
                still.setScaleType(ImageView.ScaleType.CENTER_CROP);
                still.setBackground(shape(BG, Color.TRANSPARENT));
                still.setClipToOutline(true);
                entry.addView(still, new LinearLayout.LayoutParams(dp(124), dp(70)));
                api.image(still, e.optString("still_path", ""), false);
                LinearLayout copy = column();
                copy.setPadding(dp(18), 0, 0, 0);
                entry.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                TextView episodeName = text(e.optString("name", "Episode"), 16, WHITE, true);
                episodeName.setSingleLine(true);
                episodeName.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(episodeName);
                gap(copy, 6);
                TextView overview = text(e.optString("overview", e.optString("air_date", "")), 13, MUTED, false);
                overview.setMaxLines(2);
                overview.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(overview);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(94));
                p.topMargin = dp(8);
                target.addView(entry, p);
            }
        });
    }

    private void showSportsPlayer(Item item) {
        selected = item;
        showPlayer(item, 1, 1, 0);
    }

    private void chooseSource(Item item, int s, int e) {
        showSourceChooser(item, s, e, false);
    }

    private void showSourceChooser(Item item, int s, int e, boolean playing) {
        if (playing && playerOverlay != null) playerOverlay.suspendForMenu();
        String[] names = sourceNames(item);
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        if (playing) playerSourceDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (playerSourceDialog == dialog) playerSourceDialog = null;
            if (playing && playerOpen) {
                if (providerInput) focusVideo();
                else if (playerOverlay != null) playerOverlay.resumeFromMenu();
            }
        });
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        panel.addView(text("Choose source and player", 24, WHITE, true));
        gap(panel, 12);
        int active = playing ? currentSourceIndex : 0;
        Button initial = null;
        int[] order = item.kind.equals("sport") ? new int[]{0} : PlaybackChoice.displayOrder();
        for (int id : order) {
            final int source = id;
            Button choice = button(names[source], () -> {
                dialog.dismiss();
                if (playing) loadSource(item, source, s, e);
                else showPlayer(item, s, e, source);
            }, source == active);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(40));
            p.topMargin = dp(8);
            panel.addView(choice, p);
            if (source == active) initial = choice;
        }
        ScrollView choices = new ScrollView(this);
        choices.addView(panel);
        dialog.setView(choices, 0, 0, 0, 0);
        dialog.show();
        AppStyle.dialog(dialog, 420, -2);
        if (initial != null) initial.requestFocus();
    }

    private String[] sourceNames(Item item) {
        return item.kind.equals("sport") ? new String[]{"Stream 1"} : PlaybackChoice.labels();
    }

    private String sourceUrl(Item item, int source, int s, int e) {
        if (item.kind.equals("sport")) {
            if (item.backdrop == null || item.backdrop.isEmpty()) return "";
            return "https://embed.st/embed/" + item.backdrop + "/1";
        }
        String id = String.valueOf(item.id);
        String suffix = item.kind.equals("tv") ? "/tv/" + id + "/" + s + "/" + e : "/movie/" + id;
        source = PlaybackChoice.provider(source);
        if (source == 0) return "https://vidbolt.xyz" + suffix + "?theme=E50914&autoPlay=true";
        if (source == 1) return "https://embed.vidrift.in/embed" + suffix;
        return "https://bingr.one/watch" + suffix;
    }

    private void showPlayer(Item item, int s, int e, int source) {
        if (recovery != null) recovery.save();
        String key = WatchHistory.key(item.kind, item.id, s, e);
        WatchHistory.Entry saved = watchHistory.get(key);
        showPlayerAtPosition(item, s, e, source, saved == null ? -1 : saved.resumePosition());
    }

    private void showPlayerAtPosition(Item item, int s, int e, int source, long position) {
        finishRecovery();
        initialPlaybackPosition = position;
        releaseTrailerPreview();
        releaseDiscovery();
        selected = item;
        season = s;
        episode = e;
        currentSourceIndex = item.kind.equals("sport") ? 0 : PlaybackChoice.normalize(source);
        source = currentSourceIndex;
        playerOpen = true;
        detailOpen = !item.kind.equals("sport");
        generation++;
        clearCustomVideoView();
        sourceRequest++;
        if (playerOverlay != null) { playerOverlay.deactivate(); playerOverlay = null; }
        releaseNativePlayer();
        releaseRiftPlayer();
        releaseBoltPlayer();
        if (player != null) { player.stopLoading(); player.destroy(); player = null; }
        root.removeAllViews();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Playback has one edge-to-edge surface. All actions belong to the
        // auto-hiding overlay; there is no separate windowed player screen.
        playerScreen = new FrameLayout(this);
        playerScreen.setBackgroundColor(Color.BLACK);
        playerScreen.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) playerScreen.setDefaultFocusHighlightEnabled(false);
        root.addView(playerScreen, new FrameLayout.LayoutParams(-1, -1));
        String[] names = sourceNames(item);
        player = new WebView(this);
        final WebView sessionPlayer = player;
        player.setBackgroundColor(Color.BLACK);
        player.setFocusable(true);
        player.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) player.setDefaultFocusHighlightEnabled(false);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(false);
        player.getSettings().setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(player, true);
        // The DOM bridge observes progress; explicit Web mode owns the inputs.
        embeddedPlayer = new EmbeddedPlayback(player);
        final SportsEmbedCompat sportsCompat = item.kind.equals("sport") ? new SportsEmbedCompat(player) : null;
        player.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg) { return false; }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (!playerOpen || player != sessionPlayer || nativeOnlySource || nativePlayer != null || customVideoView != null) { callback.onCustomViewHidden(); return; }
                customVideoView = view;
                if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
                customVideoCallback = callback;
                playerScreen.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                enterPlayerFullscreen();
            }
            @Override public void onHideCustomView() {
                if (player != sessionPlayer) return;
                clearCustomVideoView();
                if (playerOpen) enterPlayerFullscreen();
            }
        });
        player.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return sportsCompat == null ? null : sportsCompat.intercept(request);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (view != player || nativeOnlySource || nativePlayer != null) return;
                // Do not unmute an outgoing resolver before its replacement page
                // has committed. Fast/sports keep their visible web playback.
                // loadDataWithBaseURL may report about:blank from getUrl() when
                // historyUrl is null; onPageFinished carries the actual base URL.
                if ((item.kind.equals("sport") && url.startsWith("https://embed.st/"))
                        || (!item.kind.equals("sport") && (url.equals("https://pantyflix.com/")
                        || url.startsWith("https://com.okonu.flipflix/bolt/") || url.startsWith("https://vidbolt.xyz/")
                        || url.startsWith("https://com.okonu.flipflix/rift/") || url.startsWith("https://embed.vidrift.in/")
                        || url.startsWith("https://bingr.one/")))) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(view, false);
                }
                if (embeddedPlayer != null) embeddedPlayer.pageFinished();
                if (providerInput && playerSourceDialog == null && webActionsDialog == null) focusVideo();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (nativeOnlySource || riftPlayer != null || boltPlayer != null) return true;
                String host = request.getUrl().getHost();
                return host == null || !(host.equals("pantyflix.com") || host.equals("vidbolt.xyz") || host.equals("bingr.one") || host.equals("embed.vidrift.in") || host.equals("embed.st"));
            }
        });
        playerScreen.addView(player, new FrameLayout.LayoutParams(-1, -1));
        player.setVisibility(View.GONE);
        playerStatus = text("Preparing " + names[source] + "...", 20, WHITE, true);
        playerStatus.setGravity(Gravity.CENTER);
        playerScreen.addView(playerStatus, new FrameLayout.LayoutParams(-1, -1));
        loadSource(item, source, s, e);
        buildPlayerOverlay(item);
        if (position > 0) playerOverlay.offerRestart(() -> { if (recovery != null) recovery.startOver(); });
        enterPlayerFullscreen();
    }

    private void enterPlayerFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (playerOverlay != null) {
            if (providerInput) { playerOverlay.deactivate(); focusVideo(); }
            else playerOverlay.activate();
        }
    }

    private void buildPlayerOverlay(Item item) {
        String title = item.title + (item.kind.equals("tv") ? "  |  S" + season + " E" + episode : "");
        playerOverlay = new PlaybackControls(this, this::playbackBackend,
            this::focusVideo, title,
            item.kind.equals("tv") ? () -> showPlayer(item, season, Math.max(1, episode - 1), currentSourceIndex) : null,
            item.kind.equals("tv") ? () -> showPlayer(item, season, episode + 1, currentSourceIndex) : null,
            () -> choosePlayerSource(item), () -> loadSource(item, currentSourceIndex, season, episode), this::closePlayer);
        playerOverlay.sourceName(sourceNames(item)[currentSourceIndex]);
        root.addView(playerOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void focusVideo() {
        if (!providerInput && playerScreen != null) {
            // A focused WebView can wake its own controls and draw a full-screen
            // Android focus border. Park input on a nonvisual native container.
            if (playerScreen.isShown()) playerScreen.requestFocus();
            else {
                root.setFocusableInTouchMode(true);
                if (android.os.Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false);
                root.requestFocus();
            }
        }
        else if (nativePlayer != null) nativePlayer.view.requestFocus();
        else if (customVideoView != null) customVideoView.requestFocus();
        else if (player != null) {
            player.requestFocus();
            if (providerInput) player.evaluateJavascript("(function(){var f=document.querySelector('iframe');if(f)f.focus();})();", null);
        }
    }

    private void revealPlayerOverlay(boolean focus) {
        if (playerOpen && playerOverlay != null) {
            if (providerInput) { playerOverlay.deactivate(); focusVideo(); }
            else playerOverlay.show(focus);
        }
    }

    private void choosePlayerSource(Item item) {
        showSourceChooser(item, season, episode, true);
    }

    private void loadSource(Item item, int source, int s, int e) {
        // Capture and persist once, before releasing the outgoing provider.
        long resumePosition = recovery == null ? initialPlaybackPosition : recovery.handoff();
        recovery = null; initialPlaybackPosition = -1;
        clearCustomVideoView();
        currentSourceIndex = source;
        nativeOnlySource = !item.kind.equals("sport") && PlaybackChoice.nativeMode(source);
        int request = ++sourceRequest;
        closeStreamDiscovery();
        if (playerOverlay != null) playerOverlay.sourceName(sourceNames(item)[source]);
        releaseNativePlayer();
        releaseRiftPlayer();
        releaseBoltPlayer();
        providerInput = !item.kind.equals("sport") && !nativeOnlySource;
        if (playerOverlay != null) {
            if (providerInput) playerOverlay.deactivate();
            else playerOverlay.activate();
        }
        if (embeddedPlayer != null) embeddedPlayer.reset();
        // The next provider load replaces this document. Do not race a separate
        // about:blank navigation with registering the new session's bridges.
        if (player != null) {
            player.stopLoading(); player.setVisibility(View.GONE);
            player.setAlpha(nativeOnlySource ? 0f : 1f);
            player.setFocusable(!nativeOnlySource);
            player.setImportantForAccessibility(nativeOnlySource ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        if (playerStatus != null) {
            playerStatus.setText("Preparing " + sourceNames(item)[source] + "...");
            playerStatus.setVisibility(View.VISIBLE);
        }
        if (nativeOnlySource) {
            streamDiscovery = new StreamDiscovery(player, sourceUrl(item, source, s, e), nativeOnlySource, stream -> {
                handoffToNative(item, source, s, e, stream, request, resumePosition);
            });
        }
        if (nativeOnlySource) loadNativeResolver(item, source, s, e, request);
        else if (!item.kind.equals("sport") && PlaybackChoice.provider(source) == 0) loadBoltWeb(item, s, e, resumePosition);
        else if (!item.kind.equals("sport") && PlaybackChoice.provider(source) == 1) loadRiftWeb(item, s, e);
        else loadEmbed(item, source, s, e);
        startRecovery(item, source, s, e, resumePosition);
        if (providerInput) useProviderControls();
    }

    private void loadNativeResolver(Item item, int source, int s, int e, int request) {
        if (streamDiscovery == null || !streamDiscovery.active()) {
            nativeDiscoveryFailed(request); return;
        }
        // Keep a renderable surface for media metadata, but no input, picture,
        // sound, player API, fullscreen permission, or watch-history clock.
        player.setVisibility(View.VISIBLE);
        String url = sourceUrl(item, source, s, e);
        if (PlaybackChoice.provider(source) == 1) url += "?fs=parent&hide=next,episodes";
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;background:#000;overflow:hidden'><iframe title='Stream discovery' src='"
            + TextUtils.htmlEncode(url) + "' allow='autoplay' style='border:0;width:100vw;height:100vh'></iframe></body></html>";
        player.loadDataWithBaseURL("https://com.okonu.flipflix/resolve/", html, "text/html", "UTF-8", null);
        nativeDiscoveryDeadline = () -> nativeDiscoveryFailed(request);
        ui.postDelayed(nativeDiscoveryDeadline, 45000);
        revealPlayerOverlay(false);
    }

    private void nativeDiscoveryFailed(int request) {
        if (!playerOpen || !nativeOnlySource || request != sourceRequest || nativePlayer != null) return;
        ++sourceRequest; // Reject queued results from the expired discovery lease.
        closeStreamDiscovery();
        player.stopLoading(); player.loadUrl("about:blank"); player.setVisibility(View.GONE);
        if (playerStatus != null) {
            playerStatus.setText("No usable native stream found. Use Source to select Web mode, or Retry.");
            playerStatus.setVisibility(View.VISIBLE);
        }
        revealPlayerOverlay(true);
    }

    private void loadBoltWeb(Item item, int s, int e, long position) {
        player.setAlpha(1f); player.setFocusable(true);
        player.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        player.setVisibility(View.VISIBLE); playerStatus.setVisibility(View.GONE);
        if (BoltPlayback.supported()) {
            boltPlayer = new BoltPlayback(player, embeddedPlayer);
            boltPlayer.load(sourceUrl(item, 3, s, e), position, true);
        } else loadEmbed(item, 3, s, e);
        revealPlayerOverlay(false);
    }

    private void loadRiftWeb(Item item, int s, int e) {
        player.setVisibility(View.VISIBLE); playerStatus.setVisibility(View.GONE);
        if (RiftPlayback.supported()) {
            riftPlayer = new RiftPlayback(player,
                message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(),
                this::useProviderControls, this::closePlayer, true);
            riftPlayer.load(sourceUrl(item, 4, s, e), item.id, item.kind, s, e);
        } else loadEmbed(item, 4, s, e);
    }

    private void handoffToNative(Item item, int source, int s, int e, StreamDiscovery.Stream stream, int request, long initial) {
        if (!playerOpen || !nativeOnlySource || request != sourceRequest || nativePlayer != null) return;
        long position = recovery == null ? Math.max(0, initial) : recovery.handoff();
        recovery = null;
        closeStreamDiscovery();
        releaseRiftPlayer(); releaseBoltPlayer();
        if (embeddedPlayer != null) embeddedPlayer.reset();
        clearCustomVideoView();
        player.stopLoading(); player.loadUrl("about:blank"); player.setVisibility(View.GONE);
        playNativeStream(item, source, s, e, stream, request, position);
        startRecovery(item, source, s, e, position);
    }

    private void startRecovery(Item item, int source, int s, int e, long resumePosition) {
        String historyKey = WatchHistory.key(item.kind, item.id, s, e);
        if (!historyKey.isEmpty()) {
            JSONObject catalog = new JSONObject();
            try {
                catalog.put("id", item.id).put("media_type", item.kind).put("title", item.title).put("year", item.year)
                    .put("backdrop_path", item.backdrop).put("poster_path", item.poster).put("overview", item.overview)
                    .put("vote_average", item.rating.isEmpty() ? 0 : Double.parseDouble(item.rating));
            } catch (Exception ignored) { }
            recovery = new PlaybackRecovery(watchHistory, historyKey, catalog, s, e, source, resumePosition, this::playbackBackend,
                message -> android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show());
            if (foreground) recovery.resume();
        }
    }

    private PlaybackBackend playbackBackend() {
        if (nativePlayer != null) return nativePlayer;
        if (nativeOnlySource) return WAITING_FOR_NATIVE;
        if (riftPlayer != null && riftPlayer.state().available) return riftPlayer;
        return boltPlayer != null ? boltPlayer : embeddedPlayer;
    }
    private void releaseBoltPlayer() { if (boltPlayer != null) { boltPlayer.close(); boltPlayer = null; } }
    private void finishRecovery() { if (recovery != null) { recovery.close(); recovery = null; } }

    private void useProviderControls() {
        if (nativeOnlySource) return;
        providerInput = true;
        if (riftPlayer != null) riftPlayer.nativeControls(false);
        if (playerOverlay != null) playerOverlay.deactivate();
        focusVideo();
        android.widget.Toast.makeText(this, "Web player: use arrows and OK. Back or Menu opens playback options.", android.widget.Toast.LENGTH_LONG).show();
    }

    private void showWebActions() {
        if (!playerOpen || !providerInput || webActionsDialog != null || playerSourceDialog != null) return;
        final Item item = selected;
        final int source = currentSourceIndex, s = season, e = episode;
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        webActionsDialog = dialog;
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(20), dp(24), dp(20));
        panel.addView(text(sourceNames(item)[source], 22, WHITE, true));
        gap(panel, 8);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Return to web player"); actions.add(this::focusVideo);
        labels.add("Change source / player"); actions.add(() -> choosePlayerSource(item));
        labels.add("Retry playback"); actions.add(() -> loadSource(item, source, s, e));
        labels.add("Start from beginning"); actions.add(() -> { if (recovery != null) recovery.startOver(); });
        if (item.kind.equals("tv")) {
            if (e > 1) { labels.add("Previous episode"); actions.add(() -> showPlayer(item, s, e - 1, source)); }
            labels.add("Next episode"); actions.add(() -> showPlayer(item, s, e + 1, source));
        }
        labels.add("Close playback"); actions.add(this::closePlayer);
        Button initial = null;
        for (int i = 0; i < labels.size(); i++) {
            Runnable action = actions.get(i);
            Button choice = button(labels.get(i), () -> { dialog.dismiss(); action.run(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(38)); p.topMargin = dp(6);
            panel.addView(choice, p);
            if (initial == null) initial = choice;
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        dialog.setView(scroll, 0, 0, 0, 0);
        dialog.setOnDismissListener(d -> {
            if (webActionsDialog == dialog) webActionsDialog = null;
            if (playerOpen && providerInput && playerSourceDialog == null) focusVideo();
        });
        dialog.show(); AppStyle.dialog(dialog, 420, -2);
        if (initial != null) initial.requestFocus();
    }

    private void releaseRiftPlayer() {
        if (riftPlayer != null) { riftPlayer.close(); riftPlayer = null; }
        providerInput = false;
    }

    private void playNativeVideo(Item item, int source, int s, int e, String stream, String referer, JSONArray captions, int request) {
        playNativeStream(item, source, s, e, new StreamDiscovery.Stream(stream, referer, "Darkflix/0.1.32", captions, 0), request, 0);
    }

    private void playNativeStream(Item item, int source, int s, int e, StreamDiscovery.Stream stream, int request, long position) {
        NativePlayback video = new NativePlayback(this, stream.url, stream.referer, stream.agent, stream.captions);
        nativePlayer = video;
        providerInput = false;
        playerScreen.addView(video.view, 0, new FrameLayout.LayoutParams(-1, -1));
        if (playerStatus != null) { playerStatus.setText("Opening native player..."); playerStatus.setVisibility(View.VISIBLE); }
        video.setListener(new NativePlayback.Listener() {
            private boolean current() { return playerOpen && request == sourceRequest && nativePlayer == video; }
            @Override public void ready() {
                if (!current()) return;
                // Never replace a feature with an unrelated short ad/manifest.
                long actual = video.state().duration;
                if (stream.duration > 0 && (actual <= 0 || Math.abs(actual - stream.duration) > Math.max(10000, stream.duration / 20))) {
                    video.reject(); return;
                }
                if (playerStatus != null) playerStatus.setVisibility(View.GONE);
                if (!foreground) video.command("pause", "");
                if (playerOverlay != null) playerOverlay.sourceName(sourceNames(item)[source] + " / " + video.engine());
            }
            @Override public void failed() {
                if (!current()) return;
                // Honor the user's engine choice. Never silently switch to Web.
                if (playerStatus != null) { playerStatus.setText("Native playback unavailable. Use Source to select Web mode, or Retry."); playerStatus.setVisibility(View.VISIBLE); }
                revealPlayerOverlay(true);
            }
        });
        video.start(position);
        if (!foreground) video.command("pause", "");
        enterPlayerFullscreen();
    }

    private void closeStreamDiscovery() {
        if (nativeDiscoveryDeadline != null) { ui.removeCallbacks(nativeDiscoveryDeadline); nativeDiscoveryDeadline = null; }
        if (streamDiscovery != null) { streamDiscovery.close(); streamDiscovery = null; }
    }

    private void releaseNativePlayer() {
        closeStreamDiscovery();
        if (nativePlayer == null) return;
        if (playerScreen != null) playerScreen.removeView(nativePlayer.view);
        nativePlayer.release();
        nativePlayer = null;
    }

    private void clearCustomVideoView() {
        if (customVideoView == null) return;
        root.removeView(customVideoView);
        customVideoView = null;
        if (customVideoCallback != null) {
            WebChromeClient.CustomViewCallback callback = customVideoCallback;
            customVideoCallback = null;
            callback.onCustomViewHidden();
        }
        if (playerScreen != null) playerScreen.setVisibility(View.VISIBLE);
    }

    private void closePlayer() {
        if (!playerOpen) return;
        playerOpen = false;
        if (webActionsDialog != null) webActionsDialog.dismiss();
        if (playerSourceDialog != null) playerSourceDialog.dismiss();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (section.equals("History")) openSection(section);
        else if (selected != null && !selected.kind.equals("sport")) showDetail(selected);
        else openSection("Live Sports");
        if (updater != null) updater.stateChanged();
    }

    private void loadEmbed(Item item, int source, int s, int e) {
        if (player == null) return;
        if (playerStatus != null) playerStatus.setVisibility(View.GONE);
        player.setVisibility(View.VISIBLE);
        String url = sourceUrl(item, source, s, e);
        if (url.isEmpty()) return;
        String html = "<!doctype html><html lang='en'><head><title>Web player</title><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
            "<body style='margin:0;background:#000;overflow:hidden'><iframe title='Video' src='" + TextUtils.htmlEncode(url) +
            "' " + (providerInput ? "onload='this.focus()' " : "") + "allow='autoplay;encrypted-media;fullscreen;picture-in-picture' allowfullscreen style='border:0;width:100vw;height:100vh'></iframe></body></html>";
        if (!item.kind.equals("sport") && embeddedPlayer != null && embeddedPlayer.documentStart) {
            player.loadDataWithBaseURL("https://pantyflix.com/", html, "text/html", "UTF-8", null);
        } else player.loadUrl(url);
        revealPlayerOverlay(false);
    }

    private boolean moveCatalogFocus(int keyCode) {
        View focused = getCurrentFocus();
        if (focused == null) return false;
        // Filters remain reachable even while loading or when there are no results.
        if (!filterButtons.isEmpty()) {
            if (focused == topAction && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return focusTile(filterButtons.get(lastFilterIndex));
            }
            int index = filterButtons.indexOf(focused);
            if (index >= 0) {
                lastFilterIndex = index;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (index == 0 && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return enterRail();
                    lastFilterIndex = Math.max(0, Math.min(filterButtons.size() - 1,
                        index + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    return focusTile(filterButtons.get(lastFilterIndex));
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return focusTile(topAction);
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!focusRows.isEmpty() && !focusRows.get(0).isEmpty()) {
                        List<View> row = focusRows.get(0);
                        return focusTile(row.get(Math.min(index, row.size() - 1)));
                    }
                    if (loadMoreButton != null && loadMoreButton.isShown() && loadMoreButton.isEnabled()) return focusTile(loadMoreButton);
                    return true;
                }
            }
            if (focused == loadMoreButton && focusRows.isEmpty() && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return focusTile(filterButtons.get(lastFilterIndex));
            }
        }
        if (focusRows.isEmpty()) return false;
        if (focused == topAction && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (section.equals("Home") && !catalogRows.isEmpty()) return focusTile(catalogRows.get(0).headingView);
            if (!focusRows.get(0).isEmpty()) return focusTile(focusRows.get(0).get(0));
        }
        if (focused == loadMoreButton && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            for (int r = focusRows.size() - 1; r >= 0; r--) {
                List<View> row = focusRows.get(r);
                if (!row.isEmpty()) return focusTile(row.get(row.size() - 1));
            }
        }
        for (int r = 0; r < catalogRows.size(); r++) {
            if (focused != catalogRows.get(r).headingView) continue;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                List<View> tiles = catalogRows.get(r).tiles;
                return tiles.isEmpty() || focusTile(tiles.get(0));
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return r + 1 >= catalogRows.size() || focusTile(catalogRows.get(r + 1).headingView);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return focusTile(r == 0 ? topAction : catalogRows.get(r - 1).headingView);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
        }
        int rowIndex = -1;
        int columnIndex = -1;
        for (int r = 0; r < focusRows.size(); r++) {
            int c = focusRows.get(r).indexOf(focused);
            if (c >= 0) { rowIndex = r; columnIndex = c; break; }
        }
        if (rowIndex < 0) return false;
        List<View> row = focusRows.get(rowIndex);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (columnIndex > 0) return focusTile(row.get(columnIndex - 1));
            if (section.equals("Home") && rowIndex < catalogRows.size()) return focusTile(catalogRows.get(rowIndex).headingView);
            return enterRail();
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (columnIndex + 1 < row.size()) return focusTile(row.get(columnIndex + 1));
            if (section.equals("Home") && rowIndex < catalogRows.size()) {
                CatalogRow state = catalogRows.get(rowIndex);
                if (state.complete) return focusTile(row.get(0));
                state.pendingFrom = focused;
                loadNextPage(state);
            } else if (rowIndex + 1 < focusRows.size() && !focusRows.get(rowIndex + 1).isEmpty()) {
                return focusTile(focusRows.get(rowIndex + 1).get(0));
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && section.equals("Home") && rowIndex < catalogRows.size()) {
                return focusTile(catalogRows.get(rowIndex).headingView);
            }
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
            for (int r = rowIndex + direction; r >= 0 && r < focusRows.size(); r += direction) {
                List<View> targetRow = focusRows.get(r);
                if (!targetRow.isEmpty()) return focusTile(targetRow.get(Math.min(columnIndex, targetRow.size() - 1)));
            }
            if (direction < 0) {
                if (section.equals("Home") && !catalogRows.isEmpty()) return focusTile(catalogRows.get(0).headingView);
                if (!filterButtons.isEmpty()) return focusTile(filterButtons.get(lastFilterIndex));
                if (topAction != null) return focusTile(topAction);
            } else if (loadMoreButton != null && loadMoreButton.getVisibility() == View.VISIBLE && loadMoreButton.isEnabled()) {
                return focusTile(loadMoreButton);
            }
            return true;
        }
        return false;
    }

    private boolean focusTile(View target) {
        if (!target.requestFocus()) return false;
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), false);
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (startupIntro != null && startupIntro.handleKey(event)) return true;
        if (discovery != null && discovery.handleKey(event)) return true;
        if (trailerPreview != null && trailerPreview.handleKey(event)) return true;
        int code = event.getKeyCode();
        if (code == providerReturnKey) {
            if (event.getAction() == KeyEvent.ACTION_UP || event.getRepeatCount() > 0) {
                if (event.getAction() == KeyEvent.ACTION_UP) providerReturnKey = -1;
                return true;
            }
            // A dialog can receive the previous key-up after gaining focus.
            // A fresh press must not be swallowed by that old return key.
            providerReturnKey = -1;
        }
        // The dialog can be visible before WindowManager transfers input. Back
        // still belongs to the source menu, never the movie underneath it.
        if (code == KeyEvent.KEYCODE_BACK && playerSourceDialog != null && playerSourceDialog.isShowing()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                providerReturnKey = code;
                playerSourceDialog.dismiss();
            }
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) && webActionsDialog != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                providerReturnKey = code; webActionsDialog.dismiss();
            }
            return true;
        }
        if (playerOpen && providerInput) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    providerReturnKey = code; showWebActions();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        // Own the complete Back press before a provider's custom video view can
        // consume it. Dialogs have their own window and still dismiss normally.
        if (code == KeyEvent.KEYCODE_BACK && (playerOpen || playerBackKeyDown)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                playerBackKeyDown = true;
                if (event.getRepeatCount() == 0) closePlayer();
            } else if (event.getAction() == KeyEvent.ACTION_UP) playerBackKeyDown = false;
            return true;
        }
        if (playerOpen && playerOverlay != null && playerOverlay.handleKey(event)) return true;
        boolean directional = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!playerOpen && navigationRail != null && navigationRail.isAttachedToWindow()) {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showBrowseMenu();
                return true;
            }
            if (directional && navigationRail.hasFocus()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) navigationRail.navigate(code, this::returnFromRail);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                View current = getCurrentFocus();
                // Leave text-field caret movement alone. Catalog row boundaries are explicit below.
                if (current != null && !(current instanceof EditText)) {
                    View left = current.focusSearch(View.FOCUS_LEFT);
                    if (left != null && navigationRail.buttons.contains(left)) return enterRail();
                }
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && directional) {
            if (!playerOpen && !detailOpen && moveCatalogFocus(code)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (introActive()) return;
        if (webActionsDialog != null) webActionsDialog.dismiss();
        else if (playerSourceDialog != null && playerSourceDialog.isShowing()) playerSourceDialog.dismiss();
        else if (playerOpen && providerInput) showWebActions();
        else if (playerOpen) closePlayer();
        else if (detailOpen) openSection(section);
        else if (!section.equals("Home")) openSection("Home");
        else super.onBackPressed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onBackPressed(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        foreground = false;
        if (startupIntro != null) startupIntro.close();
        finishRecovery();
        if (updater != null) updater.close();
        releaseTrailerPreview();
        releaseDiscovery();
        playerOpen = false;
        if (webActionsDialog != null) webActionsDialog.dismiss();
        if (playerSourceDialog != null) playerSourceDialog.dismiss();
        sourceRequest++;
        if (playerOverlay != null) playerOverlay.deactivate();
        clearCustomVideoView();
        releaseNativePlayer();
        releaseRiftPlayer();
        releaseBoltPlayer();
        if (player != null) { player.stopLoading(); player.destroy(); }
        api.close();
        super.onDestroy();
    }

    private void releaseTrailerPreview() {
        if (trailerPreview != null) { trailerPreview.close(); trailerPreview = null; }
    }

    @Override protected void onPause() {
        foreground = false;
        if (startupIntro != null) startupIntro.close();
        if (recovery != null) recovery.pause();
        if (updater != null) updater.pause();
        releaseTrailerPreview();
        if (riftPlayer != null) riftPlayer.command("pause", "");
        if (boltPlayer != null) boltPlayer.command("pause", "");
        if (playerOpen && providerInput && riftPlayer == null && boltPlayer == null && embeddedPlayer != null) embeddedPlayer.command("pause", "");
        if (nativePlayer != null) nativePlayer.command("pause", "");
        if (discovery != null) discovery.setActive(false);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (section.equals("Home") && !detailOpen && !playerOpen && discovery != null
                && discoveryExpired(android.os.SystemClock.elapsedRealtime())) openSection("Home");
        if (recovery != null) recovery.resume();
        if (updater != null) updater.resume();
        if (discovery != null) discovery.setActive(hasWindowFocus() && !introActive());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (trailerPreview != null) trailerPreview.onWindowFocusChanged(hasFocus);
        if (discovery != null) discovery.setActive(hasFocus && foreground && !introActive());
        if (updater != null) updater.stateChanged();
        if (hasFocus && playerOpen && providerInput && playerSourceDialog == null && webActionsDialog == null) focusVideo();
    }

    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (updater != null) updater.activityResult(request);
    }
}
