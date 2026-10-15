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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int BG = Color.BLACK;
    private static final int PANEL = Color.rgb(24, 24, 24);
    private static final int CONTROL = Color.rgb(65, 65, 65);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(179, 179, 179);
    private static final int ACCENT = Color.rgb(229, 9, 20);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final CatalogApi api = new CatalogApi();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<List<View>> focusRows = new ArrayList<>();
    private final List<CatalogRow> catalogRows = new ArrayList<>();
    private FullCollection fullCollection;
    private Button loadMoreButton;
    private FrameLayout root;
    private LinearLayout content;
    private WebView player;
    private NativePlayback nativePlayer;
    private EmbeddedPlayback embeddedPlayer;
    private TextView playerStatus;
    private FrameLayout playerScreen;
    private PlaybackControls playerOverlay;
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
            return item;
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
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        openSection("Home");
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable shape(int color, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(4));
        d.setStroke(dp(2), border);
        return d;
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
        b.setTextSize(16);
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
        b.setBackground(shape(primary ? WHITE : CONTROL, Color.TRANSPARENT));
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
            b.setBackground(shape(primary ? (focused ? 0xffe6e6e6 : WHITE) : (focused ? 0xff545454 : CONTROL), focused ? WHITE : Color.TRANSPARENT));
            b.setTextColor(primary ? BG : WHITE);
            if (icon != null) icon.color(primary ? BG : WHITE);
            b.setElevation(focused ? dp(4) : 0);
        });
        return b;
    }

    private void shell(String title) {
        if (playerOverlay != null) playerOverlay.deactivate();
        clearCustomVideoView();
        sourceRequest++;
        releaseNativePlayer();
        playerOverlay = null;
        focusRows.clear();
        catalogRows.clear();
        fullCollection = null;
        loadMoreButton = null;
        topAction = null;
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (player != null) { player.stopLoading(); player.destroy(); player = null; }
        embeddedPlayer = null;
        playerScreen = null;
        playerStatus = null;
        root.removeAllViews();
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        root.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
        content = column();
        content.setPadding(dp(36), dp(16), dp(36), dp(34));
        scroller.addView(content);
        LinearLayout header = row();
        content.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));
        boolean brandTitle = title.equals("Flipflix");
        TextView brand = text(brandTitle ? "FLIPFLIX" : title, brandTitle ? 30 : 26, brandTitle ? ACCENT : WHITE, true);
        if (brandTitle) { brand.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); brand.setLetterSpacing(0.025f); }
        header.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        Button action = button(section.equals("Home") ? "Search" : "Home", () -> openSection(section.equals("Home") ? "Search" : "Home"));
        topAction = action;
        header.addView(action, new LinearLayout.LayoutParams(dp(120), dp(40)));
        gap(content, 16);
    }

    private void openSection(String name) {
        section = name;
        detailOpen = false;
        playerOpen = false;
        generation++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shell(name.equals("Home") ? "Flipflix" : name);
        if (name.equals("Search")) searchScreen();
        else if (name.equals("Home")) {
            loadRow("Trending", "/api/tmdb/trending/all/day?page=", "movie");
            loadRow("Movies", moviePath(), "movie");
            loadRow("TV Shows", tvPath(), "tv");
            loadRow("Anime", "/api/tmdb/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&include_adult=false&page=", "tv");
            loadSportsRow();
        } else showCollection(name);
    }

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
                line = row(); line.setGravity(Gravity.TOP); target.addView(line);
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
        int width = (getResources().getDisplayMetrics().widthPixels - dp(72) - dp(40)) / 5;
        if (section.equals("Home")) width = (getResources().getDisplayMetrics().widthPixels - dp(88) - dp(30)) / 4;
        FrameLayout box = new FrameLayout(this);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setPadding(dp(3), dp(3), dp(3), dp(3));
        box.setBackground(shape(BG, Color.TRANSPARENT));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(shape(BG, Color.TRANSPARENT));
        image.setClipToOutline(true);
        box.addView(image, new FrameLayout.LayoutParams(-1, -1));
        String artwork = !sports && item.backdrop != null && !item.backdrop.isEmpty() ? item.backdrop : item.poster;
        api.image(image, artwork, sports);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00000000, 0xc0000000, Color.BLACK}));
        box.addView(shade, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));
        LinearLayout copy = column();
        copy.setPadding(dp(8), 0, dp(8), dp(7));
        box.addView(copy, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TextView label = text(item.title, 14, WHITE, true);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        copy.addView(label, new LinearLayout.LayoutParams(-1, -2));
        TextView info = text(item.year + (item.rating.isEmpty() ? "" : "  |  " + item.rating), 12, MUTED, false);
        info.setSingleLine(true);
        info.setPadding(0, dp(3), 0, 0);
        copy.addView(info, new LinearLayout.LayoutParams(-1, -2));
        box.setContentDescription(item.title + ". " + info.getText());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, Math.round((width - dp(6)) * 9f / 16f) + dp(6));
        p.rightMargin = dp(10);
        p.bottomMargin = dp(8);
        box.setLayoutParams(p);
        box.setOnClickListener(v -> { if (sports) showSportsPlayer(item); else showDetail(item); });
        box.setOnFocusChangeListener((v, focus) -> {
            box.setBackground(shape(BG, focus ? WHITE : Color.TRANSPARENT));
            box.setElevation(focus ? dp(6) : 0);
            if (focus && onFocused != null) onFocused.run();
        });
        return box;
    }

    private void searchScreen() {
        LinearLayout search = row();
        content.addView(search);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Search movies and TV shows");
        query.setHintTextColor(MUTED);
        query.setTextColor(WHITE);
        query.setTextSize(18);
        query.setTypeface(REGULAR);
        query.setPadding(dp(16), 0, dp(16), 0);
        query.setBackground(shape(PANEL, 0xff808080));
        query.setOnFocusChangeListener((v, focused) -> query.setBackground(shape(PANEL, focused ? WHITE : 0xff808080)));
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        search.addView(query, new LinearLayout.LayoutParams(0, dp(54), 1));
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
        LinearLayout.LayoutParams searchAction = new LinearLayout.LayoutParams(dp(130), dp(54));
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
        gap(content, 4);
        LinearLayout grid = column();
        content.addView(grid);
        String path;
        String kind;
        if (name.equals("Trending")) { path = "/api/tmdb/trending/all/day?page="; kind = "movie"; }
        else if (name.equals("Movies")) { path = moviePath(); kind = "movie"; }
        else if (name.equals("TV Shows")) { path = tvPath(); kind = "tv"; }
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
        Button more = button("Load more", () -> loadCollectionPage(state));
        loadMoreButton = more;
        content.addView(more, new LinearLayout.LayoutParams(dp(180), dp(52)));
        more.setEnabled(false);
        more.setText("Loading...");
        loadCollectionPage(state);
    }

    private void loadCollectionPage(FullCollection state) {
        if (state.loading || state.complete || state.pathPrefix == null) return;
        state.loading = true;
        boolean fromButton = getCurrentFocus() == loadMoreButton;
        if (loadMoreButton != null) { loadMoreButton.setEnabled(false); loadMoreButton.setText("Loading..."); }
        int page = state.nextPage;
        int request = generation;
        api.object(state.pathPrefix + page, (json, error) -> {
            if (request != generation || fullCollection != state) return;
            state.loading = false;
            if (error != null || json == null) {
                if (state.tiles.isEmpty()) state.grid.addView(text("Catalog unavailable", 16, MUTED, false));
                if (loadMoreButton != null) { loadMoreButton.setEnabled(true); loadMoreButton.setText("Retry loading"); }
                return;
            }
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
            if (start < state.tiles.size() && (start == 0 || fromButton)) {
                focusTile(state.tiles.get(start));
            }
        });
    }

    private void appendCollectionItem(FullCollection state, Item item) {
        if (state.tiles.size() % 5 == 0) {
            LinearLayout line = row();
            line.setGravity(Gravity.TOP);
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

    private void showDetail(Item item) {
        selected = item;
        detailOpen = true;
        playerOpen = false;
        generation++;
        shell("Flipflix");
        // Artwork belongs to the full window, not the inset, scrolling preview card.
        ImageView hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(hero, 0, new FrameLayout.LayoutParams(-1, -1));
        api.image(hero, item.backdrop, false, 1280);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xeb000000, 0x80000000, 0x10000000}));
        root.addView(shade, 1, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00000000, Color.BLACK}));
        root.addView(fade, 2, new FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM));
        Button back = button("Back to " + section, () -> openSection(section));
        content.addView(back, new LinearLayout.LayoutParams(dp(200), dp(40)));
        gap(content, 12);
        FrameLayout preview = new FrameLayout(this);
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(304)));
        LinearLayout details = column();
        details.setPadding(dp(24), dp(20), dp(20), dp(20));
        int detailWidth = Math.min(560, (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 90);
        preview.addView(details, new FrameLayout.LayoutParams(dp(detailWidth), -2, Gravity.BOTTOM | Gravity.LEFT));
        TextView name = text(item.title, 34, WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        gap(details, 10);
        TextView meta = text(item.year + "  |  " + (item.kind.equals("tv") ? "TV show" : "Movie") + (item.rating.isEmpty() ? "" : "  |  " + item.rating), 14, MUTED, false);
        details.addView(meta);
        gap(details, 10);
        TextView synopsis = text(item.overview, 15, WHITE, false);
        synopsis.setMaxLines(3);
        synopsis.setEllipsize(TextUtils.TruncateAt.END);
        synopsis.setLineSpacing(dp(2), 1);
        details.addView(synopsis);
        gap(details, 16);
        LinearLayout sources = row();
        details.addView(sources);
        String[] names = sourceNames(item);
        for (int i = 0; i < names.length; i++) {
            final int source = i;
            Button choice = button(names[i], () -> showPlayer(item, season, episode, source), i == 0);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(140), dp(44));
            p.rightMargin = dp(12);
            sources.addView(choice, p);
            if (i == 0) choice.requestFocus();
        }
        int request = generation;
        api.object("/api/tmdb/" + item.kind + "/" + item.id, (json, error) -> {
            if (request != generation || json == null) return;
            item.overview = json.optString("overview", item.overview);
            synopsis.setText(item.overview);
            if (item.kind.equals("tv")) addSeasons(item, json.optJSONArray("seasons"));
        });
    }

    private void addSeasons(Item item, JSONArray seasons) {
        if (seasons == null) return;
        gap(content, 22);
        content.addView(text("Episodes", 23, WHITE, true));
        gap(content, 12);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
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
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(140), dp(42));
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
        dialog.setOnDismissListener(d -> { if (playing && playerOverlay != null) playerOverlay.resumeFromMenu(); });
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        panel.addView(text("Choose a source", 24, WHITE, true));
        gap(panel, 20);
        int active = playing ? currentSourceIndex : 0;
        Button initial = null;
        for (int i = 0; i < names.length; i++) {
            final int source = i;
            Button choice = button(names[i], () -> {
                dialog.dismiss();
                if (playing) loadSource(item, source, s, e);
                else showPlayer(item, s, e, source);
            }, i == active);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
            p.topMargin = dp(10);
            panel.addView(choice, p);
            if (i == active) initial = choice;
        }
        dialog.setView(panel, 0, 0, 0, 0);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(shape(PANEL, 0xff414141));
            int width = Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(80));
            dialog.getWindow().setLayout(width, -2);
        }
        if (initial != null) initial.requestFocus();
    }

    private String[] sourceNames(Item item) {
        return item.kind.equals("sport") ? new String[]{"Stream 1"} : new String[]{"Bolt", "Rift", "Fast"};
    }

    private String sourceUrl(Item item, int source, int s, int e) {
        if (item.kind.equals("sport")) {
            if (item.backdrop == null || item.backdrop.isEmpty()) return "";
            return "https://embed.st/embed/" + item.backdrop + "/1";
        }
        String id = String.valueOf(item.id);
        String suffix = item.kind.equals("tv") ? "/tv/" + id + "/" + s + "/" + e : "/movie/" + id;
        if (source == 0) return "https://vidbolt.xyz" + suffix + "?theme=E50914&autoPlay=true";
        if (source == 1) return "https://embed.vidrift.in/embed" + suffix;
        return "https://bingr.one/watch" + suffix;
    }

    private void showPlayer(Item item, int s, int e, int source) {
        selected = item;
        season = s;
        episode = e;
        currentSourceIndex = source;
        playerOpen = true;
        detailOpen = !item.kind.equals("sport");
        generation++;
        clearCustomVideoView();
        sourceRequest++;
        if (playerOverlay != null) { playerOverlay.deactivate(); playerOverlay = null; }
        releaseNativePlayer();
        if (player != null) { player.stopLoading(); player.destroy(); player = null; }
        root.removeAllViews();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Playback has one edge-to-edge surface. All actions belong to the
        // auto-hiding overlay; there is no separate windowed player screen.
        playerScreen = new FrameLayout(this);
        root.addView(playerScreen, new FrameLayout.LayoutParams(-1, -1));
        String[] names = sourceNames(item);
        player = new WebView(this);
        final WebView sessionPlayer = player;
        player.setBackgroundColor(Color.BLACK);
        player.setFocusable(true);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(false);
        player.getSettings().setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(player, true);
        embeddedPlayer = new EmbeddedPlayback(player);
        final SportsEmbedCompat sportsCompat = item.kind.equals("sport") ? new SportsEmbedCompat(player) : null;
        player.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg) { return false; }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (!playerOpen || player != sessionPlayer || customVideoView != null) { callback.onCustomViewHidden(); return; }
                customVideoView = view;
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
                if (view == player && embeddedPlayer != null) embeddedPlayer.pageFinished();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
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
        enterPlayerFullscreen();
    }

    private void enterPlayerFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (playerOverlay != null) playerOverlay.activate();
    }

    private void buildPlayerOverlay(Item item) {
        String title = item.title + (item.kind.equals("tv") ? "  |  S" + season + " E" + episode : "");
        playerOverlay = new PlaybackControls(this, () -> nativePlayer != null ? nativePlayer : embeddedPlayer,
            this::focusVideo, title,
            item.kind.equals("tv") ? () -> showPlayer(item, season, Math.max(1, episode - 1), currentSourceIndex) : null,
            item.kind.equals("tv") ? () -> showPlayer(item, season, episode + 1, currentSourceIndex) : null,
            () -> choosePlayerSource(item), () -> loadSource(item, currentSourceIndex, season, episode), this::closePlayer);
        playerOverlay.sourceName(sourceNames(item)[currentSourceIndex]);
        root.addView(playerOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void focusVideo() {
        if (nativePlayer != null) nativePlayer.view.requestFocus();
        else if (customVideoView != null) customVideoView.requestFocus();
        else if (player != null) player.requestFocus();
    }

    private void revealPlayerOverlay(boolean focus) {
        if (playerOpen && playerOverlay != null) playerOverlay.show(focus);
    }

    private void choosePlayerSource(Item item) {
        showSourceChooser(item, season, episode, true);
    }

    private void loadSource(Item item, int source, int s, int e) {
        clearCustomVideoView();
        currentSourceIndex = source;
        int request = ++sourceRequest;
        if (playerOverlay != null) playerOverlay.sourceName(sourceNames(item)[source]);
        releaseNativePlayer();
        if (embeddedPlayer != null) embeddedPlayer.reset();
        if (player != null) { player.stopLoading(); player.loadUrl("about:blank"); player.setVisibility(View.GONE); }
        if (playerStatus != null) {
            playerStatus.setText("Preparing " + sourceNames(item)[source] + "...");
            playerStatus.setVisibility(View.VISIBLE);
        }
        if (source == 1 && !item.kind.equals("sport")) {
            String embed = sourceUrl(item, source, s, e);
            api.riftStream(embed, (stream, captions, error) -> {
                if (request != sourceRequest || !playerOpen) return;
                if (error != null || stream == null) { loadEmbed(item, source, s, e); return; }
                playNativeRift(item, source, s, e, stream, embed, captions, request);
            });
        } else loadEmbed(item, source, s, e);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void playNativeRift(Item item, int source, int s, int e, String stream, String referer, JSONArray captions, int request) {
        NativePlayback video = new NativePlayback(this, stream, referer, captions);
        nativePlayer = video;
        playerScreen.addView(video.view, 0, new FrameLayout.LayoutParams(-1, -1));
        if (playerStatus != null) playerStatus.setVisibility(View.GONE);
        video.player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (request == sourceRequest && nativePlayer == video) {
                    releaseNativePlayer();
                    loadEmbed(item, source, s, e);
                }
            }
        });
        video.start();
    }

    private void releaseNativePlayer() {
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (selected != null && !selected.kind.equals("sport")) showDetail(selected);
        else openSection("Live Sports");
    }

    private void loadEmbed(Item item, int source, int s, int e) {
        if (player == null) return;
        if (playerStatus != null) playerStatus.setVisibility(View.GONE);
        player.setVisibility(View.VISIBLE);
        String url = sourceUrl(item, source, s, e);
        if (url.isEmpty()) return;
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
            "<body style='margin:0;background:#000;overflow:hidden'><iframe title='Video' src='" + url +
            "' allow='autoplay;fullscreen;picture-in-picture' allowfullscreen style='border:0;width:100vw;height:100vh'></iframe></body></html>";
        if (!item.kind.equals("sport") && embeddedPlayer != null && embeddedPlayer.documentStart) {
            player.loadDataWithBaseURL("https://pantyflix.com/", html, "text/html", "UTF-8", null);
        } else player.loadUrl(url);
        revealPlayerOverlay(false);
    }

    private boolean moveCatalogFocus(int keyCode) {
        View focused = getCurrentFocus();
        if (focused == null || focusRows.isEmpty()) return false;
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
            if (rowIndex > 0 && fullCollection != null) {
                List<View> previous = focusRows.get(rowIndex - 1);
                if (!previous.isEmpty()) return focusTile(previous.get(previous.size() - 1));
            }
            return true;
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
                if (topAction != null) return focusTile(topAction);
            } else if (loadMoreButton != null && loadMoreButton.getVisibility() == View.VISIBLE) {
                return focusTile(loadMoreButton);
            }
            return true;
        }
        return false;
    }

    private boolean focusTile(View target) {
        if (!target.requestFocus()) return false;
        target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), false);
        if (section.equals("Home")) {
            for (CatalogRow row : catalogRows) {
                if (row.headingView == target || row.tiles.contains(target)) {
                    View scroll = (View) row.cards.getParent();
                    content.requestRectangleOnScreen(new Rect(0, row.headingView.getTop(), content.getWidth(), scroll.getBottom()), true);
                    break;
                }
            }
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && directional) {
            if (!playerOpen && !detailOpen && moveCatalogFocus(code)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (playerOpen) closePlayer();
        else if (detailOpen) openSection(section);
        else if (!section.equals("Home")) openSection("Home");
        else super.onBackPressed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onBackPressed(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        playerOpen = false;
        sourceRequest++;
        if (playerOverlay != null) playerOverlay.deactivate();
        clearCustomVideoView();
        releaseNativePlayer();
        if (player != null) { player.stopLoading(); player.destroy(); }
        api.close();
        super.onDestroy();
    }
}
