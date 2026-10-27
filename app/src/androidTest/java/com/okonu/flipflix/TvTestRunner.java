package com.okonu.flipflix;

import android.app.Activity;
import android.content.Intent;
import androidx.test.runner.AndroidJUnitRunner;

/** Most regressions exercise browsing/playback; intro tests explicitly opt in. */
public final class TvTestRunner extends AndroidJUnitRunner {
    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (name.equals(MainActivity.class.getName())) {
            intent.putExtra("skip_startup_intro", !intent.getBooleanExtra("test_show_startup_intro", false));
        }
        return super.newActivity(loader, name, intent);
    }
}
