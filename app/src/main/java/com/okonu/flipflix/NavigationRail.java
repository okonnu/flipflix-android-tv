package com.okonu.flipflix;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Same quiet rail on every browsing screen, excluded from fullscreen playback. */
final class NavigationRail extends FrameLayout {
    interface Open { void accept(String section); }
    final List<ImageButton> buttons = new ArrayList<>();
    NavigationRail(Activity activity, Open open, Runnable focused) {
        super(activity); setBackgroundColor(0xf2070a11);
        LinearLayout items = new LinearLayout(activity); items.setOrientation(LinearLayout.VERTICAL); items.setGravity(Gravity.CENTER);
        addView(items, new LayoutParams(-1, -1));
        String[] glyphs = {"profile", "search", "browse", "library"};
        String[] labels = {"Local profile", "Search", "Browse", "Library"};
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i], glyph = glyphs[i];
            ImageButton b = new ImageButton(activity); b.setContentDescription(label); b.setFocusableInTouchMode(true);
            b.setPadding(dp(14), dp(14), dp(14), dp(14)); b.setImageDrawable(new ControlIcon(glyph, AppStyle.MUTED));
            b.setBackground(AppStyle.shape(activity, Color.TRANSPARENT, Color.TRANSPARENT));
            b.setOnClickListener(v -> { if (label.equals("Local profile")) AppStyle.profile(activity); else open.accept(label); });
            b.setOnFocusChangeListener((v, hasFocus) -> {
                b.setBackground(AppStyle.shape(activity, hasFocus ? 0x30485261 : Color.TRANSPARENT, hasFocus ? AppStyle.MUTED : Color.TRANSPARENT));
                b.setImageDrawable(new ControlIcon(glyph, hasFocus ? Color.WHITE : AppStyle.MUTED));
                if (hasFocus) focused.run();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(46), dp(46)); p.bottomMargin = dp(9);
            items.addView(b, p); buttons.add(b);
        }
        TextView brand = new TextView(activity); brand.setText("F"); brand.setTextSize(27); brand.setTextColor(AppStyle.ACCENT);
        brand.setTypeface(AppStyle.MEDIUM); brand.setGravity(Gravity.CENTER); brand.setIncludeFontPadding(false);
        LayoutParams bp = new LayoutParams(-1, dp(52), Gravity.TOP); bp.topMargin = dp(8); addView(brand, bp);
        TextView footer = new TextView(activity); footer.setText("FLIPFLIX"); footer.setTextSize(8); footer.setTextColor(AppStyle.MUTED);
        footer.setTypeface(AppStyle.MEDIUM); footer.setLetterSpacing(.08f); footer.setGravity(Gravity.CENTER);
        addView(footer, new LayoutParams(-1, dp(26), Gravity.BOTTOM));
    }
    private int dp(int n) { return AppStyle.dp(getContext(), n); }
    void focusBrowse() { buttons.get(2).requestFocus(); }
    boolean navigate(int key, Runnable returnToContent) {
        if (!hasFocus()) return false;
        int index = buttons.indexOf(findFocus());
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) returnToContent.run();
        else if (key == KeyEvent.KEYCODE_DPAD_UP) buttons.get(Math.max(0, index - 1)).requestFocus();
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) buttons.get(Math.min(3, index + 1)).requestFocus();
        return true;
    }
}
