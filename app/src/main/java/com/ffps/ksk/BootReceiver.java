package com.ffps.ksk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;

import java.util.Set;

public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_LAUNCH = "com.ffps.ksk.LAUNCH";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        String action = intent == null ? "" : String.valueOf(intent.getAction());

        if (ACTION_LAUNCH.equals(action)) {
            // сработал таймер задержки
            updateResult(p, launch(context));
            return;
        }

        String result;
        if (!p.getBoolean(Prefs.AUTOSTART, true)) {
            result = context.getString(R.string.boot_res_off);
        } else {
            int delay = Math.max(0, Prefs.getInt(p, Prefs.LAUNCH_DELAY, Prefs.DEF_LAUNCH_DELAY));
            boolean hasApps = AutostartItems.hasEnabled(context, p);
            // с автостартом приложений паузу перед открытием киоска отсчитывает сам киоск
            if (delay == 0 || hasApps) {
                result = launch(context);
            } else {
                schedule(context, delay);
                result = context.getString(R.string.boot_res_wait, delay);
            }
        }
        // след для диагностики: виден в настройках под галочкой автозапуска
        p.edit().putString(Prefs.LAST_BOOT, System.currentTimeMillis() + "|" + action + "|" + result).commit();
    }

    private static String launch(Context context) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            return context.getString(R.string.boot_res_error, e.getClass().getSimpleName());
        }
        return context.getString(R.string.boot_res_started, overlayState(context));
    }

    private static String overlayState(Context context) {
        if (Build.VERSION.SDK_INT < 29) {
            return context.getString(R.string.overlay_state_na);
        }
        return context.getString(Settings.canDrawOverlays(context)
                ? R.string.overlay_state_yes : R.string.overlay_state_no);
    }

    private static void schedule(Context context, int delaySec) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, BootReceiver.class);
        i.setAction(ACTION_LAUNCH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, 1, i, flags);
        long at = SystemClock.elapsedRealtime() + delaySec * 1000L;
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    private static void updateResult(SharedPreferences p, String result) {
        String[] parts = p.getString(Prefs.LAST_BOOT, "").split("\\|", 3);
        String head = parts.length >= 3
                ? parts[0] + "|" + parts[1]
                : System.currentTimeMillis() + "|" + ACTION_LAUNCH;
        p.edit().putString(Prefs.LAST_BOOT, head + "|" + result).commit();
    }
}
