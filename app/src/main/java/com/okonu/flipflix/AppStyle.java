package com.okonu.flipflix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;

/** Shared discovery design tokens. Video surfaces deliberately remain true black. */
final class AppStyle {
    static final int BG = 0xff070a11, PANEL = 0xff151c29, CONTROL = 0x40485261;
    static final int MUTED = 0xffa7afbd, TEXT = 0xffd0d5df, BORDER = 0xff354051, ACCENT = 0xffff3f4c;
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static int dp(Context context, float value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    static GradientDrawable shape(Context context, int fill, int border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(context, 5));
        d.setStroke(dp(context, 2), border); return d;
    }
    static GradientDrawable leftShade() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{BG, 0xe6070a11, 0x28070a11, 0x00070a11});
    }
    static void button(Button button, boolean primary, boolean focused) {
        button.setBackground(shape(button.getContext(), focused ? Color.WHITE : primary ? TEXT : CONTROL, Color.TRANSPARENT));
        button.setTextColor(primary || focused ? BG : Color.WHITE);
        button.setTypeface(MEDIUM); button.setStateListAnimator(null);
    }
    static void dialog(AlertDialog dialog, int width, int height) {
        if (dialog.getWindow() == null) return;
        Context c = dialog.getContext();
        dialog.getWindow().setBackgroundDrawable(shape(c, PANEL, BORDER));
        dialog.getWindow().setDimAmount(.65f);
        dialog.getWindow().setLayout(Math.min(dp(c, width), c.getResources().getDisplayMetrics().widthPixels - dp(c, 64)),
            height < 0 ? height : Math.min(dp(c, height), c.getResources().getDisplayMetrics().heightPixels - dp(c, 40)));
    }
    static void profile(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("discovery", 0);
        EditText name = new EditText(activity); name.setSingleLine(true); name.setText(prefs.getString("profile", "Guest"));
        name.setSelectAllOnFocus(true); name.setTextColor(Color.WHITE); name.setTextSize(16);
        name.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        AlertDialog profile = new AlertDialog.Builder(activity).setTitle("Local profile")
            .setMessage("A name for this device. No online account or sign-in is required.").setView(name)
            .setPositiveButton("Save", (d, w) -> prefs.edit().putString("profile", name.getText().toString().trim()).apply())
            .setNeutralButton("Media credits", (d, w) -> {
                AlertDialog credits = new AlertDialog.Builder(activity).setTitle("Open-film preview credits")
                    .setMessage("Sintel: (c) 2010 Blender Foundation / durian.blender.org\nBig Buck Bunny: (c) 2008 Blender Foundation / bigbuckbunny.org\n\nCC BY 3.0: creativecommons.org/licenses/by/3.0/\nTrailers and stills supplied by media.w3.org. Ambient excerpts are cropped, muted, and limited to 25 seconds. These are replacement sample assets, not Flipflix originals. Other catalog artwork comes from TMDB. No live program schedules are invented.")
                    .setPositiveButton("OK", null).create();
                credits.show(); dialog(credits, 500, -2);
            }).setNegativeButton("Cancel", null).create();
        profile.show(); dialog(profile, 460, -2);
    }
}
