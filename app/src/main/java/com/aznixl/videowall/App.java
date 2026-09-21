package com.aznixl.videowall;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 应用入口。
 *
 * 只干一件事：按用户设置决定明暗模式。
 *
 * 注意 MainActivity 在 manifest 里声明了 configChanges 含 uiMode ——
 * 这是为了**不让系统到点切深色时把正在播的视频打断**。
 * 代价是用户在设置里改明暗后，系统不会再自动重建 Activity，
 * 所以那种情况由 MainActivity / SettingsActivity 自己在 onResume 里比对并 recreate()。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(nightModeOf(new Prefs(this).themeMode()));
    }

    /** 把应用自己的主题档位映射到 AppCompat 的明暗模式。 */
    public static int nightModeOf(int themeMode) {
        switch (themeMode) {
            case Prefs.THEME_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case Prefs.THEME_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }
}
