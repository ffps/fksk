package com.ffps.fksk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Определяет, что киоск запустился в первый раз после загрузки системы. */
final class Boot {
    private Boot() {
    }

    static boolean isNewBoot(Context context, SharedPreferences prefs) {
        String last = prefs.getString(Prefs.LAST_BOOT_ID, "");
        String id = bootId(context);
        if (id != null) {
            return !id.equals(last);
        }
        // запасной вариант: оценка времени загрузки по часам, допуск 5 минут на подстройку времени
        long estimate = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (last.startsWith("est:")) {
            try {
                return Math.abs(estimate - Long.parseLong(last.substring(4))) > 5 * 60 * 1000L;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return true;
    }

    static void mark(Context context, SharedPreferences prefs) {
        String id = bootId(context);
        if (id == null) {
            id = "est:" + (System.currentTimeMillis() - SystemClock.elapsedRealtime());
        }
        prefs.edit().putString(Prefs.LAST_BOOT_ID, id).commit();
    }

    /** Уникальный признак текущей загрузки или null, если прочитать его нельзя. */
    private static String bootId(Context context) {
        String id = readFirstLine("/proc/sys/kernel/random/boot_id");
        if (id != null) {
            return id;
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return "bc" + Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (Exception e) {
                // значения нет
            }
        }
        return null;
    }

    private static String readFirstLine(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(path));
            String line = reader.readLine();
            return line == null || line.trim().length() == 0 ? null : line.trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // закрытие не удалось — не критично
                }
            }
        }
    }
}
