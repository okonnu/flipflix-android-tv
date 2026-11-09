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
                AlertDialog credits = new AlertDialog.Builder(activity).setTitle("Media credits")
                    .setMessage("Movie and series listings and artwork: TMDB, accessed through Pantyflix's catalog API.\n\nThis product uses the TMDB API but is not endorsed or certified by TMDB.\n\nAvailable trailers play through YouTube. Live sports listings come from the Streamed service through Pantyflix. Playback availability and options depend on the selected provider.\n\nStartup soundtrack: Epic Cinematic Logo Reveal by BreakzStudios (Pixabay #333643), adapted under the Pixabay Content License.")
                    .setNeutralButton("Software licenses", (c, which) -> softwareLicenses(activity))
                    .setPositiveButton("OK", null).create();
                credits.show(); dialog(credits, 500, -2);
            }).setNegativeButton("Cancel", null).create();
        profile.show(); dialog(profile, 460, -2);
    }
    private static void softwareLicenses(Activity activity) {
        StringBuilder text = new StringBuilder();
        for (String asset : new String[]{"native-playback.txt", "Apache-2.0.txt"}) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(activity.getAssets().open("licenses/" + asset), java.nio.charset.StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) text.append(line).append('\n');
                text.append('\n');
            } catch (java.io.IOException ignored) { }
        }
        AlertDialog licenses = new AlertDialog.Builder(activity).setTitle("Software licenses")
            .setMessage(text.toString()).setPositiveButton("Done", null).create();
        licenses.show(); dialog(licenses, 600, 460);
    }
}
