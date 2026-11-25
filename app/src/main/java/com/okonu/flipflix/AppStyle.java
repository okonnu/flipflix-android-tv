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
import android.widget.TextView;
import android.widget.ListAdapter;
import android.widget.BaseAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.content.res.ColorStateList;
import android.graphics.drawable.StateListDrawable;

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
        GradientDrawable surface = shape(c, 0xff111318, 0xff30333a); surface.setCornerRadius(dp(c, 12)); surface.setStroke(dp(c, 1), 0xff30333a);
        dialog.getWindow().setBackgroundDrawable(surface);
        dialog.getWindow().setDimAmount(.78f);
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dialog.getWindow().setLayout(Math.min(dp(c, width), c.getResources().getDisplayMetrics().widthPixels - dp(c, 64)),
            height < 0 ? height : Math.min(dp(c, height), c.getResources().getDisplayMetrics().heightPixels - dp(c, 40)));
        decorate(dialog.getWindow().getDecorView());
        if (dialog.getListView() != null && !(dialog.getListView().getAdapter() instanceof MenuAdapter)) {
            android.widget.ListView list = dialog.getListView(); int checked = list.getCheckedItemPosition();
            list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(c, 5));
            list.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 12)); list.setClipToPadding(false);
            list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            list.setAdapter(new MenuAdapter(list.getAdapter()));
            if (checked >= 0) { list.setItemChecked(checked, true); list.setSelection(checked); }
        }
    }
    static AlertDialog.Builder modal(Context context, String title) {
        TextView heading = heading(context, title);
        heading.setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 16));
        return new AlertDialog.Builder(context).setCustomTitle(heading);
    }
    static TextView heading(Context context, String title) {
        TextView text = new TextView(context); text.setText(title); text.setTextColor(Color.WHITE); text.setTextSize(23); text.setTypeface(MEDIUM);
        ControlIcon icon = new ControlIcon(iconFor(title), ACCENT); icon.setBounds(0, 0, dp(context, 24), dp(context, 24));
        text.setCompoundDrawables(icon, null, null, null); text.setCompoundDrawablePadding(dp(context, 12)); return text;
    }
    static String iconFor(String label) {
        String text = label.toLowerCase(java.util.Locale.US);
        if (text.contains("subtit")) return "subtitles";
        if (text.contains("audio") || text.contains("volume") || text.contains("language")) return "volume";
        if (text.contains("quality")) return "quality";
        if (text.contains("retry") || text.contains("check now")) return "retry";
        if (text.contains("beginning")) return "history";
        if (text.contains("next")) return "next";
        if (text.contains("previous")) return "previous";
        if (text.contains("close") || text.equals("cancel")) return "close";
        if (text.equals("back") || text.equals("later") || text.equals("not now")) return "back";
        if (text.contains("web")) return "web";
        if (text.contains("native")) return "screen";
        if (text.contains("source") || text.contains("server")) return "source";
        if (text.contains("darkflix") || text.contains("update") || text.contains("install") || text.contains("version")) return "download";
        if (text.contains("profile")) return "profile";
        if (text.contains("librar")) return "library";
        if (text.contains("history")) return "history";
        if (text.contains("movie") || text.contains("show") || text.equals("browse")) return "browse";
        if (text.contains("home")) return "home";
        if (text.equals("ok") || text.equals("done") || text.equals("save")) return "check";
        return "settings";
    }
    private static final int[][] FOCUS_STATES = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {android.R.attr.state_selected}, {}};
    private static ColorStateList actionColors() { return new ColorStateList(FOCUS_STATES, new int[]{BG, BG, BG, Color.WHITE}); }
    static void menuRow(TextView row, String iconName) {
        Context c = row.getContext(); row.setTextColor(actionColors()); row.setTypeface(MEDIUM); row.setTextSize(16);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); row.setMinHeight(dp(c, 48));
        row.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        StateListDrawable backgrounds = new StateListDrawable();
        for (int i = 0; i < FOCUS_STATES.length; i++) backgrounds.addState(FOCUS_STATES[i], shape(c, i < 3 ? Color.WHITE : 0xff202329, Color.TRANSPARENT));
        row.setBackground(backgrounds);
        ControlIcon icon = new ControlIcon(iconName, Color.WHITE); icon.tint(actionColors());
        icon.setBounds(0, 0, dp(c, 20), dp(c, 20)); row.setCompoundDrawables(icon, null, null, null); row.setCompoundDrawablePadding(dp(c, 12));
        if (row instanceof android.widget.CheckedTextView) ((android.widget.CheckedTextView) row).setCheckMarkTintList(actionColors());
        if (android.os.Build.VERSION.SDK_INT >= 26) row.setDefaultFocusHighlightEnabled(false);
        row.setStateListAnimator(null);
    }
    static Button action(Context c, String text, String icon, Runnable run) {
        Button button = new Button(c); button.setText(text); button.setAllCaps(false); button.setMinWidth(dp(c, 96));
        menuRow(button, icon); button.setOnClickListener(v -> run.run()); return button;
    }
    private static void decorate(View view) {
        if (view instanceof Button) {
            Button b = (Button) view; b.setAllCaps(false); menuRow(b, iconFor(b.getText().toString())); b.setOnFocusChangeListener(null);
        } else if (view instanceof TextView && view.getId() == android.R.id.message) {
            TextView message = (TextView) view; message.setTextColor(TEXT); message.setTextSize(16); message.setLineSpacing(dp(view.getContext(), 3), 1);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) decorate(((ViewGroup) view).getChildAt(i));
    }
    private static final class MenuAdapter extends BaseAdapter {
        final ListAdapter delegate;
        MenuAdapter(ListAdapter delegate) { this.delegate = delegate; }
        public int getCount() { return delegate.getCount(); }
        public Object getItem(int p) { return delegate.getItem(p); }
        public long getItemId(int p) { return delegate.getItemId(p); }
        public boolean isEnabled(int p) { return delegate.isEnabled(p); }
        public View getView(int p, View recycled, ViewGroup parent) {
            View view = delegate.getView(p, recycled, parent);
            if (view instanceof TextView) menuRow((TextView) view, iconFor(((TextView) view).getText().toString()));
            return view;
        }
    }
    static void profile(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("discovery", 0);
        EditText name = new EditText(activity); name.setSingleLine(true); name.setText(prefs.getString("profile", "Guest"));
        name.setSelectAllOnFocus(true); name.setTextColor(Color.WHITE); name.setTextSize(16);
        name.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        AlertDialog profile = modal(activity, "Local profile")
            .setMessage("A name for this device. No online account or sign-in is required.").setView(name)
            .setPositiveButton("Save", (d, w) -> prefs.edit().putString("profile", name.getText().toString().trim()).apply())
            .setNeutralButton("Media credits", (d, w) -> {
                AlertDialog credits = modal(activity, "Media credits")
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
        AlertDialog licenses = modal(activity, "Software licenses")
            .setMessage(text.toString()).setPositiveButton("Done", null).create();
        licenses.show(); dialog(licenses, 600, 460);
    }
}
