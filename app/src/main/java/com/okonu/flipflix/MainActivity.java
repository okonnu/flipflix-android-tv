package com.okonu.flipflix;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 11, 16);
    private static final int PANEL = Color.rgb(20, 26, 36);
    private static final int WHITE = Color.rgb(242, 246, 252);
    private static final int MUTED = Color.rgb(158, 172, 190);
    private static final int ACCENT = Color.rgb(24, 184, 237);
    private final CatalogApi api = new CatalogApi();
    private FrameLayout root;
    private LinearLayout content;
    private WebView player;
    private Item selected;
    private String section = "Home";
    private boolean detailOpen;
    private boolean playerOpen;
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
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), border);
        return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void gap(LinearLayout l, int height) { View v = new View(this); l.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(WHITE);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setBackground(shape(PANEL, Color.rgb(42, 53, 67)));
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, focused) -> {
            b.setBackground(shape(focused ? Color.rgb(28, 55, 73) : PANEL, focused ? ACCENT : Color.rgb(42, 53, 67)));
            b.setScaleX(focused ? 1.04f : 1f);
            b.setScaleY(focused ? 1.04f : 1f);
        });
        return b;
    }

    private void shell(String title) {
        if (player != null) { player.stopLoading(); player.destroy(); player = null; }
        root.removeAllViews();
        LinearLayout entire = row();
        entire.setGravity(Gravity.TOP);
        root.addView(entire, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout rail = column();
        rail.setPadding(dp(12), dp(20), dp(12), dp(20));
        rail.setBackgroundColor(Color.rgb(12, 16, 22));
        entire.addView(rail, new LinearLayout.LayoutParams(dp(126), -1));
        TextView brand = text("F", 36, ACCENT, true);
        brand.setGravity(Gravity.CENTER);
        rail.addView(brand, new LinearLayout.LayoutParams(-1, dp(62)));
        for (String name : new String[]{"Home", "Search", "Movies", "TV Shows", "Anime", "Sports", "My Space"}) {
            Button nav = button(name, () -> openSection(name));
            nav.setTextSize(13);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
            p.bottomMargin = dp(8);
            rail.addView(nav, p);
        }
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        entire.addView(scroller, new LinearLayout.LayoutParams(0, -1, 1));
        content = column();
        content.setPadding(dp(38), dp(25), dp(38), dp(38));
        scroller.addView(content);
        content.addView(text(title, 30, WHITE, true));
        gap(content, 16);
    }

    private void openSection(String name) {
        section = name;
        detailOpen = false;
        playerOpen = false;
        generation++;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        shell(name.equals("Home") ? "Flipflix" : name);
        if (name.equals("Home")) {
            content.addView(text("A native TV catalog experiment", 16, MUTED, false));
            gap(content, 20);
            loadRow("Trending", "/api/tmdb/trending/all/day?page=1", "movie");
            loadRow("Popular movies", moviePath(), "movie");
            loadRow("Popular TV shows", tvPath(), "tv");
        } else if (name.equals("Search")) searchScreen();
        else if (name.equals("Movies")) gridScreen(moviePath(), "movie");
        else if (name.equals("TV Shows")) gridScreen(tvPath(), "tv");
        else if (name.equals("Anime")) gridScreen("/api/tmdb/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&page=1&include_adult=false", "tv");
        else if (name.equals("Sports")) sportsScreen();
        else {
            content.addView(text("My Space requires a Pantyflix account. Account sign-in is not part of this research build.", 18, MUTED, false));
            gap(content, 20);
            content.addView(text("Local watch history and favorites can be added without website login.", 16, MUTED, false));
        }
    }

    private String moviePath() { return "/api/tmdb/discover/movie?sort_by=popularity.desc&page=1&include_adult=false&vote_count.gte=80&include_video=false"; }
    private String tvPath() { return "/api/tmdb/discover/tv?sort_by=popularity.desc&page=1&include_adult=false&vote_count.gte=80&include_video=false"; }

    private void loadRow(String heading, String path, String kind) {
        TextView title = text(heading, 22, WHITE, true);
        content.addView(title);
        gap(content, 8);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = row();
        scroll.addView(cards);
        content.addView(scroll);
        gap(content, 22);
        int request = generation;
        api.object(path, (json, error) -> {
            if (request != generation) return;
            if (error != null || json == null) { cards.addView(text("Catalog unavailable", 16, MUTED, false)); return; }
            JSONArray entries = json.optJSONArray("results");
            if (entries == null) return;
            for (int i = 0; i < Math.min(entries.length(), 18); i++) {
                JSONObject o = entries.optJSONObject(i);
                if (o != null) cards.addView(card(Item.from(o, kind), false));
            }
        });
    }

    private void gridScreen(String path, String kind) {
        content.addView(text("Use the remote to select a title. Back returns to this list.", 15, MUTED, false));
        gap(content, 18);
        LinearLayout grid = column();
        content.addView(grid);
        int request = generation;
        api.object(path, (json, error) -> {
            if (request != generation) return;
            if (error != null || json == null) { grid.addView(text("Could not load catalog. Check the connection.", 18, MUTED, false)); return; }
            fillGrid(grid, json.optJSONArray("results"), kind);
        });
    }

    private void fillGrid(LinearLayout target, JSONArray entries, String kind) {
        target.removeAllViews();
        if (entries == null || entries.length() == 0) { target.addView(text("No results", 17, MUTED, false)); return; }
        LinearLayout line = null;
        for (int i = 0; i < Math.min(entries.length(), 60); i++) {
            if (i % 5 == 0) { line = row(); line.setGravity(Gravity.TOP); target.addView(line); }
            JSONObject o = entries.optJSONObject(i);
            if (o != null) line.addView(card(Item.from(o, kind), false));
        }
    }

    private View card(Item item, boolean sports) {
        LinearLayout box = column();
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(shape(PANEL, Color.TRANSPARENT));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Color.rgb(32, 39, 49));
        box.addView(image, new LinearLayout.LayoutParams(-1, dp(180)));
        api.image(image, item.poster, sports);
        TextView label = text(item.title, 14, WHITE, true);
        label.setMaxLines(2);
        label.setPadding(dp(8), dp(5), dp(8), 0);
        box.addView(label, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView info = text(item.year + (item.rating.isEmpty() ? "" : "  |  " + item.rating), 12, MUTED, false);
        info.setPadding(dp(8), 0, dp(8), dp(5));
        box.addView(info, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(148), dp(250));
        p.rightMargin = dp(15);
        p.bottomMargin = dp(18);
        box.setLayoutParams(p);
        box.setOnClickListener(v -> { if (sports) showSportsPlayer(item); else showDetail(item); });
        box.setOnFocusChangeListener((v, focus) -> {
            box.setBackground(shape(focus ? Color.rgb(25, 42, 54) : PANEL, focus ? ACCENT : Color.TRANSPARENT));
            box.setScaleX(focus ? 1.045f : 1f);
            box.setScaleY(focus ? 1.045f : 1f);
            if (focus) box.bringToFront();
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
        search.addView(go, new LinearLayout.LayoutParams(dp(130), dp(54)));
        query.setOnEditorActionListener((v, actionId, event) -> { submit.run(); return true; });
        gap(content, 22);
        content.addView(results);
        query.requestFocus();
    }

    private void sportsScreen() {
        content.addView(text("Live listings. Streams use a third-party embedded player.", 16, MUTED, false));
        gap(content, 18);
        LinearLayout grid = column();
        content.addView(grid);
        int request = generation;
        api.array("/api/streamed/matches/live", (json, error) -> {
            if (request != generation) return;
            if (error != null || json == null) { grid.addView(text("Sports unavailable", 18, MUTED, false)); return; }
            LinearLayout line = null;
            for (int i = 0; i < Math.min(json.length(), 35); i++) {
                if (i % 5 == 0) { line = row(); line.setGravity(Gravity.TOP); grid.addView(line); }
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
                line.addView(card(item, true));
            }
        });
    }

    private void showDetail(Item item) {
        selected = item;
        detailOpen = true;
        playerOpen = false;
        generation++;
        shell(item.title);
        Button back = button("Back to " + section, () -> openSection(section));
        content.addView(back, new LinearLayout.LayoutParams(dp(200), dp(52)));
        gap(content, 15);
        ImageView hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        content.addView(hero, new LinearLayout.LayoutParams(-1, dp(220)));
        api.image(hero, item.backdrop, false);
        gap(content, 18);
        TextView meta = text(item.year + "  |  " + (item.kind.equals("tv") ? "TV show" : "Movie") + (item.rating.isEmpty() ? "" : "  |  " + item.rating), 17, ACCENT, true);
        content.addView(meta);
        gap(content, 10);
        TextView synopsis = text(item.overview, 16, WHITE, false);
        content.addView(synopsis);
        gap(content, 18);
        Button watch = button("Watch - choose source", () -> showPlayer(item, season, episode));
        content.addView(watch, new LinearLayout.LayoutParams(dp(260), dp(55)));
        watch.requestFocus();
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
        gap(content, 25);
        content.addView(text("Seasons and episodes", 23, WHITE, true));
        gap(content, 10);
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
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(140), dp(52));
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
                Button b = button(ep + ". " + e.optString("name", "Episode") + "    " + e.optString("air_date", ""), () -> showPlayer(item, number, ep));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
                p.bottomMargin = dp(8);
                target.addView(b, p);
            }
        });
    }

    private void showSportsPlayer(Item item) {
        selected = item;
        showPlayer(item, 1, 1);
    }

    private String[] sourceNames(Item item) {
        return item.kind.equals("sport") ? new String[]{"Stream 1"} : new String[]{"Bolt", "Fast", "Rift"};
    }

    private String sourceUrl(Item item, int source, int s, int e) {
        if (item.kind.equals("sport")) {
            if (item.backdrop == null || item.backdrop.isEmpty()) return "";
            return "https://embed.st/embed/" + item.backdrop + "/1";
        }
        String id = String.valueOf(item.id);
        String suffix = item.kind.equals("tv") ? "/tv/" + id + "/" + s + "/" + e : "/movie/" + id;
        if (source == 0) return "https://vidbolt.xyz" + suffix + "?theme=00A8E1&autoPlay=true";
        if (source == 1) return "https://bingr.one/watch" + suffix;
        return "https://embed.vidrift.in/embed" + suffix;
    }

    private void showPlayer(Item item, int s, int e) {
        selected = item;
        season = s;
        episode = e;
        playerOpen = true;
        detailOpen = !item.kind.equals("sport");
        generation++;
        if (player != null) { player.stopLoading(); player.destroy(); player = null; }
        root.removeAllViews();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout screen = column();
        screen.setPadding(dp(18), dp(12), dp(18), dp(10));
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = row();
        screen.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
        Button back = button("Back", () -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(100), dp(52)));
        TextView title = text("  " + item.title + (item.kind.equals("tv") ? "  S" + s + " E" + e : ""), 20, WHITE, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        Spinner sources = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sourceNames(item));
        sources.setAdapter(adapter);
        sources.setBackground(shape(PANEL, ACCENT));
        bar.addView(sources, new LinearLayout.LayoutParams(dp(150), dp(50)));
        player = new WebView(this);
        player.setBackgroundColor(Color.BLACK);
        player.setFocusable(true);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(false);
        player.getSettings().setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(player, true);
        player.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg) { return false; }
        });
        player.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String host = request.getUrl().getHost();
                return host == null || !(host.equals("pantyflix.com") || host.equals("vidbolt.xyz") || host.equals("bingr.one") || host.equals("embed.vidrift.in") || host.equals("embed.st"));
            }
        });
        screen.addView(player, new LinearLayout.LayoutParams(-1, 0, 1));
        sources.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { loadEmbed(item, position, s, e); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        if (item.kind.equals("tv")) {
            LinearLayout controls = row();
            screen.addView(controls, new LinearLayout.LayoutParams(-1, dp(56)));
            controls.addView(button("Previous episode", () -> showPlayer(item, s, Math.max(1, e - 1))), new LinearLayout.LayoutParams(dp(210), dp(50)));
            controls.addView(button("Next episode", () -> showPlayer(item, s, e + 1)), new LinearLayout.LayoutParams(dp(190), dp(50)));
        }
        back.requestFocus();
    }

    private void loadEmbed(Item item, int source, int s, int e) {
        if (player == null) return;
        String url = sourceUrl(item, source, s, e);
        if (url.isEmpty()) return;
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
            "<body style='margin:0;background:#000;overflow:hidden'><iframe title='Video' src='" + url +
            "' allow='autoplay;fullscreen;picture-in-picture' allowfullscreen style='border:0;width:100vw;height:100vh'></iframe></body></html>";
        player.loadDataWithBaseURL("https://pantyflix.com/", html, "text/html", "UTF-8", null);
    }

    @Override public void onBackPressed() {
        if (playerOpen) {
            playerOpen = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (selected != null && !selected.kind.equals("sport")) showDetail(selected); else openSection("Sports");
        } else if (detailOpen) openSection(section);
        else if (!section.equals("Home")) openSection("Home");
        else super.onBackPressed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onBackPressed(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        if (player != null) { player.stopLoading(); player.destroy(); }
        api.close();
        super.onDestroy();
    }
}
