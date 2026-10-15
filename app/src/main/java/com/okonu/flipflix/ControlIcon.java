package com.okonu.flipflix;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small native control glyphs, drawn at the device's density. */
final class ControlIcon extends Drawable {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ControlIcon(String name, int color) {
        this.name = name;
        paint.setColor(color);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void color(int color) { paint.setColor(color); invalidateSelf(); }

    private void line(Canvas canvas, float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, paint);
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setStyle(Paint.Style.STROKE);
        if (name.equals("play")) {
            paint.setStyle(Paint.Style.FILL);
            line(canvas, 7, 4, 21, 12, 7, 20, 7, 4);
        } else if (name.equals("search")) {
            canvas.drawCircle(10, 10, 6.5f, paint);
            line(canvas, 15, 15, 21, 21);
        } else if (name.equals("home")) {
            line(canvas, 3, 11, 12, 3, 21, 11);
            line(canvas, 6, 9, 6, 21, 18, 21, 18, 9);
        } else if (name.equals("back")) {
            line(canvas, 14, 5, 7, 12, 14, 19);
        } else if (name.equals("next") || name.equals("previous")) {
            if (name.equals("previous")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
            paint.setStyle(Paint.Style.FILL);
            line(canvas, 5, 5, 16, 12, 5, 19, 5, 5);
            paint.setStyle(Paint.Style.STROKE);
            line(canvas, 19, 5, 19, 19);
        } else if (name.equals("source")) {
            canvas.drawRoundRect(3, 3, 17, 14, 1, 1, paint);
            line(canvas, 7, 18, 21, 18, 21, 7);
        } else if (name.equals("retry")) {
            canvas.drawArc(4, 4, 20, 20, 40, 285, false, paint);
            line(canvas, 20, 3, 20, 9, 14, 9);
        } else if (name.equals("fullscreen")) {
            line(canvas, 3, 9, 3, 3, 9, 3);
            line(canvas, 15, 3, 21, 3, 21, 9);
            line(canvas, 21, 15, 21, 21, 15, 21);
            line(canvas, 9, 21, 3, 21, 3, 15);
        }
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
