package com.okonu.flipflix;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real HLS decoding through the shared strategy interface, on the physical TV. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class NativeStrategyTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private NativePlayback playback;
    private PlaybackControls controls;
    private void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private interface Check { boolean ready(); }
    private void await(String message, Check check) {
        AtomicBoolean done = new AtomicBoolean(); long end = SystemClock.uptimeMillis() + 30000;
        while (!done.get() && SystemClock.uptimeMillis() < end) { main(() -> done.set(check.ready())); SystemClock.sleep(100); }
        String[] observed = {""};
        if (!done.get() && playback != null) main(() -> {
            PlaybackBackend.State s = playback.state();
            observed[0] = " [engine=" + playback.engine() + ", ready=" + s.available + ", position=" + s.position
                + ", paused=" + s.paused + ", muted=" + s.muted + ", fill=" + s.fillScreen + "]";
        });
        assertTrue(message + observed[0], done.get());
    }
    private void install() throws Exception {
        File dir = new File(rule.getActivity().getCacheDir(), "native-tracks");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        android.content.res.AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        for (String name : assets.list("native-tracks")) {
            try (InputStream in = assets.open("native-tracks/" + name); FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        main(() -> {
            try {
                java.lang.reflect.Field generation = MainActivity.class.getDeclaredField("generation");
                generation.setAccessible(true); generation.set(rule.getActivity(), 999999);
            } catch (Exception e) { throw new AssertionError(e); }
            playback = new NativePlayback(rule.getActivity(), new File(dir, "master.m3u8").toURI().toString(), "https://example.com/", null);
            FrameLayout screen = new FrameLayout(rule.getActivity()); screen.addView(playback.view, new FrameLayout.LayoutParams(-1,-1));
            rule.getActivity().setContentView(screen);
            controls = new PlaybackControls(rule.getActivity(), () -> playback, playback.view::requestFocus,
                "Native strategy test", null, null, () -> {}, () -> {}, () -> {});
            screen.addView(controls, new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            try {
                java.lang.reflect.Field overlay = MainActivity.class.getDeclaredField("playerOverlay");
                overlay.setAccessible(true); overlay.set(rule.getActivity(), controls);
                java.lang.reflect.Field open = MainActivity.class.getDeclaredField("playerOpen");
                open.setAccessible(true); open.set(rule.getActivity(), true);
            } catch (Exception e) { throw new AssertionError(e); }
            playback.start(); controls.activate();
        });
        await("HLS decodes video and audio with ExoPlayer", () -> playback.state().available
            && playback.engine().equals("ExoPlayer") && NativeFirstPlaybackTest.audioDecoded(playback)
            && NativeFirstPlaybackTest.exoPlayer(playback).getVideoFormat() != null);
    }
    @After public void close() { main(() -> { if (controls != null) controls.deactivate(); if (playback != null) playback.release(); }); }
    private void key(int code) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void focus(String label) {
        main(() -> { controls.show(false); assertTrue(focusIn(controls, label)); });
        await("Control receives focus", () -> rule.getActivity().hasWindowFocus()
            && label.contentEquals(rule.getActivity().getCurrentFocus().getContentDescription()));
    }
    private boolean focusIn(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view.requestFocus();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            if (focusIn(((ViewGroup)view).getChildAt(i), label)) return true;
        return false;
    }
    private AlertDialog dialog() {
        try { java.lang.reflect.Field f = PlaybackControls.class.getDeclaredField("settingsDialog"); f.setAccessible(true); return (AlertDialog) f.get(controls); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void awaitDialog() { await("Remote dialog owns input", () -> dialog() != null && dialog().isShowing() && dialog().getWindow().getDecorView().hasWindowFocus()); }
    private void selectOption(int index) {
        AlertDialog[] target = new AlertDialog[1];
        main(() -> { target[0] = dialog(); target[0].getListView().requestFocus(); target[0].getListView().setSelection(index); });
        await("Requested menu row owns remote focus", () -> dialog() == target[0]
            && target[0].getListView().getSelectedItemPosition() == index
            && target[0].getListView().getSelectedView() != null);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private int videoHeight() {
        androidx.media3.common.Format format = NativeFirstPlaybackTest.exoPlayer(playback).getVideoFormat();
        return format == null ? 0 : format.height;
    }
    private void capture(String name) throws Exception {
        android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream("/sdcard/Download/" + name + ".png")) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { image.recycle(); }
    }
    @Test public void qualityAudioAndSubtitlesSelectActualTracks() throws Exception {
        install();
        await("Source capabilities are present", () -> playback.state().qualities.size() == 3
            && playback.state().settings.get("audio").size() == 2 && playback.state().subtitles.size() == 2);
        focus("Video quality"); key(KeyEvent.KEYCODE_DPAD_CENTER); awaitDialog();
        capture("darkflix-exo-quality");
        main(() -> assertEquals(3, dialog().getListView().getCount()));
        selectOption(1);
        await("Exo decodes the chosen lower rendition", () -> videoHeight() == 90);
        main(() -> {
            PlaybackBackend.State state = playback.state();
            assertFalse(state.qualities.get(0).selected);
            playback.command("quality", state.qualities.get(2).id);
            playback.command("seek", "30000");
        });
        await("Exo decodes the chosen higher rendition", () -> videoHeight() == 180);
        main(() -> playback.command("audio", playback.state().settings.get("audio").get(1).id));
        await("Spanish audio actually selected", () -> NativeFirstPlaybackTest.exoPlayer(playback).getAudioFormat() != null
            && "es".equals(NativeFirstPlaybackTest.exoPlayer(playback).getAudioFormat().language));
        main(() -> playback.command("subtitle", playback.state().subtitles.get(1).id));
        await("Caption track selected and rendered", () -> !playback.state().subtitles.get(0).selected
            && !NativeFirstPlaybackTest.exoPlayer(playback).getCurrentCues().cues.isEmpty());
        await("Controls auto-hide so subtitles are unobscured", () -> controls.getVisibility() != View.VISIBLE);
        capture("darkflix-exo-subtitles");
        main(() -> { playback.command("subtitle", "off"); playback.command("quality", "auto"); playback.command("mute", ""); });
        await("Off, Auto and mute acknowledged", () -> playback.state().subtitles.get(0).selected
            && playback.state().qualities.get(0).selected && NativeFirstPlaybackTest.playerVolume(playback) == 0);
        main(() -> playback.command("mute", ""));
        await("Audio output restored", () -> NativeFirstPlaybackTest.playerVolume(playback) == 100 && NativeFirstPlaybackTest.audioDecoded(playback));
    }
    @Test public void replacementSessionRejectsStaleMenuAndKeepsPausedCheckpoint() throws Exception {
        install();
        main(() -> { playback.command("pause", ""); playback.command("seek", "20000"); playback.command("mute", ""); playback.command("resize", "fill"); });
        await("Paused checkpoint ready", () -> playback.state().paused && Math.abs(playback.state().position-20000) < 1200);
        focus("Video quality"); key(KeyEvent.KEYCODE_DPAD_CENTER); awaitDialog();
        // A retry or source change while a quality menu is open must not send
        // stale option IDs to the replacement session.
        main(() -> {
            FrameLayout parent = (FrameLayout) playback.view.getParent();
            parent.removeView(playback.view); playback.release();
            File master = new File(rule.getActivity().getCacheDir(), "native-tracks/master.m3u8");
            playback = new NativePlayback(rule.getActivity(), master.toURI().toString(), "https://example.com/", null);
            parent.addView(playback.view, 0, new FrameLayout.LayoutParams(-1,-1));
            playback.start(20000); playback.command("pause", "");
            playback.command("mute", ""); playback.command("resize", "fill");
        });
        await("Replacement prepares at paused checkpoint", () -> playback.state().available
            && playback.state().paused && playback.state().muted && playback.state().fillScreen
            && Math.abs(playback.state().position-20000) < 1500);
        SystemClock.sleep(1500);
        main(() -> assertEquals("Paused HLS time must remain at its acknowledged checkpoint", 20000, playback.state().position, 1500));
        selectOption(1);
        main(() -> {
            assertEquals("ExoPlayer", playback.engine());
            assertEquals(3, playback.state().qualities.size());
            assertTrue("Stale menu must not select a rendition", playback.state().qualities.get(0).selected);
            assertFalse("No engine selector", playback.state().settings.containsKey("engine"));
        });
        main(() -> { playback.command("mute", ""); playback.command("play", ""); });
        await("Replacement session decodes audio", () -> playback.state().position > 21000 && NativeFirstPlaybackTest.audioDecoded(playback));
    }
}
