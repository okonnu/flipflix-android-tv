package com.okonu.flipflix;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.filters.SdkSuppress;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class StartupIntroTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class, false, false);
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private void main(Runnable action) { instrumentation().runOnMainSync(action); }
    private Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private Intent introIntent() {
        return new Intent(instrumentation().getTargetContext(), MainActivity.class)
            .putExtra("test_show_startup_intro", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    private MainActivity launch() { return rule.launchActivity(introIntent()); }
    private StartupIntro intro(MainActivity a) { return (StartupIntro) field(a, "startupIntro"); }
    private FrameLayout content(MainActivity a) { return (FrameLayout) field(a, "root"); }
    private void await(String message, BooleanSupplier condition, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        fail(message);
    }

    @Test public void crossfadeIsSmoothBoundedAndCompletelyTransparentAtTheEnd() {
        assertEquals(1f, StartupIntro.opacityAt(0), 0f);
        assertEquals(1f, StartupIntro.opacityAt(7000), 0f);
        assertEquals(.5f, StartupIntro.opacityAt(7500), .001f);
        assertEquals(0f, StartupIntro.opacityAt(8000), 0f);
        assertEquals(0f, StartupIntro.opacityAt(9000), 0f);
        float previous = 1f;
        for (long t = 7000; t <= 8000; t += 16) {
            float current = StartupIntro.opacityAt(t);
            assertTrue(current <= previous && previous - current < .03f);
            previous = current;
        }
    }

    @Test public void realAudioDrivesVectorsForEightSecondsAndCrossfadesOverMountedHome() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        assertNotNull(intro);
        FrameLayout home = content(a);
        View discovery = (View) field(a, "discovery");
        main(() -> {
            assertSame(home, discovery.getParent());
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, home.getDescendantFocusability());
            for (android.widget.ImageView piece : intro.logo.pieces) assertTrue(piece.getDrawable() instanceof VectorDrawable);
            assertTrue(intro.overlay.hasFocus());
        });
        await("The bundled soundtrack should decode without a network request", () -> intro.player != null && intro.player.isPlaying(), 5000);
        await("Exo outputs the bundled soundtrack", () -> {
            androidx.media3.exoplayer.DecoderCounters counters = intro.player.getAudioDecoderCounters();
            if (counters == null) return false;
            counters.ensureUpdated();
            return counters.renderedOutputBufferCount > 0 && intro.player.getVolume() == 1f;
        }, 2000);
        main(() -> {
            assertTrue("Vector lockup must actually occupy the screen, not remain a 1px placeholder",
                intro.logo.lockup.getWidth() > intro.logo.getWidth() / 3);
            assertEquals(intro.logo.lockup.getLayoutParams().height, intro.logo.lockup.getHeight());
            assertEquals(intro.logo.lockup.getWidth(), intro.logo.pieces[0].getWidth());
        });
        await("Soundtrack duration available", () -> intro.player.getDuration() > 0, 1000);
        main(() -> assertEquals(8000, intro.player.getDuration(), 50));
        await("The whole vector layer must become translucent over Home", () -> intro.isActive() && intro.overlay.getAlpha() < .85f && intro.overlay.getAlpha() > .1f, 9000);
        main(() -> {
            assertSame(home, discovery.getParent());
            assertEquals(View.VISIBLE, home.getVisibility());
            assertEquals(1f, home.getAlpha(), 0f);
            assertNotNull(intro.overlay.getParent());
        });
        await("Intro should finish and release its decoder", () -> !intro.isActive(), 2500);
        main(() -> {
            assertNull(intro.player);
            for (android.widget.ImageView piece : intro.logo.pieces) assertNull(piece.getDrawable());
            assertNull(intro.overlay.getParent());
            assertEquals(0f, intro.overlay.getAlpha(), 0f);
            assertNotEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, home.getDescendantFocusability());
            assertNotNull(home.findFocus());
            assertSame(discovery, field(a, "discovery"));
        });
    }

    @Test public void remoteAndTouchCannotSkipOrActivateHome() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        await("Soundtrack playing", () -> intro.player != null && intro.player.isPlaying(), 5000);
        main(() -> {
            intro.player.pause();
            for (int key : new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}) {
                assertTrue(a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
                assertTrue(a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)));
                assertTrue(intro.isActive());
            }
            a.onBackPressed();
            assertFalse(a.isFinishing());
            assertFalse(intro.overlay.isClickable());
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent touch = MotionEvent.obtain(now, now, action, 100, 100, 0);
                assertTrue(intro.overlay.dispatchTouchEvent(touch)); touch.recycle();
            }
            assertTrue(intro.isActive());
            assertEquals(1f, intro.overlay.getAlpha(), 0f);
            assertFalse((boolean) field(a, "detailOpen"));
            assertFalse((boolean) field(a, "playerOpen"));
            assertFalse(intro.handleKey(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)));
        });
    }

    @Test public void keyHeldAtHandoffDoesNotClickTheFirstHomeCard() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        main(() -> {
            assertTrue(a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)));
            intro.close();
            assertTrue(a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)));
            assertFalse((boolean) field(a, "detailOpen"));
        });
    }

    @Test public void leavingAppStopsAudioAndReturningDoesNotReplayIntro() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        await("Soundtrack playing", () -> intro.player != null && intro.player.isPlaying(), 5000);
        main(() -> {
            instrumentation().callActivityOnPause(a);
            assertFalse(intro.isActive()); assertNull(intro.player);
            instrumentation().callActivityOnResume(a);
            assertSame(intro, intro(a)); assertFalse(intro.isActive());
            intro.close(); // Cleanup is idempotent.
            assertNull(intro.overlay.getParent());
        });
    }

    @Test public void losingAudioFocusFadesOutAndReleasesPlayer() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        await("Soundtrack playing", () -> intro.player != null && intro.player.isPlaying(), 5000);
        main(() -> ((android.media.AudioManager.OnAudioFocusChangeListener) field(intro, "focus"))
            .onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS));
        await("Audio focus loss returns to Home", () -> !intro.isActive(), 1500);
        main(() -> {
            assertNull(intro.player);
            assertEquals(false, field(intro, "focusHeld")); assertEquals(false, field(intro, "receiverRegistered"));
        });
    }

    @Test public void recreationDoesNotReplayIntro() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(introIntent())) {
            scenario.onActivity(a -> assertNotNull(intro(a)));
            scenario.recreate();
            scenario.onActivity(a -> assertNull(intro(a)));
        }
    }

    @Test public void unavailableMediaCannotTrapViewerOnBlackScreen() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        main(() -> {
            intro.player.stop();
            intro.player.setMediaItem(androidx.media3.common.MediaItem.fromUri("file:///missing-startup-soundtrack.m4a"));
            intro.player.prepare(); intro.player.play();
        });
        await("Decode failure should reveal Home", () -> !intro.isActive(), 3000);
        main(() -> {
            assertNull(intro.player); assertNotNull(content(a).findFocus());
            assertNotNull(field(a, "discovery"));
        });
    }

    @Test public void normalRegressionTestsCanOptOutOnlyInDebugBuild() {
        MainActivity a = rule.launchActivity(null);
        main(() -> assertNull(intro(a)));
    }

    @Test public void vectorPiecesFadeTogetherAndSettleOnImpactAtAnyResolution() {
        MainActivity a = rule.launchActivity(null);
        main(() -> {
            StartupLogo logo = new StartupLogo(a);
            for (int[] size : new int[][]{{1280, 720}, {1920, 1080}, {3840, 2160}}) {
                logo.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                logo.layout(0, 0, size[0], size[1]);
                assertEquals(Math.round(size[1] * .62f), logo.lockup.getLayoutParams().height);
                assertEquals(logo.lockup.getLayoutParams().width, logo.lockup.getWidth());
                assertEquals(logo.lockup.getLayoutParams().height, logo.lockup.getHeight());
                logo.render(900);
                for (android.widget.ImageView piece : logo.pieces) assertEquals(0f, piece.getAlpha(), 0f);
                logo.render(1900);
                for (int i = 0; i < 4; i++) assertEquals(.5f, logo.pieces[i].getAlpha(), .001f);
                assertEquals(0f, logo.pieces[4].getAlpha(), 0f);
                logo.render(4200);
                for (int i = 0; i < 4; i++) {
                    assertEquals(1f, logo.pieces[i].getAlpha(), 0f);
                    assertEquals(0f, logo.pieces[i].getTranslationX(), 0f);
                    assertEquals(0f, logo.pieces[i].getTranslationY(), 0f);
                }
                logo.render(8000);
                for (android.widget.ImageView piece : logo.pieces) {
                    assertTrue(piece.getDrawable() instanceof VectorDrawable);
                    assertEquals(1f, piece.getAlpha(), 0f);
                }
            }
        });
    }

    @Test public void updatePromptWaitsUntilIntroHasDisappeared() {
        MainActivity a = launch();
        main(() -> {
            AppUpdater.Browsing browsing = (AppUpdater.Browsing) field(field(a, "updater"), "browsing");
            assertFalse(browsing.getAsBoolean());
            intro(a).close();
            assertTrue(browsing.getAsBoolean());
        });
    }

    @Test public void catalogLoadedUnderIntroGetsInitialCardAndHeroAtHandoff() {
        MainActivity a = launch(); StartupIntro intro = intro(a);
        DiscoveryView home = (DiscoveryView) field(a, "discovery");
        main(() -> {
            try {
                Field generation = MainActivity.class.getDeclaredField("generation");
                generation.setAccessible(true); generation.setInt(a, Integer.MAX_VALUE);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            DiscoveryItem item = new DiscoveryItem();
            item.key = "movie:9999"; item.title = "Startup home fixture";
            item.category = "Featured picks"; item.thumbnail = item.heroImage = "";
            home.rows.get(0).items.clear(); home.rows.get(0).items.add(item);
            home.rows.get(0).complete = true;
            home.refreshRow(0);
        });
        await("Catalog card layout", () -> !home.isLayoutRequested() && home.rowViews.get(0).cards.get(0).getWidth() > 0, 3000);
        main(() -> {
            assertFalse(home.rowViews.get(0).cards.get(0).hasFocus());
            intro.close();
            assertTrue(home.rowViews.get(0).cards.get(0).hasFocus());
            assertEquals("Startup home fixture", ((android.widget.TextView) field(home, "title")).getText().toString());
        });
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void reducedMotionGoesDirectlyToHomeWithoutAudio() {
        android.content.ContentResolver resolver = instrumentation().getTargetContext().getContentResolver();
        String previous = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE);
        instrumentation().getUiAutomation().adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, "0");
            MainActivity a = launch();
            main(() -> { assertNull(intro(a)); assertNotNull(field(a, "discovery")); });
        } finally {
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous);
            instrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }
}
