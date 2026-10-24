package com.ffps.ksk;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class SettingsActivity extends PreferenceActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        addPreferencesFromResource(R.xml.preferences);
        populateApps((ListPreference) findPreference(Prefs.LAUNCH_APP));

        findPreference(Prefs.SCHEDULE).setOnPreferenceChangeListener(
                (p, v) -> check(Schedule.isValid(str(v)), R.string.toast_bad_schedule));
        findPreference(Prefs.RETRY_COUNT).setOnPreferenceChangeListener(
                (p, v) -> check(inRange(str(v), 0, 9999), R.string.toast_bad_number));
        findPreference(Prefs.RETRY_PAUSE).setOnPreferenceChangeListener(
                (p, v) -> check(inRange(str(v), 1, 86400), R.string.toast_bad_number));
        findPreference(Prefs.LAUNCH_DELAY).setOnPreferenceChangeListener(
                (p, v) -> check(inRange(str(v), 0, 600), R.string.toast_bad_number));
        findPreference(Prefs.AUTOSTART_LIST).setOnPreferenceClickListener(p -> {
            startActivity(new Intent(this, AutostartActivity.class));
            return true;
        });
        findPreference(Prefs.CLEANUP_HOURS).setOnPreferenceChangeListener(
                (p, v) -> check(inRange(str(v), 0, 720), R.string.toast_bad_number));
        findPreference(Prefs.HEALTH_MINUTES).setOnPreferenceChangeListener(
                (p, v) -> check(inRange(str(v), 0, 1440), R.string.toast_bad_number));
        findPreference(Prefs.HEALTH_VIEW).setOnPreferenceClickListener(p -> {
            startActivity(new Intent(this, HealthLogActivity.class));
            return true;
        });
        findPreference(Prefs.OVERLAY).setOnPreferenceClickListener(p -> {
            openOverlaySettings();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        refreshSummaries();
    }

    @Override
    protected void onPause() {
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        refreshSummaries();
    }

    private void refreshSummaries() {
        SharedPreferences sp = getPreferenceScreen().getSharedPreferences();
        String url = sp.getString(Prefs.URL, "").trim();
        boolean noUrl = url.length() == 0 || url.equals("http://") || url.equals("https://");
        findPreference(Prefs.URL).setSummary(noUrl ? getString(R.string.not_set) : url);
        String count = sp.getString(Prefs.RETRY_COUNT, String.valueOf(Prefs.DEF_RETRY_COUNT)).trim();
        findPreference(Prefs.RETRY_COUNT).setSummary(
                count.equals("0") ? getString(R.string.retry_unlimited) : count);
        findPreference(Prefs.RETRY_PAUSE).setSummary(
                sp.getString(Prefs.RETRY_PAUSE, String.valueOf(Prefs.DEF_RETRY_PAUSE)));
        String sch = sp.getString(Prefs.SCHEDULE, Prefs.DEF_SCHEDULE).trim();
        findPreference(Prefs.SCHEDULE).setSummary(sch.length() == 0 ? getString(R.string.schedule_off) : sch);
        findPreference(Prefs.AUTOSTART).setSummary(bootSummary(sp.getString(Prefs.LAST_BOOT, "")));
        findPreference(Prefs.AUTOSTART_LIST).setSummary(itemsSummary(sp));
        findPreference(Prefs.LAUNCH_DELAY).setSummary(
                sp.getString(Prefs.LAUNCH_DELAY, String.valueOf(Prefs.DEF_LAUNCH_DELAY)));
        ListPreference app = (ListPreference) findPreference(Prefs.LAUNCH_APP);
        CharSequence appName = app.getEntry();
        boolean noApp = appName == null || app.getValue() == null || app.getValue().length() == 0;
        app.setSummary(getString(R.string.pref_launch_app_summary,
                noApp ? getString(R.string.not_set) : appName.toString()));
        Preference overlay = findPreference(Prefs.OVERLAY);
        if (Build.VERSION.SDK_INT < 29) {
            overlay.setEnabled(false);
            overlay.setSummary(R.string.overlay_na);
        } else {
            overlay.setSummary(Settings.canDrawOverlays(this) ? R.string.overlay_on : R.string.overlay_off);
        }
        int hours = Prefs.getInt(sp, Prefs.CLEANUP_HOURS, Prefs.DEF_CLEANUP_HOURS);
        findPreference(Prefs.CLEANUP_HOURS).setSummary(
                hours <= 0 ? getString(R.string.off) : getString(R.string.every_hours, hours));
        int minutes = Prefs.getInt(sp, Prefs.HEALTH_MINUTES, Prefs.DEF_HEALTH_MINUTES);
        findPreference(Prefs.HEALTH_MINUTES).setSummary(
                minutes <= 0 ? getString(R.string.off) : getString(R.string.every_minutes, minutes));
        long logSize = HealthLog.size(this);
        findPreference(Prefs.HEALTH_VIEW).setSummary(logSize <= 0
                ? getString(R.string.log_empty_short)
                : getString(R.string.log_size_kb, (logSize + 1023) / 1024));
    }

    private void populateApps(ListPreference lp) {
        List<String[]> apps = AppUtil.listApps(this);
        CharSequence[] entries = new CharSequence[apps.size() + 1];
        CharSequence[] values = new CharSequence[apps.size() + 1];
        entries[0] = getString(R.string.not_set);
        values[0] = "";
        for (int i = 0; i < apps.size(); i++) {
            entries[i + 1] = apps.get(i)[0];
            values[i + 1] = apps.get(i)[1];
        }
        lp.setEntries(entries);
        lp.setEntryValues(values);
    }

    private String itemsSummary(SharedPreferences sp) {
        List<AutostartItem> items = AutostartItems.load(this, sp);
        if (items.isEmpty()) {
            return getString(R.string.not_set);
        }
        int enabled = 0;
        for (AutostartItem it : items) {
            if (it.enabled) {
                enabled++;
            }
        }
        StringBuilder sb = new StringBuilder(getString(R.string.items_count, items.size(), enabled));
        String[] last = sp.getString(Prefs.LAST_APPS, "").split("\\|", 3);
        if (last.length == 3) {
            try {
                String when = DateFormat.getDateTimeInstance().format(new Date(Long.parseLong(last[0])));
                sb.append("\n").append(getString(R.string.apps_last, when, last[1], last[2]));
            } catch (RuntimeException ignored) {
                // запись повреждена — не показываем
            }
        }
        return sb.toString();
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.overlay_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String bootSummary(String raw) {
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) {
            return getString(R.string.boot_never);
        }
        try {
            String when = DateFormat.getDateTimeInstance().format(new Date(Long.parseLong(parts[0])));
            String action = parts[1].substring(parts[1].lastIndexOf('.') + 1);
            return getString(R.string.boot_last, when, action, parts[2]);
        } catch (RuntimeException e) {
            return getString(R.string.boot_never);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static boolean inRange(String s, int lo, int hi) {
        try {
            int n = Integer.parseInt(s);
            return n >= lo && n <= hi;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean check(boolean ok, int errorRes) {
        if (!ok) {
            Toast.makeText(this, errorRes, Toast.LENGTH_LONG).show();
        }
        return ok;
    }
}
