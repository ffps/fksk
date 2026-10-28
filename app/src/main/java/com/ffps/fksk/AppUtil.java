package com.ffps.fksk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AppUtil {
    private AppUtil() {
    }

    /**
     * Установленные приложения как {название, пакет}, по алфавиту: обычные (LAUNCHER) и
     * экраны "домой" (HOME, например Launcher3), кроме самого киоска.
     */
    static List<String[]> listApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] categories = {Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_HOME};
        for (String category : categories) {
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) {
                    continue;
                }
                apps.add(new String[]{String.valueOf(ri.loadLabel(pm)), pkg});
            }
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return apps;
    }

    /** Команда запуска приложения; у лаунчера нет категории LAUNCHER, его открываем как HOME. */
    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(pkg);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
