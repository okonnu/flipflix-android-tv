package com.okonu.flipflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only scheduling; downloads and validation never block browsing. */
final class AppUpdater implements AutoCloseable {
    interface Browsing { boolean getAsBoolean(); }
    static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
    static final long RETRY_INTERVAL = 15 * 60 * 1000L;
    static final long SNOOZE = 24 * 60 * 60 * 1000L;
    static final int SETTINGS_REQUEST = 8091, INSTALL_REQUEST = 8092;
    private final Activity activity;
    private final Browsing browsing;
    private final UpdateRepository repository;
    private final SharedPreferences prefs;
    private final boolean automatic;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable tick = () -> check(false);
    private boolean foreground, restored, busy, closed, waitingPermission, installing;
    private boolean manual;
    private GithubRelease ready;
    private String status = "Updates are checked automatically while you browse. Downloads are verified before installation.";
    private AlertDialog dialog;

    AppUpdater(Activity activity, Browsing browsing) {
        this(activity, browsing, new UpdateRepository(activity), activity.getSharedPreferences("app_updates", 0),
            activity.getPackageName().equals("com.okonu.flipflix"));
    }
    AppUpdater(Activity activity, Browsing browsing, UpdateRepository repository, SharedPreferences prefs, boolean automatic) {
        this.activity = activity; this.browsing = browsing; this.repository = repository; this.prefs = prefs; this.automatic = automatic;
    }
    void resume() { foreground = true; stateChanged(); }
    void pause() { foreground = false; ui.removeCallbacks(tick); }
    private boolean available() {
        return !closed && foreground && !activity.isFinishing() && activity.hasWindowFocus() && browsing.getAsBoolean();
    }
    void stateChanged() {
        ui.removeCallbacks(tick);
        if (!available() || dialog != null || waitingPermission || installing || busy) return;
        if (ready != null) {
            if (manual || System.currentTimeMillis() >= prefs.getLong("snooze_until", 0)) offer();
            else if (automatic) ui.postDelayed(tick, Math.max(800, prefs.getLong("snooze_until", 0) - System.currentTimeMillis()));
            return;
        }
        if (automatic) ui.postDelayed(tick, !restored ? 800 : Math.max(800, prefs.getLong("next_check", 0) - System.currentTimeMillis()));
    }
    void showStatus() {
        if (closed || dialog != null) return;
        if (ready != null) { manual = true; offer(); return; }
        dialog = new AlertDialog.Builder(activity).setTitle("Flipflix " + repository.installedVersion())
            .setMessage(status + "\n\nAndroid requires your confirmation to install an update. Checks never interrupt a movie.")
            .setPositiveButton(busy ? "Working..." : "Check now", (d, w) -> ui.post(() -> check(true)))
            .setNegativeButton("Close", null).create();
        display();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
    }
    void check(boolean requested) {
        if (closed || busy || waitingPermission || installing || (!requested && (!automatic || !available()))) return;
        manual = requested; busy = true;
        status = "Checking GitHub releases...";
        if (requested) toast(status);
        boolean due = requested || System.currentTimeMillis() >= prefs.getLong("next_check", 0);
        if (due) prefs.edit().putLong("next_check", System.currentTimeMillis() + RETRY_INTERVAL).apply();
        boolean restore = !restored; restored = true;
        worker.execute(() -> {
            try {
                GithubRelease result = restore ? repository.pending() : ready;
                // A verified download survives Later, process death, and TV restarts.
                if (result == null && due) {
                    result = repository.latest();
                    if (result != null) {
                        ui.post(() -> { if (!closed) status = "Downloading and verifying Flipflix update..."; });
                        repository.prepare(result);
                    }
                }
                GithubRelease completed = result;
                ui.post(() -> {
                    if (closed) return;
                    busy = false; ready = completed;
                    if (due) prefs.edit().putLong("next_check", System.currentTimeMillis() + CHECK_INTERVAL).apply();
                    status = ready == null ? "You have the latest release." : "Flipflix " + ready.version + " is ready to install.";
                    if (manual && ready == null && foreground) toast(status);
                    stateChanged();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (closed) return;
                    busy = false; status = "Could not update. " + friendly(e);
                    if (manual && foreground) toast(status);
                    stateChanged();
                });
            }
        });
    }
    private void offer() {
        if (!available() || dialog != null || ready == null || busy) return;
        manual = false;
        dialog = new AlertDialog.Builder(activity).setTitle("Flipflix " + ready.version + " is ready")
            .setMessage("The update was downloaded from GitHub and verified. Install it now? Your library and saved settings will stay intact.")
            .setPositiveButton("Install update", (d, w) -> ui.post(this::install))
            .setNegativeButton("Later", (d, w) -> snooze()).create();
        dialog.setOnCancelListener(d -> snooze());
        display();
    }
    private void snooze() { prefs.edit().putLong("snooze_until", System.currentTimeMillis() + SNOOZE).apply(); }
    private void display() {
        AlertDialog shown = dialog;
        shown.setOnDismissListener(d -> { if (dialog == shown) dialog = null; });
        shown.show(); AppStyle.dialog(shown, 480, -2);
        shown.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }
    private void install() {
        if (closed || busy || ready == null || !foreground) return;
        snooze(); // Cancel/back from system screens must not cause an install loop.
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            dialog = new AlertDialog.Builder(activity).setTitle("Allow Flipflix updates")
                .setMessage("On the next screen, allow Flipflix to install unknown apps, then press Back. This permission is needed for updates downloaded from GitHub.")
                .setPositiveButton("Open settings", (d, w) -> {
                    waitingPermission = true;
                    try {
                        activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())), SETTINGS_REQUEST);
                    } catch (RuntimeException e) {
                        waitingPermission = false;
                        toast("Open Fire TV Settings > My Fire TV > Developer Options > Install unknown apps, enable Flipflix, then retry App updates.");
                    }
                }).setNegativeButton("Later", null).create();
            display(); return;
        }
        busy = true; installing = true;
        worker.execute(() -> {
            try {
                repository.verify(ready);
                File apk = repository.apk();
                ui.post(() -> {
                    busy = false;
                    if (closed) return;
                    if (!foreground || !browsing.getAsBoolean()) { installing = false; return; }
                    try { activity.startActivityForResult(installIntent(activity, apk), INSTALL_REQUEST); }
                    catch (RuntimeException e) { installing = false; toast("The TV could not open its installer. Try App updates again or use the download link."); }
                });
            } catch (Exception e) {
                repository.discard();
                ui.post(() -> {
                    if (closed) return;
                    busy = false; installing = false; ready = null;
                    status = "Update validation failed. Please check for updates again."; toast(status);
                });
            }
        });
    }
    static Intent installIntent(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
        intent.setClipData(ClipData.newRawUri("Flipflix update", uri));
        return intent;
    }
    void activityResult(int request) {
        if (request == SETTINGS_REQUEST) {
            waitingPermission = false;
            if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) ui.post(() -> {
                // onActivityResult can precede onResume; allow that lifecycle step to finish.
                ui.postDelayed(() -> { if (!closed) install(); }, 300);
            });
            else toast("Install permission was not enabled. You can retry under Browse > App updates.");
        } else if (request == INSTALL_REQUEST) { installing = false; }
    }
    private String friendly(Exception e) {
        return e instanceof java.io.IOException && e.getMessage() != null ? e.getMessage() : "Check your connection and try again later.";
    }
    private void toast(String message) { if (!closed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
    @Override public void close() {
        closed = true; ui.removeCallbacksAndMessages(null); repository.close(); worker.shutdownNow();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}
