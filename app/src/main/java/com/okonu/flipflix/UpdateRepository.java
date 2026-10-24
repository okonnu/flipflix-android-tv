package com.okonu.flipflix;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** All network, checksum and package work runs off the UI thread. No GitHub token. */
class UpdateRepository implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    private volatile boolean closed;
    private volatile HttpURLConnection active;

    UpdateRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        directory = new File(context.getFilesDir(), "updates");
    }
    String installedVersion() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
    }
    GithubRelease latest() throws Exception {
        HttpURLConnection connection = open(GithubRelease.API);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                checkOpen();
                if (out.size() + count > 1024 * 1024) throw new IOException("Release response is too large.");
                out.write(buffer, 0, count);
            }
            return GithubRelease.latest(new JSONObject(out.toString("UTF-8")), installedVersion());
        } finally { connection.disconnect(); active = null; }
    }
    GithubRelease pending() throws Exception {
        String saved = prefs.getString("ready_release", "");
        if (saved.isEmpty()) return null;
        try {
            GithubRelease release = GithubRelease.restore(saved);
            if (GithubRelease.compare(release.version, installedVersion()) <= 0) { discard(); return null; }
            verify(release);
            return release;
        } catch (Exception e) { discard(); return null; }
    }
    File apk() { return new File(directory, "update.apk"); }
    void prepare(GithubRelease release) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update storage.");
        File partial = new File(directory, "download.part");
        HttpURLConnection connection = open(release.url);
        try (InputStream input = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[32768]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                checkOpen(); total += count;
                if (total > release.size) throw new IOException("Update size does not match the release.");
                out.write(buffer, 0, count);
            }
            if (total != release.size) throw new IOException("The update download was incomplete.");
            out.getFD().sync();
            validate(partial, release);
            if (!partial.renameTo(apk())) throw new IOException("Cannot save the downloaded update.");
            prefs.edit().putString("ready_release", release.json().toString()).commit();
        } finally { connection.disconnect(); active = null; if (partial.exists()) partial.delete(); }
    }
    void verify(GithubRelease release) throws Exception { validate(apk(), release); }
    private void validate(File file, GithubRelease release) throws Exception {
        if (file.length() != release.size) throw new IOException("The saved update is incomplete.");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) { checkOpen(); hash.update(buffer, 0, count); }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
        if (!release.digest.substring(7).equalsIgnoreCase(hex.toString())) throw new IOException("The update checksum did not match GitHub.");
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo incoming = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        validatePackage(installed, incoming, release.version, Build.VERSION.SDK_INT);
    }
    static void validatePackage(PackageInfo installed, PackageInfo incoming, String version, int sdk) throws IOException {
        if (incoming == null || !installed.packageName.equals(incoming.packageName)
            || !version.equals(incoming.versionName) || code(incoming) <= code(installed))
            throw new IOException("The APK is not a newer version of this app.");
        if (Build.VERSION.SDK_INT >= 24 && incoming.applicationInfo != null && incoming.applicationInfo.minSdkVersion > sdk)
            throw new IOException("This update needs a newer Android version.");
        Set<Signature> expected = signatures(installed), actual = signatures(incoming);
        if (expected.isEmpty() || !expected.equals(actual)) throw new IOException("The APK signing certificate does not match this app.");
    }
    private static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static Set<Signature> signatures(PackageInfo info) {
        Signature[] values = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        return values == null ? new HashSet<>() : new HashSet<>(Arrays.asList(values));
    }
    void discard() {
        prefs.edit().remove("ready_release").apply();
        if (apk().exists()) apk().delete();
    }
    private void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Update check cancelled.");
    }
    static boolean allowed(URL url) {
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null && url.getPort() == -1
            && (url.getHost().equals("api.github.com") || url.getHost().equals("github.com") || url.getHost().equals("release-assets.githubusercontent.com"));
    }
    HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            checkOpen();
            if (!allowed(url)) throw new IOException("Unexpected update download host.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(); active = connection;
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Flipflix-Android-TV-Updater");
            connection.setRequestProperty("Accept", url.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status == 200) return connection;
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IOException("Missing update redirect.");
                    url = new URL(url, next); connection.disconnect(); continue;
                }
                throw new IOException(status == 403 || status == 429 ? "GitHub is limiting requests. Please try later." : "GitHub update check failed (HTTP " + status + ").");
            } catch (IOException e) { connection.disconnect(); active = null; throw e; }
        }
        throw new IOException("Too many update redirects.");
    }
    @Override public void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
}
