package com.okonu.flipflix;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded, event-driven YouTube preview, owned only by the details page. */
final class TrailerPreview {
    static final String ORIGIN = "https://com.okonu.flipflix";
    private static final long FADE_MS = 400;
    private final Activity activity;
    private final FrameLayout root;
    private final View[] overlays;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> candidates = new ArrayList<>();
    private final Runnable timeout = this::restore;
    private Runnable begin;
    private WebView web;
    private TextView loading;
    private View previousFocus;
    private boolean closed;
    private boolean showing;
    private int wakeKey = KeyEvent.KEYCODE_UNKNOWN;
    private int candidateIndex;
    private String lastEvent = "loading";

    TrailerPreview(Activity activity, FrameLayout root, View... overlays) {
        this.activity = activity;
        this.root = root;
        this.overlays = overlays;
    }

    void start(CatalogApi api, String kind, int id) {
        if (id <= 0 || !(kind.equals("movie") || kind.equals("tv"))
            || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            || Settings.Global.getFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
            close();
            return;
        }
        showLoading();
        // No preview downloads on catalog rows or while the remote is moving.
        begin = () -> api.object("/api/tmdb/" + kind + "/" + id + "/videos?language=en-US", (json, error) -> {
            if (closed) return;
            candidates.addAll(selectVideos(json));
            if (candidates.isEmpty()) restore();
            else load(candidates.get(0));
        });
        ui.postDelayed(begin, 1500);
        ui.postDelayed(timeout, 25000);
    }

    static List<String> selectVideos(JSONObject response) {
        List<String> ids = new ArrayList<>();
        JSONArray results = response == null ? null : response.optJSONArray("results");
        if (results == null) return ids;
        // Prefer official English trailers, then official teasers. Never title-search
        // YouTube or guess from unrelated fan/reaction videos.
        for (String type : new String[]{"Trailer", "Teaser"}) {
            for (int i = 0; i < results.length() && ids.size() < 3; i++) {
                JSONObject video = results.optJSONObject(i);
                if (video == null || !video.optBoolean("official") || !"YouTube".equals(video.optString("site"))
                    || !type.equals(video.optString("type")) || !"en".equals(video.optString("iso_639_1"))) continue;
                String key = video.optString("key");
                if (key.matches("[A-Za-z0-9_-]{11}") && !ids.contains(key)) ids.add(key);
            }
        }
        return ids;
    }

    private void load(String id) {
        if (closed) return;
        showLoading();
        destroyWeb();
        try {
            WebView view = new WebView(activity);
            web = view;
            view.setBackgroundColor(Color.BLACK);
            view.setAlpha(0f);
            view.setFocusable(false);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setMediaPlaybackRequiresUserGesture(false);
            view.getSettings().setAllowFileAccess(false);
            view.getSettings().setAllowContentAccess(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
            // Only the app-owned top-level document can report player events.
            // YouTube subframes cannot invoke an Android JavaScript interface.
            WebViewCompat.addWebMessageListener(view, "FlipflixTrailer", Collections.singleton(ORIGIN),
                (source, message, origin, mainFrame, reply) -> {
                    if (source != web || closed || !mainFrame || !ORIGIN.equals(origin.toString())) return;
                    onMessage(message.getData());
                });
            view.setWebViewClient(new WebViewClient() {
                @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                    if (v == web && request.isForMainFrame()) close();
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    if (!request.isForMainFrame()) return false;
                    openLink(request.getUrl(), request.hasGesture());
                    return true;
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    openLink(Uri.parse(url), true);
                    return true;
                }
            });
            root.addView(view, 1, new FrameLayout.LayoutParams(-1, -1));
            String html;
            try (InputStream in = activity.getAssets().open("trailer-preview.html");
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                html = out.toString("UTF-8");
            }
            view.loadDataWithBaseURL(ORIGIN + "/", html.replace("__VIDEO_ID__", id), "text/html", "UTF-8", null);
            ui.removeCallbacks(timeout);
            ui.postDelayed(timeout, 18000);
        } catch (Exception unavailable) { close(); }
    }

