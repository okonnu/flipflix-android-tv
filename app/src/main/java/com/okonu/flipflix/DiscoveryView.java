package com.okonu.flipflix;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Native, remote-first discovery surface. Selection is the only owner of hero/preview state. */
final class DiscoveryView extends FrameLayout {
    interface Actions {
        void open(DiscoveryItem item);
        void section(String name);
        void more(DiscoveryRow row);
    }
    static final class Position { int row, card; }
    private static final int BG = AppStyle.BG, MUTED = AppStyle.MUTED;
    final List<DiscoveryRow> rows;
    final List<RowView> rowViews = new ArrayList<>();
    final AmbientPreview preview;
    final ScrollView scroll;
    final TvScrollMotion vertical;
    private final Activity activity;
    private final CatalogApi api;
    private final Actions actions;
    private final FrameLayout artwork;
    private final ImageView poster, incoming, logo;
    private final LinearLayout hero, list;
    private final NavigationRail rail;
    private final TextView category, title, metadata, description, credit;
    private final Button watch, save;
    private final List<ImageButton> railButtons;
    private final Position position;
    private DiscoveryItem current;
    private int selectedRow, selectedCard, artworkGeneration;
    private int width, height, heroHeight, railWidth, gap, tileWidth;
    private boolean closed, selectedOnce, inRail, inHeading, inHero;
    private float touchX, touchY;

