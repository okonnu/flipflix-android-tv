package com.okonu.flipflix;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URL;

/** Only stable, numeric Flipflix releases with the established APK asset qualify. */
final class GithubRelease {
    static final String REPOSITORY = "okonnu/flipflix-android-tv";
    static final String API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    static final long MAX_APK = 150L * 1024 * 1024;
    final String version, url, digest;
    final long size;

    GithubRelease(String version, String url, String digest, long size) throws IOException {
        numbers(version);
        URL location = new URL(url);
        if (!"https".equals(location.getProtocol()) || !"github.com".equals(location.getHost())
            || location.getPort() != -1 || location.getUserInfo() != null || location.getQuery() != null
            || location.getRef() != null || !location.getPath().equals("/" + REPOSITORY + "/releases/download/v" + version + "/app-release.apk")
            || !digest.matches("sha256:[0-9a-fA-F]{64}") || size <= 0 || size > MAX_APK)
            throw new IOException("The GitHub release does not contain a valid Dagflix APK.");
        this.version = version; this.url = url; this.digest = digest; this.size = size;
    }

    static GithubRelease latest(JSONObject json, String installed) throws Exception {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
        String tag = json.optString("tag_name");
        if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) return null;
        String version = tag.substring(1);
        if (compare(version, installed) <= 0) return null;
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.optString("name").equals("app-release.apk") && asset.optString("state").equals("uploaded"))
                return new GithubRelease(version, asset.getString("browser_download_url"), asset.optString("digest"), asset.getLong("size"));
        }
        throw new IOException("The latest release APK is not ready yet. Try again later.");
    }

    JSONObject json() throws Exception {
        return new JSONObject().put("version", version).put("url", url).put("digest", digest).put("size", size);
    }
    static GithubRelease restore(String value) throws Exception {
        JSONObject json = new JSONObject(value);
        return new GithubRelease(json.getString("version"), json.getString("url"), json.getString("digest"), json.getLong("size"));
    }
    static int compare(String a, String b) throws IOException {
        long[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Long.compare(x[i], y[i]);
        return 0;
    }
    private static long[] numbers(String value) throws IOException {
        if (value == null || !value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Invalid version.");
        try {
            String[] parts = value.split("\\.");
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])};
        } catch (NumberFormatException e) { throw new IOException("Invalid version.", e); }
    }
}
