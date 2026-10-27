package com.okonu.flipflix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import android.content.res.XmlResourceParser;
import org.xmlpull.v1.XmlPullParser;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Render the packaged artwork, including the launcher mask, rather than a mockup. */
@RunWith(AndroidJUnit4.class)
public final class LauncherArtworkTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Bitmap render(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }
    private boolean red(int pixel) { return Color.alpha(pixel) > 200 && Color.red(pixel) > 180 && Color.green(pixel) < 35 && Color.blue(pixel) < 45; }
    private void save(Bitmap image, String name) throws Exception {
        File directory = new File(context().getFilesDir(), "icon-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    private List<String> paths(int resource) throws Exception {
        List<String> result = new ArrayList<>();
        try (XmlResourceParser xml = context().getResources().getXml(resource)) {
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.getEventType() == XmlPullParser.START_TAG && "path".equals(xml.getName()))
                    result.add(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "pathData"));
            }
        }
        return result;
    }

    @Test public void launcherArtUsesExactlyTheApprovedStartupPaths() throws Exception {
        List<String> mark = new ArrayList<>();
        for (int resource : new int[]{R.drawable.startup_stem, R.drawable.startup_frame, R.drawable.startup_lower, R.drawable.startup_upper})
            mark.addAll(paths(resource));
        String assembled = android.text.TextUtils.join(" ", mark);
        assertEquals(assembled, paths(R.drawable.app_icon_foreground).get(0));
        assertEquals(assembled, paths(R.drawable.tv_banner).get(1));
        assertEquals(android.text.TextUtils.join(" ", paths(R.drawable.startup_wordmark)), paths(R.drawable.tv_banner).get(2));
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    public void manifestUsesAdaptiveRedOnBlackWithoutWhiteWrapper() throws Exception {
        Drawable icon = context().getPackageManager().getApplicationIcon(context().getApplicationInfo());
        assertTrue(icon instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
        assertEquals(Color.BLACK, render(adaptive.getBackground(), 32, 32).getPixel(16, 16));
        Bitmap image = render(icon, 512, 512);
        int count = 0, minX = 512, maxX = 0, minY = 512, maxY = 0;
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            int pixel = image.getPixel(x, y);
            if (Color.alpha(pixel) > 200) assertTrue("No white or old blue background", Color.green(pixel) < 35 && Color.blue(pixel) < 45);
            if (red(pixel)) { count++; minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
        assertTrue("Emblem should be substantial, not a tiny image inside an icon", count > 30000);
        assertEquals(511, minX + maxX, 4); assertEquals(511, minY + maxY, 4);
        assertTrue(maxY - minY > 320 && maxY - minY < 365);
        save(image, "app-icon.png");
        if (Build.VERSION.SDK_INT >= 33) assertNotNull(adaptive.getMonochrome());
    }

    @Test public void foregroundFitsCircularSafeZoneAndHasTransparentSurroundings() throws Exception {
        Bitmap image = render(context().getDrawable(R.drawable.app_icon_foreground), 1080, 1080);
        int count = 0;
        for (int y = 0; y < 1080; y++) for (int x = 0; x < 1080; x++) {
            if (Color.alpha(image.getPixel(x, y)) > 128) {
                count++;
                assertTrue("Every part of the approved mark must survive circular launcher masks", Math.hypot(x + .5 - 540, y + .5 - 540) <= 330);
            }
        }
        assertTrue(count > 90000); assertEquals(0, Color.alpha(image.getPixel(0, 0)));
    }

    @Test public void tvBannerKeepsFullLogoAndWordmarkOnPureBlack() throws Exception {
        Drawable banner = context().getPackageManager().getApplicationBanner(context().getApplicationInfo());
        assertNotNull(banner);
        assertEquals(16f / 9f, (float) banner.getIntrinsicWidth() / banner.getIntrinsicHeight(), .001f);
        Bitmap image = render(banner, 640, 360);
        int red = 0, text = 0;
        for (int y = 0; y < 360; y++) for (int x = 0; x < 640; x++) {
            int pixel = image.getPixel(x, y);
            assertEquals(255, Color.alpha(pixel));
            if (x < 20 || x >= 620 || y < 20 || y >= 340) assertEquals(Color.BLACK, pixel);
            if (red(pixel)) red++;
            if (y > 270 && Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200) text++;
        }
        assertTrue(red > 20000); assertTrue("TV banner includes the vector wordmark", text > 1500);
        save(image, "tv-banner.png");
    }
}
