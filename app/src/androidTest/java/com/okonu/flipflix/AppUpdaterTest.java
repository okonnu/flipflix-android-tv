package com.okonu.flipflix;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AppUpdaterTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private AppUpdater updater;
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Object field(Object target, String name) {
        try { java.lang.reflect.Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean good = new AtomicBoolean(); main(() -> { try { good.set(condition.call()); } catch (Exception e) { throw new AssertionError(e); } });
            if (good.get()) return; SystemClock.sleep(50);
        }
        fail("Condition was not reached");
    }
    private GithubRelease release(String version) throws Exception {
        return new GithubRelease(version, "https://github.com/okonnu/flipflix-android-tv/releases/download/v" + version + "/app-release.apk", "sha256:" + new String(new char[64]).replace('\0', 'a'), 100);
    }
    private JSONObject response(String version) throws Exception {
        GithubRelease r = release(version);
        return new JSONObject().put("tag_name", "v" + version).put("assets", new JSONArray().put(new JSONObject()
            .put("name", "app-release.apk").put("state", "uploaded").put("browser_download_url", r.url).put("digest", r.digest).put("size", r.size)));
    }
    @After public void cleanup() { main(() -> { if (updater != null) updater.close(); }); }

    @Test public void stableReleasesUseNumericOrderingAndExactAsset() throws Exception {
        assertTrue(GithubRelease.compare("0.1.100", "0.1.19") > 0);
        assertNull(GithubRelease.latest(response("0.1.18"), "0.1.19"));
        assertNull(GithubRelease.latest(response("0.1.19"), "0.1.19"));
        assertNull(GithubRelease.latest(response("0.1.20").put("prerelease", true), "0.1.19"));
        assertNull(GithubRelease.latest(response("0.1.20").put("draft", true), "0.1.19"));
        assertEquals("0.1.20", GithubRelease.latest(response("0.1.20"), "0.1.19").version);
        JSONObject missing = response("0.1.20"); missing.getJSONArray("assets").getJSONObject(0).remove("digest");
        try { GithubRelease.latest(missing, "0.1.19"); fail(); } catch (java.io.IOException expected) { }
        assertFalse(UpdateRepository.allowed(new URL("http://github.com/example")));
        assertFalse(UpdateRepository.allowed(new URL("https://github.com.evil.test/example")));
        assertFalse(UpdateRepository.allowed(new URL("https://user@github.com/example")));
        assertTrue(UpdateRepository.allowed(new URL("https://release-assets.githubusercontent.com/example")));
    }
    @Test public void rejectWrongPackageSignatureVersionAndMinimumSdk() throws Exception {
        PackageInfo old = new PackageInfo(); old.packageName = "test.app"; old.versionCode = 19; old.signatures = new Signature[]{new Signature("abcd")};
        PackageInfo next = new PackageInfo(); next.packageName = "test.app"; next.versionCode = 20; next.versionName = "0.1.19"; next.signatures = old.signatures;
        UpdateRepository.validatePackage(old, next, "0.1.19", 34);
        next.packageName = "other.app"; reject(old, next); next.packageName = "test.app";
        next.signatures = new Signature[]{new Signature("1234")}; reject(old, next); next.signatures = old.signatures;
        next.versionCode = 19; reject(old, next); next.versionCode = 20;
        next.applicationInfo = new android.content.pm.ApplicationInfo(); next.applicationInfo.minSdkVersion = 35; reject(old, next);
    }
    private void reject(PackageInfo old, PackageInfo next) throws Exception {
        try { UpdateRepository.validatePackage(old, next, "0.1.19", 34); fail("Unsafe package accepted"); } catch (java.io.IOException expected) { }
    }
    private final class Source extends UpdateRepository {
        GithubRelease candidate, cached;
        int checks, downloads;
        boolean fail;
        Source() { super(rule.getActivity()); }
        @Override GithubRelease latest() throws Exception { checks++; if (fail) throw new java.io.IOException("Offline"); return candidate; }
        @Override GithubRelease pending() { return cached; }
        @Override void prepare(GithubRelease r) { downloads++; cached = r; }
        @Override void verify(GithubRelease r) { }
    }
    private SharedPreferences settings() { return rule.getActivity().getSharedPreferences("update_test", 0); }
    private void controller(Source source, AtomicBoolean browsing) {
        main(() -> {
            settings().edit().clear().commit();
            ((AppUpdater) field(rule.getActivity(), "updater")).close();
            updater = new AppUpdater(rule.getActivity(), browsing::get, source, settings(), true);
            try {
                java.lang.reflect.Field f = MainActivity.class.getDeclaredField("updater"); f.setAccessible(true); f.set(rule.getActivity(), updater);
            } catch (Exception e) { throw new AssertionError(e); }
            updater.resume();
        });
    }
    @Test public void readyPromptWaitsUntilPlaybackEndsAndLaterSnoozes() throws Exception {
        Source source = new Source(); source.candidate = release("0.1.20");
        AtomicBoolean browsing = new AtomicBoolean(false); controller(source, browsing);
        main(() -> updater.check(true));
        waitFor(() -> !(boolean) field(updater, "busy"));
        main(() -> { assertNull(field(updater, "dialog")); assertEquals(1, source.downloads); browsing.set(true); updater.stateChanged(); });
        waitFor(() -> field(updater, "dialog") != null);
        main(() -> {
            java.util.ArrayList<android.view.View> labels = new java.util.ArrayList<>();
            ((AlertDialog) field(updater, "dialog")).getWindow().getDecorView()
                .findViewsWithText(labels, "Darkflix 0.1.20 is ready", android.view.View.FIND_VIEWS_WITH_TEXT);
            assertFalse("Update prompts use the new displayed app name", labels.isEmpty());
        });
        main(() -> ((AlertDialog) field(updater, "dialog")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        waitFor(() -> rule.getActivity().hasWindowFocus());
        main(() -> { updater.stateChanged(); assertNull(field(updater, "dialog")); updater.showStatus(); });
        waitFor(() -> field(updater, "dialog") != null);
    }
    @Test public void cachedDownloadRestoresBeforeNextNetworkCheck() throws Exception {
        Source source = new Source(); source.cached = release("0.1.20");
        controller(source, new AtomicBoolean(true));
        main(() -> settings().edit().putLong("next_check", System.currentTimeMillis() + AppUpdater.CHECK_INTERVAL).commit());
        waitFor(() -> field(updater, "dialog") != null);
        assertEquals(0, source.checks); assertEquals(0, source.downloads);
    }
    @Test public void offlineCheckBacksOffAndNeverPromptsInstaller() throws Exception {
        Source source = new Source(); source.fail = true; controller(source, new AtomicBoolean(true));
        main(() -> updater.check(true)); waitFor(() -> !(boolean) field(updater, "busy"));
        main(() -> { assertNull(field(updater, "dialog")); assertTrue(settings().getLong("next_check", 0) > System.currentTimeMillis()); });
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    public void installPermissionIsRequestedOnceAndDenialDoesNotLoop() throws Exception {
        org.junit.Assume.assumeFalse(rule.getActivity().getPackageManager().canRequestPackageInstalls());
        android.content.IntentFilter filter = new android.content.IntentFilter(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        filter.addDataScheme("package");
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(filter,
            new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true);
        try {
            Source source = new Source(); source.cached = release("0.1.20"); controller(source, new AtomicBoolean(true));
            waitFor(() -> field(updater, "dialog") != null);
            main(() -> ((AlertDialog) field(updater, "dialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            waitFor(() -> {
                AlertDialog dialog = (AlertDialog) field(updater, "dialog");
                return dialog != null && "Open settings".contentEquals(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText());
            });
            main(() -> ((AlertDialog) field(updater, "dialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            waitFor(() -> monitor.getHits() == 1 && !(boolean) field(updater, "waitingPermission"));
            main(() -> {
                updater.stateChanged(); assertNull(field(updater, "dialog"));
                assertFalse((boolean) field(updater, "installing"));
                assertNotNull(field(updater, "ready"));
            });
        } finally { instrumentation.removeMonitor(monitor); }
    }
    @Test public void signedFutureApkDownloadsVerifiesRestoresAndSharesReadOnly() throws Exception {
        // Generated by the documented isolated-device test build, not bundled in production.
        java.io.File fixture = new java.io.File(rule.getActivity().getCacheDir(), "signed-update-test.apk");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("update-candidate.apk");
             java.io.FileOutputStream out = new java.io.FileOutputStream(fixture)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); digest.update(buffer, 0, n); }
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.US, "%02x", b & 255));
        PackageInfo candidate = rule.getActivity().getPackageManager().getPackageArchiveInfo(fixture.getAbsolutePath(), 0);
        assertNotNull("Future APK fixture has a valid manifest", candidate);
        String candidateVersion = candidate.versionName;
        GithubRelease r = new GithubRelease(candidateVersion, release(candidateVersion).url, "sha256:" + hash, fixture.length());
        UpdateRepository repository = new UpdateRepository(rule.getActivity()) {
            @Override HttpURLConnection open(String address) throws java.io.IOException {
                return new HttpURLConnection(new URL(address)) {
                    @Override public InputStream getInputStream() throws java.io.IOException { return new java.io.FileInputStream(fixture); }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() { }
                };
            }
        };
        try {
            repository.prepare(r); assertEquals(candidateVersion, repository.pending().version);
            Intent intent = AppUpdater.installIntent(rule.getActivity(), repository.apk());
            assertEquals("content", intent.getData().getScheme()); assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.getAction());
            assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            assertNotNull(intent.getClipData());
            try (InputStream in = rule.getActivity().getContentResolver().openInputStream(intent.getData())) { assertEquals('P', in.read()); assertEquals('K', in.read()); }
            GithubRelease bad = new GithubRelease(r.version, r.url, release("0.1.20").digest, r.size);
            try { repository.prepare(bad); fail("Bad checksum accepted"); } catch (java.io.IOException expected) { }
            assertNotNull("Previous good APK survives failed replacement", repository.pending());
        } finally { repository.discard(); repository.close(); fixture.delete(); }
    }
}
