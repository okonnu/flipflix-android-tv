package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App-owned recovery UI. Never covers a healthy provider's controls. */
final class PlaybackFeedback extends FrameLayout {
    private final String title, source;
    private final Runnable retry, change, back, wait;
    private Button first;
    private boolean failure;

    PlaybackFeedback(Context context, String title, String source, Runnable retry, Runnable change, Runnable back, Runnable wait) {
        super(context);
        this.title = title; this.source = source; this.retry = retry; this.change = change; this.back = back; this.wait = wait;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        loading();
    }
    private int dp(float n) { return AppStyle.dp(getContext(), n); }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(getContext()); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(bold ? AppStyle.MEDIUM : AppStyle.REGULAR); return v;
    }
    private void space(LinearLayout into, int height) { into.addView(new View(getContext()), new LinearLayout.LayoutParams(1, dp(height))); }
    void loading() {
        animate().cancel(); setAlpha(1); failure = false; first = null; removeAllViews(); setBackgroundColor(Color.TRANSPARENT);
        TextView status = label("Connecting to " + source + "...  Back or Menu for options.", 14, AppStyle.TEXT, false);
        status.setPadding(dp(18), dp(12), dp(18), dp(12));
        status.setBackground(AppStyle.shape(getContext(), 0xe6101218, Color.TRANSPARENT));
        LayoutParams p = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT); p.leftMargin = dp(40); p.bottomMargin = dp(28);
        addView(status, p); setVisibility(VISIBLE);
    }
    void hide() { animate().cancel(); failure = false; setVisibility(GONE); }
    boolean failed() { return failure && getVisibility() == VISIBLE; }
    void focusAction() { if (failed() && first != null && !hasFocus()) first.requestFocus(); }

    void error(String heading, String explanation, String code, boolean canWait) {
        animate().cancel(); failure = true; removeAllViews(); setVisibility(VISIBLE); setAlpha(1);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff13161d, 0xff050608, Color.BLACK}));
        TextView brand = label("DARKFLIX", 17, AppStyle.ACCENT, true); brand.setLetterSpacing(.13f);
        LayoutParams brandParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        brandParams.leftMargin = dp(48); brandParams.topMargin = dp(32); addView(brand, brandParams);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL);
        TextView context = label(source.toUpperCase(java.util.Locale.US) + "  /  " + title, 13, AppStyle.MUTED, true);
        context.setMaxLines(2); context.setEllipsize(android.text.TextUtils.TruncateAt.END); body.addView(context);
        space(body, 18);
        View accent = new View(getContext()); accent.setBackgroundColor(AppStyle.ACCENT); body.addView(accent, new LinearLayout.LayoutParams(dp(32), dp(3)));
        space(body, 18);
        body.addView(label(heading, 30, Color.WHITE, true)); space(body, 12);
        TextView message = label(explanation, 17, AppStyle.TEXT, false); message.setLineSpacing(dp(3), 1); body.addView(message);
        space(body, 26);
        LinearLayout actions = new LinearLayout(getContext()); actions.setOrientation(LinearLayout.HORIZONTAL);
        first = action(actions, "Retry", "retry", retry);
        action(actions, "Change source", "source", change);
        action(actions, "Back", "back", back);
        body.addView(actions);
        if (canWait) {
            space(body, 10);
            Button keep = AppStyle.action(getContext(), "Return to player", "play", wait);
            body.addView(keep, new LinearLayout.LayoutParams(dp(230), dp(44)));
        }
        space(body, 20);
        body.addView(label(code + "  |  Your saved progress is kept.", 12, AppStyle.MUTED, false));
        int width = Math.min(dp(680), getResources().getDisplayMetrics().widthPixels - dp(96));
        LayoutParams bp = new LayoutParams(width, -2, Gravity.CENTER_VERTICAL | Gravity.LEFT); bp.leftMargin = dp(48); addView(body, bp);
        focusAction();
        if (StartupIntro.motionEnabled(getContext())) { setAlpha(0); animate().alpha(1).setDuration(220).start(); }
    }
    private Button action(LinearLayout into, String text, String icon, Runnable callback) {
        Button button = AppStyle.action(getContext(), text, icon, callback);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(48)); p.rightMargin = dp(10);
        into.addView(button, p); return button;
    }
}
