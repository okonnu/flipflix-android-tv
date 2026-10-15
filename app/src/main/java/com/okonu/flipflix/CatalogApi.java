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

final class CatalogApi {
    interface Result { void done(JSONObject json, Exception error); }
    interface ArrayResult { void done(JSONArray json, Exception error); }
    interface StringResult { void done(String value, Exception error); }
    private final ExecutorService network = Executors.newFixedThreadPool(4);
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

    void riftStream(String embedUrl, StringResult result) {
        network.execute(() -> {
            String stream = null;
            Exception failure = null;
            try {
                String html = read(embedUrl);
                String marker = "var embedMeta = ";
                int start = html.indexOf(marker);
                int end = start < 0 ? -1 : html.indexOf(";</script>", start);
                if (end < 0) throw new IllegalStateException("Rift stream metadata unavailable");
                JSONObject meta = new JSONObject(html.substring(start + marker.length(), end));
                JSONArray options = meta.optJSONArray("orionStreams");
                if (options != null) {
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject option = options.optJSONObject(i);
                        if (option == null || !option.optBoolean("direct")) continue;
                        String path = option.optString("url", "");
                        if (!path.startsWith("/") && !path.startsWith("https://")) continue;
                        if (!path.contains(".m3u8") && !path.contains(".mp4")) continue;
                        stream = path.startsWith("/") ? "https://embed.vidrift.in" + path : path;
                        break;
                    }
                }
                if (stream == null) throw new IllegalStateException("No direct Rift stream");
            } catch (Exception e) { failure = e; }
            String resolved = stream;
            Exception error = failure;
            ui.post(() -> result.done(resolved, error));
        });
    }

    void image(ImageView view, String path, boolean sports) {
        image(view, path, sports, 500);
    }

    void image(ImageView view, String path, boolean sports, int width) {
        if (path == null || path.isEmpty()) return;
        String url = sports ? "https://streamed.pk" + path : "https://image.tmdb.org/t/p/w" + width + path;
        view.setTag(url);
        Bitmap cached = images.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        network.execute(() -> {
            try {
                byte[] bytes = readBytes(url);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    images.put(url, bitmap);
                    ui.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bitmap); });
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

    void close() { network.shutdownNow(); }
}