    private void openLink(Uri url, boolean userGesture) {
        if (!userGesture || !("https".equals(url.getScheme()) || "http".equals(url.getScheme()))) return;
        close();
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, url)); }
        catch (android.content.ActivityNotFoundException ignored) { }
    }

    void onMessage(String message) {
        if (closed || message == null || message.length() > 512) return;
        try {
            JSONObject event = new JSONObject(message);
            lastEvent = message;
            String type = event.optString("event");
            if (type.equals("state")) {
                int state = event.optInt("value", -1);
                if (state == 1) {
                    ui.removeCallbacks(timeout);
                    reveal();
                } else if (state == 0 || (state == 2 && showing)) restore();
                else if (state == 3) {
                    // A buffering event gets one bounded watchdog, never polling.
                    ui.removeCallbacks(timeout);
                    ui.postDelayed(timeout, 18000);
                }
            } else if (type.equals("error")) {
                int code = event.optInt("value");
                if (!showing && (code == 100 || code == 101 || code == 150) && ++candidateIndex < candidates.size())
                    load(candidates.get(candidateIndex));
                else restore();
            } else if (type.equals("blocked") || type.equals("reduced-motion")) restore();
        } catch (org.json.JSONException ignored) { }
    }

    private void reveal() {
        if (showing || web == null || !activity.hasWindowFocus()) return;
        showing = true;
        previousFocus = activity.getCurrentFocus();
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        web.setKeepScreenOn(true);
        web.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator()).start();
        hideLoading(true);
        for (View overlay : overlays) {
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            overlay.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                if (!closed && showing) overlay.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == wakeKey) {
            if (event.getAction() == KeyEvent.ACTION_UP) wakeKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        boolean navigation = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_DPAD_CENTER
            || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
            || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_MENU
            || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE
            || key == KeyEvent.KEYCODE_MEDIA_STOP || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND;
        if (closed || !navigation || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        boolean consume = showing;
        if (consume) wakeKey = key;
        restore();
        return consume;
    }

    void close() {
        finish(false);
    }

    private void restore() {
        if (!closed) finish(true);
    }

    private void finish(boolean animate) {
        // Navigation/lifecycle disposal also finishes any in-progress return fade.
        // Only in-page returns crossfade; no detached WebView survives a new page.
        boolean crossfade = animate && showing;
        closed = true;
        showing = false;
        if (begin != null) ui.removeCallbacks(begin);
        ui.removeCallbacks(timeout);
        if (crossfade && web != null) {
            WebView fading = web;
            fading.animate().cancel();
            fading.setKeepScreenOn(false);
            fading.evaluateJavascript("window.pauseTrailer && window.pauseTrailer()", null);
            fading.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> { if (web == fading) destroyWeb(); }).start();
        } else destroyWeb();
        hideLoading(animate);
        for (View overlay : overlays) {
            overlay.animate().cancel();
            overlay.setVisibility(View.VISIBLE);
            if (crossfade) overlay.animate().alpha(1f).setDuration(FADE_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
            else overlay.setAlpha(1f);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        if (previousFocus != null && previousFocus.isShown()) previousFocus.requestFocus();
    }

    private void destroyWeb() {
        if (web == null) return;
        WebView old = web;
        web = null;
        old.animate().cancel();
        old.setKeepScreenOn(false);
        old.stopLoading();
        old.onPause();
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.destroy();
    }

    private void showLoading() {
        if (loading != null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        loading = new TextView(activity);
        loading.setTag("preview-loading");
        loading.setText("Preview is loading...");
        loading.setTextSize(13);
        loading.setTextColor(0xccffffff);
        loading.setShadowLayer(3 * density, 0, density, Color.BLACK);
        loading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        loading.setAlpha(0f);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        p.rightMargin = (int) (36 * density);
        p.bottomMargin = (int) (24 * density);
        root.addView(loading, p);
        loading.animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideLoading(boolean animate) {
        if (loading == null) return;
        TextView fading = loading;
        fading.animate().cancel();
        if (animate) fading.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            root.removeView(fading);
            if (loading == fading) loading = null;
        }).start();
        else { root.removeView(fading); loading = null; }
    }
}
