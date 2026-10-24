package com.okonu.flipflix;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class CatalogApi {
    interface Result { void done(JSONObject json, Exception error); }
    interface ArrayResult { void done(JSONArray json, Exception error); }
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    // A slow catalog response must not hold up the selected poster or local sample artwork.
    private final ExecutorService artwork = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(64) {
        @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024 / 1024); }
    };

    void object(String path, Result result) {
        network.execute(() -> {
            JSONObject data = null;
            Exception failure = null;
            try { data = new JSONObject(read("https://pantyflix.com" + path)); }
            catch (Exception e) { failure = e; }
            JSONObject response = data;
            Exception error = failure;
            ui.post(() -> result.done(response, error));
        });
    }

    void array(String path, ArrayResult result) {
        network.execute(() -> {
            JSONArray data = null;
            Exception failure = null;
            try { data = new JSONArray(read("https://pantyflix.com" + path)); }
            catch (Exception e) { failure = e; }
            JSONArray response = data;
            Exception error = failure;
            ui.post(() -> result.done(response, error));
        });
    }

    void image(ImageView view, String path, boolean sports) {
        image(view, path, sports, 500);
    }

    void image(ImageView view, String path, boolean sports, int width) {
        if (path == null || path.isEmpty()) return;
        String url = sports ? "https://streamed.pk" + path : "https://image.tmdb.org/t/p/w" + width + path;
        imageUrl(view, url, null);
    }

    void imageUrl(ImageView view, String url, Runnable ready) {
        if (url == null || url.isEmpty()) return;
        view.setTag(url);
        Bitmap cached = images.get(url);
        if (cached != null) { view.setImageBitmap(cached); if (ready != null) ready.run(); return; }
        artwork.execute(() -> {
            try {
                Bitmap bitmap;
                if (url.startsWith("asset:///")) {
                    try (InputStream in = view.getContext().getAssets().open(url.substring(9))) { bitmap = BitmapFactory.decodeStream(in); }
                } else {
                    byte[] bytes = readBytes(url);
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
                if (bitmap != null) {
                    images.put(url, bitmap);
                    Bitmap result = bitmap;
                    ui.post(() -> { if (url.equals(view.getTag())) { view.setImageBitmap(result); if (ready != null) ready.run(); } });
                }
            } catch (Exception ignored) { }
        });
    }

    private static String read(String url) throws Exception { return new String(readBytes(url), java.nio.charset.StandardCharsets.UTF_8); }

    private static byte[] readBytes(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json,image/*,*/*");
        connection.setRequestProperty("User-Agent", "Flipflix-Research/0.1");
        try {
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    void close() { network.shutdownNow(); artwork.shutdownNow(); }
}
