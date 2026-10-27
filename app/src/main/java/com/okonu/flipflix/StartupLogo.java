package com.okonu.flipflix;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Original geometric paths, rendered by Android at the actual display size. */
final class StartupLogo extends FrameLayout {
    private static final float[] START_X = {-32f, 32f, 0f, 0f};
    private static final float[] START_Y = {0f, 0f, 32f, -32f};
    final FrameLayout lockup;
    final ImageView[] pieces = new ImageView[5];
    private long positionMs;

    StartupLogo(Context context) {
        super(context);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        lockup = new FrameLayout(context);
        lockup.setClipChildren(false);
        addView(lockup, new LayoutParams(1, 1, Gravity.CENTER));
        int[] resources = {R.drawable.startup_stem, R.drawable.startup_frame,
            R.drawable.startup_lower, R.drawable.startup_upper, R.drawable.startup_wordmark};
        for (int i = 0; i < pieces.length; i++) {
            ImageView piece = new ImageView(context);
            piece.setImageResource(resources[i]);
            piece.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            piece.setAlpha(0f);
            lockup.addView(piece, new LayoutParams(-1, -1));
            pieces[i] = piece;
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        int height = Math.round(Math.min(h * .62f, w * .6f));
        int width = Math.round(height * 800f / 720f);
        // Size children before measuring them. Changing their params during
        // onSizeChanged leaves the first layout at the 1px placeholder size.
        if (lockup.getLayoutParams().width != width || lockup.getLayoutParams().height != height)
            lockup.setLayoutParams(new LayoutParams(width, height, Gravity.CENTER));
        super.onMeasure(widthSpec, heightSpec);
        render(positionMs);
    }

    void render(long elapsedMs) {
        positionMs = elapsedMs;
        float unit = lockup.getLayoutParams().width / 800f;
        float opacity = easedProgress(elapsedMs, 900f, 2000f);
        // All pieces share a single easing curve, settling on the audio impact.
        float distance = 1f - easedProgress(elapsedMs, 1000f, 3200f);
        for (int i = 0; i < 4; i++) {
            pieces[i].setAlpha(opacity);
            pieces[i].setTranslationX(START_X[i] * unit * distance);
            pieces[i].setTranslationY(START_Y[i] * unit * distance);
        }
        float wordmark = easedProgress(elapsedMs, 4200f, 1500f);
        pieces[4].setAlpha(wordmark);
        pieces[4].setTranslationY(10f * unit * (1f - wordmark));
        // The containing overlay crossfades into Home; never fade into baked-in black.
    }

    private static float easedProgress(float elapsedMs, float startMs, float durationMs) {
        float progress = Math.max(0f, Math.min(1f, (elapsedMs - startMs) / durationMs));
        return (float) (.5 - .5 * Math.cos(Math.PI * progress));
    }

    void release() {
        // VectorDrawable can cache a full-size raster internally. Do not retain
        // five display-size caches while the viewer browses or plays a movie.
        for (ImageView piece : pieces) piece.setImageDrawable(null);
        lockup.removeAllViews();
    }
}
