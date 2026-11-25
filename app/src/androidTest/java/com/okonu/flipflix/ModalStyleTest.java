package com.okonu.flipflix;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ModalStyleTest {
    @Rule public ActivityTestRule<MainActivity> rule = new ActivityTestRule<>(MainActivity.class);
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void key(int code) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); SystemClock.sleep(200); }
    private void capture(String name) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureUi"))) return;
        SystemClock.sleep(350);
        Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream("/sdcard/Download/" + name + ".png")) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { shot.recycle(); }
    }
    @Test public void styledChoiceListKeepsSelectionIconsAndRemoteActions() throws Exception {
        AlertDialog[] dialog = new AlertDialog[1]; AtomicInteger chosen = new AtomicInteger(-1);
        main(() -> {
            dialog[0] = AppStyle.modal(rule.getActivity(), "Audio language / dubbing")
                .setSingleChoiceItems(new String[]{"English", "Spanish", "French"}, 1, (d, i) -> { chosen.set(i); d.dismiss(); }).create();
            dialog[0].show(); AppStyle.dialog(dialog[0], 460, -2); dialog[0].getListView().requestFocus();
            assertEquals(1, dialog[0].getListView().getCheckedItemPosition());
        });
        try {
            SystemClock.sleep(350);
            main(() -> {
                TextView row = (TextView) dialog[0].getListView().getSelectedView();
                assertNotNull(row); assertEquals("Spanish", row.getText().toString()); assertNotNull(row.getCompoundDrawables()[0]);
                assertTrue(row.getHeight() >= AppStyle.dp(rule.getActivity(), 48));
            });
            capture("darkflix-audio-modal"); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(2, chosen.get());
        } finally { main(() -> dialog[0].dismiss()); }
    }
    @Test public void styledConfirmationRetainsActionsAndReadableFocus() throws Exception {
        AlertDialog[] dialog = new AlertDialog[1]; AtomicInteger clicked = new AtomicInteger();
        main(() -> {
            dialog[0] = AppStyle.modal(rule.getActivity(), "New version available")
                .setMessage("Darkflix 0.1.50 is ready. Would you like to install it?\n\nThe download is verified. Your library and saved settings will stay intact.")
                .setPositiveButton("Install", (d, which) -> clicked.incrementAndGet()).setNegativeButton("Not now", null).create();
            dialog[0].show(); AppStyle.dialog(dialog[0], 480, -2); dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        });
        try {
            SystemClock.sleep(350);
            main(() -> {
                android.widget.Button button = dialog[0].getButton(AlertDialog.BUTTON_POSITIVE);
                assertTrue(button.hasFocus()); assertEquals(AppStyle.BG, button.getCurrentTextColor()); assertNotNull(button.getCompoundDrawables()[0]);
                assertTrue(button.getWidth() > 0); assertEquals(View.VISIBLE, button.getVisibility());
            });
            capture("darkflix-update-modal"); key(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals(1, clicked.get());
        } finally { main(() -> dialog[0].dismiss()); }
    }
}