    DiscoveryView(Activity activity, CatalogApi api, List<DiscoveryRow> rows, Actions actions, Position position) {
        super(activity);
        this.activity = activity; this.api = api; this.rows = rows; this.actions = actions; this.position = position;
        selectedRow = position.row; selectedCard = position.card;
        setBackgroundColor(BG); setClipChildren(false);
        artwork = new FrameLayout(activity); addView(artwork);
        poster = image(); incoming = image(); artwork.addView(poster, new LayoutParams(-1, -1));
        artwork.addView(incoming, new LayoutParams(-1, -1));
        TextureView video = new TextureView(activity); artwork.addView(video, new LayoutParams(-1, -1));
        preview = new AmbientPreview(activity, video);
        View leftShade = new View(activity);
        leftShade.setBackground(AppStyle.leftShade());
        addView(leftShade, new LayoutParams(-1, -1));
        View bottomShade = new View(activity);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00070a11, 0x15070a11, BG, BG}));
        addView(bottomShade, new LayoutParams(-1, -1));
        hero = column(); addView(hero);
        category = text("DISCOVER SOMETHING GREAT", 10, MUTED, true); category.setLetterSpacing(.17f); hero.addView(category);
        logo = image(); logo.setVisibility(GONE); hero.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(26)));
        title = text("Flipflix", 34, Color.WHITE, true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameP = new LinearLayout.LayoutParams(-1, -2); nameP.topMargin = dp(8); hero.addView(title, nameP);
        metadata = text("Movies, series, and stories worth discovering", 12, 0xffd0d5df, false); metadata.setMaxLines(2);
        LinearLayout.LayoutParams metaP = new LinearLayout.LayoutParams(-1, -2); metaP.topMargin = dp(8); hero.addView(metadata, metaP);
        description = text("Browse with your remote. Pause on a title to discover more.", 13, 0xffd0d5df, false);
        description.setMaxLines(2); description.setEllipsize(TextUtils.TruncateAt.END); description.setLineSpacing(dp(2), 1);
        LinearLayout.LayoutParams descP = new LinearLayout.LayoutParams(-1, -2); descP.topMargin = dp(9); hero.addView(description, descP);
        LinearLayout buttons = new LinearLayout(activity); buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsP = new LinearLayout.LayoutParams(-2, dp(34)); buttonsP.topMargin = dp(12); hero.addView(buttons, buttonsP);
        watch = action("View details", () -> { if (current != null) actions.open(current); });
        buttons.addView(watch, new LinearLayout.LayoutParams(dp(124), -1));
        save = action("+ Library", this::toggleSaved);
        LinearLayout.LayoutParams saveP = new LinearLayout.LayoutParams(dp(95), -1); saveP.leftMargin = dp(8); buttons.addView(save, saveP);
        credit = text("", 9, MUTED, false); credit.setMaxLines(1); credit.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams creditP = new LinearLayout.LayoutParams(-1, -2); creditP.topMargin = dp(9); hero.addView(credit, creditP);
        scroll = new ScrollView(activity) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
            @Override public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) { return false; }
        };
        scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(OVER_SCROLL_NEVER); scroll.setClipToPadding(false);
        vertical = new TvScrollMotion(scroll, false);
        addView(scroll);
        list = column(); list.setClipChildren(false); list.setPadding(dp(8), dp(8), dp(20), dp(60));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        rail = new NavigationRail(activity, actions::section, () -> { inRail = true; inHero = inHeading = false; });
        railButtons = rail.buttons; addView(rail);
        for (int i = 0; i < rows.size(); i++) { RowView r = new RowView(i); rowViews.add(r); list.addView(r.container); refreshRow(i); }
        if (rows.isEmpty()) {
            title.setText("Your library"); description.setText("Save a title with + Library, then find it here.");
            metadata.setText("Your saved titles stay on this device."); watch.setVisibility(GONE); save.setVisibility(GONE);
            post(() -> railButtons.get(2).requestFocus());
        }
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(activity); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setIncludeFontPadding(false); t.setTypeface(bold ? AppStyle.MEDIUM : AppStyle.REGULAR); return t;
    }
    private ImageView image() { ImageView v = new ImageView(activity); v.setScaleType(ImageView.ScaleType.CENTER_CROP); return v; }
    private GradientDrawable shape(int fill, int stroke) {
        return AppStyle.shape(activity, fill, stroke);
    }
    private Button action(String label, Runnable run) {
        Button b = new Button(activity); b.setText(label); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(Color.WHITE);
        b.setPadding(dp(10), 0, dp(10), 0); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        b.setFocusableInTouchMode(true); AppStyle.button(b, false, false);
        b.setOnFocusChangeListener((v, focused) -> {
            if (focused) { inHero = true; inRail = inHeading = false; }
            AppStyle.button(b, false, focused);
        });
        b.setOnClickListener(v -> run.run()); return b;
    }
    static List<DiscoveryItem> saved(Activity activity) {
        List<DiscoveryItem> result = new ArrayList<>();
        try {
            JSONArray items = new JSONArray(activity.getSharedPreferences("discovery", 0).getString("library", "[]"));
            for (int i = 0; i < items.length(); i++) result.add(DiscoveryItem.from(items.getJSONObject(i)));
        } catch (org.json.JSONException ignored) { }
        return result;
    }
    private boolean isSaved(DiscoveryItem item) { for (DiscoveryItem i : saved(activity)) if (i.key.equals(item.key)) return true; return false; }
    private void toggleSaved() {
        if (current == null) return;
        List<DiscoveryItem> items = saved(activity); boolean found = false;
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).key.equals(current.key)) { items.remove(i); found = true; }
        if (!found) items.add(current);
        JSONArray json = new JSONArray(); for (DiscoveryItem i : items) json.put(i.json());
        activity.getSharedPreferences("discovery", 0).edit().putString("library", json.toString()).apply();
        save.setText(found ? "+ Library" : "Saved"); save.setContentDescription(found ? "Save to library" : "Remove from library");
        if (found && rows.size() == 1 && rows.get(0).kind.equals("saved")) actions.section("Library");
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH); width = w; height = h;
        railWidth = dp(w < dp(700) ? 52 : 62); gap = dp(13); heroHeight = Math.round(h * .55f);
        tileWidth = Math.max(dp(110), Math.round((w - railWidth - dp(46)) / (w < dp(700) ? 2.45f : 4.45f)));
        LayoutParams artP = new LayoutParams(Math.round(w * .83f), Math.round(h * .85f), Gravity.TOP | Gravity.RIGHT);
        artwork.setLayoutParams(artP);
        LayoutParams heroP = new LayoutParams(Math.round((w - railWidth) * .49f), -2); heroP.leftMargin = railWidth + dp(25); heroP.topMargin = Math.max(dp(24), Math.round(h * .075f)); hero.setLayoutParams(heroP);
        title.setTextSize(w < dp(700) ? 26 : 34); title.setMaxLines(h < dp(480) ? 1 : 2);
        metadata.setMaxLines(h < dp(480) ? 1 : 2); description.setMaxLines(h < dp(480) ? 1 : 2);
        LayoutParams scrollP = new LayoutParams(w - railWidth - dp(10), h - heroHeight); scrollP.leftMargin = railWidth + dp(10); scrollP.topMargin = heroHeight; scroll.setLayoutParams(scrollP);
        // Rows can draw their focus shadows outside each strip, but never over the shared hero.
        scroll.setClipBounds(new Rect(0, 0, scrollP.width, scrollP.height));
        rail.setLayoutParams(new LayoutParams(railWidth, -1));
        for (RowView row : rowViews) row.resize();
        post(() -> { if (!closed) restoreSelection(); });
    }
    void refreshRow(int index) {
        if (closed || index < 0 || index >= rowViews.size()) return;
        RowView r = rowViews.get(index); r.refresh();
        if (!selectedOnce && index == Math.min(position.row, rows.size() - 1) && !r.cards.isEmpty()) post(this::restoreSelection);
    }
    private void restoreSelection() {
        if (closed || selectedOnce || rows.isEmpty()) return;
        int r = Math.min(position.row, rows.size() - 1);
        if (!rows.get(r).items.isEmpty()) focusCard(r, Math.min(position.card, rows.get(r).items.size() - 1));
    }
    private void focusCard(int r, int c) {
        if (closed || r < 0 || r >= rowViews.size() || rowViews.get(r).cards.isEmpty()) return;
        c = Math.max(0, Math.min(c, rowViews.get(r).cards.size() - 1));
        rowViews.get(r).cards.get(c).requestFocus();
    }
    private void selected(int r, int c) {
        selectedOnce = true; selectedRow = r; selectedCard = c;
        inRail = inHero = inHeading = false;
        position.row = r; position.card = c; rows.get(r).lastIndex = c;
        DiscoveryItem item = rows.get(r).items.get(c);
        updateHero(item);
        RowView rv = rowViews.get(r); rv.loadVisible(c);
        int max = Math.max(0, list.getHeight() - scroll.getHeight());
        vertical.moveTo(Math.max(0, Math.min(rv.container.getTop() - dp(14), max)), true);
        if (c >= rows.get(r).items.size() - 4) actions.more(rows.get(r));
        if (r + 1 < rowViews.size()) rowViews.get(r + 1).loadVisible(rows.get(r + 1).lastIndex);
    }
    private void updateHero(DiscoveryItem item) {
        if (current == item) return;
        current = item; preview.select(item);
        category.setText(item.live ? "ON NOW" : item.category.toUpperCase(java.util.Locale.US));
        title.setText(item.title);
        String meta = item.metadata;
        if (item.live) meta = TextUtils.join("  |  ", nonEmpty(item.broadcastTime, item.ageRating, item.episodeInfo, item.metadata));
        metadata.setText(meta); description.setText(item.description);
        credit.setText(item.attribution); credit.setVisibility(item.attribution.isEmpty() ? GONE : VISIBLE);
        watch.setText(item.watchUrl.isEmpty() ? "View details" : "Watch trailer");
        if (item.live) watch.setText("Watch live");
        save.setText(isSaved(item) ? "Saved" : "+ Library");
        logo.setVisibility(item.logoUrl.isEmpty() ? GONE : VISIBLE);
        if (!item.logoUrl.isEmpty()) api.imageUrl(logo, item.logoUrl, null);
        int token = ++artworkGeneration;
        incoming.animate().cancel(); poster.animate().cancel(); incoming.setAlpha(0f);
        // Use this card's already-decoded thumbnail immediately while its full hero arrives.
        // Never leave the previous title's artwork behind a new selection.
        android.graphics.drawable.Drawable fallback = null;
        if (selectedRow < rowViews.size() && selectedCard < rowViews.get(selectedRow).cards.size())
            fallback = rowViews.get(selectedRow).cards.get(selectedCard).thumbnail.getDrawable();
        if (fallback != null && fallback.getConstantState() != null) fallback = fallback.getConstantState().newDrawable(getResources());
        poster.setImageDrawable(fallback);
        incoming.setImageDrawable(null); incoming.setTag(null); poster.setTag(null);
        if (!item.heroImage.isEmpty()) api.imageUrl(incoming, item.heroImage, () -> {
            if (closed || token != artworkGeneration) return;
            incoming.animate().alpha(1f).setDuration(preview.reducedMotion() ? 0 : 260).setInterpolator(new DecelerateInterpolator()).start();
        });
    }
    private List<String> nonEmpty(String... strings) { List<String> out = new ArrayList<>(); for (String s : strings) if (s != null && !s.isEmpty()) out.add(s); return out; }
    boolean handleKey(KeyEvent event) {
        if (closed || !isAttachedToWindow() || !hasFocus()) return false;
        int key = event.getKeyCode();
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        if (key == KeyEvent.KEYCODE_MENU && !rows.isEmpty()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) rowViews.get(selectedRow).heading.requestFocus();
            return true;
        }
        if (!arrow) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) { preview.restartDelay(); return true; }
        preview.stop(); // A held arrow at a boundary still counts as active browsing.
        if (inRail) {
            int index = railButtons.indexOf(activity.getCurrentFocus());
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) focusCard(selectedRow, selectedCard);
            else if (key == KeyEvent.KEYCODE_DPAD_UP) railButtons.get(Math.max(0, index - 1)).requestFocus();
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) railButtons.get(Math.min(3, index + 1)).requestFocus();
        } else if (inHero) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) focusCard(selectedRow, selectedCard);
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) save.requestFocus();
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (save.hasFocus()) watch.requestFocus(); else railButtons.get(2).requestFocus(); }
        } else if (inHeading) {
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) focusCard(selectedRow, rows.get(selectedRow).lastIndex);
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) railButtons.get(2).requestFocus();
            else if (key == KeyEvent.KEYCODE_DPAD_UP && selectedRow == 0) watch.requestFocus();
            else { int r = Math.max(0, Math.min(rows.size() - 1, selectedRow + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1))); rowViews.get(r).heading.requestFocus(); }
        } else if (!rows.isEmpty()) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (selectedCard == 0) railButtons.get(2).requestFocus(); else focusCard(selectedRow, selectedCard - 1); }
            else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) focusCard(selectedRow, selectedCard + 1);
            else if (key == KeyEvent.KEYCODE_DPAD_UP && selectedRow == 0) watch.requestFocus();
            else {
                int direction = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                for (int r = selectedRow + direction; r >= 0 && r < rows.size(); r += direction) {
                    if (!rows.get(r).items.isEmpty()) { focusCard(r, rows.get(r).lastIndex); break; }
                }
            }
        }
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { touchX = e.getX(); touchY = e.getY(); vertical.cancel(); }
        else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getX() - touchX) + Math.abs(e.getY() - touchY) > dp(8)) preview.stop();
        else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) preview.restartDelay();
        return super.dispatchTouchEvent(e);
    }
    void setActive(boolean active) { if (!closed) preview.setActive(active); }
    void close() { closed = true; artworkGeneration++; preview.close(); vertical.cancel(); incoming.setTag(null); for (RowView row : rowViews) row.carousel.motion.cancel(); }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }

    final class RowView {
        final int index;
        final LinearLayout container, strip;
        final TextView heading, status;
        final TvHorizontalScrollView carousel;
        final List<Card> cards = new ArrayList<>();
        RowView(int index) {
            this.index = index; DiscoveryRow row = rows.get(index);
            container = column(); container.setClipChildren(false);
            heading = text(row.title, 17, Color.WHITE, true); heading.setPadding(dp(6), dp(3), dp(10), dp(6));
            heading.setFocusableInTouchMode(true); heading.setClickable(true); heading.setContentDescription(row.title + ", browse all");
            heading.setOnClickListener(v -> actions.section(row.section));
            heading.setOnFocusChangeListener((v, focused) -> {
                heading.setBackground(shape(focused ? 0x30485261 : Color.TRANSPARENT, focused ? Color.WHITE : Color.TRANSPARENT));
                if (focused) { selectedRow = index; inHeading = true; inRail = inHero = false; vertical.moveTo(Math.max(0, container.getTop() - dp(14)), true); }
            });
            LinearLayout headingRow = new LinearLayout(activity); headingRow.setGravity(Gravity.CENTER_VERTICAL);
            container.addView(headingRow, new LinearLayout.LayoutParams(-1, dp(31)));
            headingRow.addView(heading, new LinearLayout.LayoutParams(-2, -1));
            if (row.section.equals("Movies") || row.section.equals("TV Shows")) {
                TextView hint = text("Browse & filter", 10, MUTED, false); hint.setPadding(dp(6), 0, 0, 0);
                hint.setOnClickListener(v -> actions.section(row.section)); hint.setFocusable(false);
                headingRow.addView(hint);
                heading.setContentDescription(row.title + ", browse all and filter");
            }
            carousel = new TvHorizontalScrollView(activity) {
                @Override protected boolean revealFocus(View focused, boolean animate) {
                    if (!(focused instanceof Card)) return false;
                    int destination = Math.max(0, focused.getLeft() - dp(8));
                    return moveTo(destination, animate);
                }
            };
            carousel.setClipChildren(false); carousel.setClipToPadding(false);
            strip = new LinearLayout(activity); strip.setGravity(Gravity.TOP); strip.setPadding(dp(8), dp(6), dp(8), dp(6));
            strip.setClipChildren(false); strip.setClipToPadding(false); carousel.addView(strip);
            container.addView(carousel, new LinearLayout.LayoutParams(-1, -2));
            status = text("Loading titles...", 13, MUTED, false); status.setPadding(dp(8), dp(16), 0, dp(18)); container.addView(status);
            View space = new View(activity); container.addView(space, new LinearLayout.LayoutParams(1, dp(14)));
        }
        void refresh() {
            DiscoveryRow row = rows.get(index);
            for (int i = cards.size(); i < row.items.size(); i++) { Card c = new Card(index, i); cards.add(c); strip.addView(c); }
            status.setVisibility(cards.isEmpty() ? VISIBLE : GONE);
            status.setText(row.loading ? "Loading titles..." : row.failed ? "Unavailable. Open this category to retry." : row.complete ? "No titles available" : "Loading titles...");
            resize();
            if (index <= selectedRow + 1) loadVisible(row.lastIndex);
        }
        void resize() {
            int standard = tileWidth == 0 ? dp(180) : tileWidth;
            boolean featured = rows.get(index).featured;
            int featuredH = Math.min(dp(155), Math.round((height == 0 ? dp(540) : height) * .29f));
            for (int i = 0; i < cards.size(); i++) {
                int w = featured ? (i == 0 ? featuredH * 16 / 9 : featuredH * 2 / 3) : standard;
                int h = featured ? featuredH : w * 9 / 16;
                cards.get(i).resize(w, h);
            }
            // Leave enough trailing room for the last selected tile to settle at the left anchor.
            strip.setPadding(dp(8), dp(6), Math.max(dp(8), width - railWidth - standard - dp(38)), dp(6));
        }
        void loadVisible(int from) { for (int i = Math.max(0, from - 1); i < Math.min(cards.size(), from + 6); i++) cards.get(i).load(); }
    }
    final class Card extends FrameLayout {
        final int row, index;
        final DiscoveryItem item;
        final FrameLayout picture;
        final ImageView thumbnail;
        final TextView label;
        boolean loaded;
        Card(int row, int index) {
            super(activity); this.row = row; this.index = index; item = rows.get(row).items.get(index);
            setFocusableInTouchMode(true); setClickable(true); setClipChildren(false); setContentDescription(item.title + ". " + item.metadata);
            picture = new FrameLayout(activity); picture.setBackground(shape(0xff151c29, Color.TRANSPARENT)); picture.setClipToOutline(true); addView(picture);
            thumbnail = image(); picture.addView(thumbnail, new LayoutParams(-1, -1));
            if (item.live) {
                TextView badge = text("ON NOW", 8, Color.WHITE, true); badge.setPadding(dp(5), dp(3), dp(5), dp(3)); badge.setBackground(shape(0xc0000000, Color.TRANSPARENT));
                LayoutParams p = new LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT); p.topMargin = dp(7); p.rightMargin = dp(7); picture.addView(badge, p);
                if (item.progress >= 0) {
                    View progress = new View(activity) { final android.graphics.Paint paint = new android.graphics.Paint();
                        @Override protected void onDraw(android.graphics.Canvas canvas) { paint.setColor(0x70ffffff); canvas.drawRect(0, 0, getWidth(), getHeight(), paint); paint.setColor(0xffe43e49); canvas.drawRect(0, 0, getWidth() * Math.max(0, Math.min(1, item.progress)), getHeight(), paint); } };
                    picture.addView(progress, new LayoutParams(-1, dp(3), Gravity.BOTTOM));
                }
            }
            label = text(item.title, 12, 0xffdce0e7, true); label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END); addView(label);
            setOnFocusChangeListener((v, focused) -> {
                picture.setForeground(shape(Color.TRANSPARENT, focused ? Color.WHITE : Color.TRANSPARENT));
                animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f).setDuration(preview.reducedMotion() ? 0 : 220).start();
                setElevation(focused ? dp(5) : 0); if (focused) selected(row, index);
            });
            setOnHoverListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) requestFocus(); return false; });
            setOnClickListener(v -> { requestFocus(); actions.open(item); });
        }
        void resize(int w, int h) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h + dp(27)); p.rightMargin = gap; setLayoutParams(p);
            picture.setLayoutParams(new LayoutParams(-1, h));
            LayoutParams l = new LayoutParams(-1, dp(22)); l.topMargin = h + dp(5); label.setLayoutParams(l);
        }
        void load() { if (!loaded) { loaded = true; api.imageUrl(thumbnail, item.thumbnail, null); } }
    }
}
