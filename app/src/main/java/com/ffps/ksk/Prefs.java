package com.ffps.ksk;

import android.content.SharedPreferences;

final class Prefs {
    static final String URL = "url";
    static final String RETRY_COUNT = "retry_count";
    static final String RETRY_PAUSE = "retry_pause";
    static final String SCHEDULE = "schedule";
    static final String AUTOSTART = "autostart";
    static final String IGNORE_SSL = "ignore_ssl";
    static final String LAST_BOOT = "last_boot_signal";
    static final String LAUNCH_DELAY = "launch_delay";
    static final String PULL_REFRESH = "pull_refresh";
    static final String OVERLAY = "overlay";
    static final String LAUNCH_APP = "launch_app";
    static final String AUTOSTART_APPS = "autostart_apps"; // прежний формат, нужен для переноса
    static final String AUTOSTART_ITEMS = "autostart_items";
    static final String AUTOSTART_LIST = "autostart_list";
    static final String APPS_PAUSE = "apps_pause";
    static final String LAST_APPS = "last_apps_run";
    static final String LAST_BOOT_ID = "last_boot_id";

    static final String DEF_SCHEDULE = "59 * * * *";
    static final int DEF_RETRY_COUNT = 0;
    static final int DEF_RETRY_PAUSE = 10;
    static final int DEF_LAUNCH_DELAY = 10;
    static final int DEF_APPS_PAUSE = 1;

    private Prefs() {
    }

    static int getInt(SharedPreferences p, String key, int def) {
        try {
            return Integer.parseInt(p.getString(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
