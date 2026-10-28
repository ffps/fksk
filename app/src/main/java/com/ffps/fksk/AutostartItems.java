package com.ffps.fksk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Хранение списка автостарта (JSON в настройках). */
final class AutostartItems {
    private static final Random RANDOM = new Random();

    private AutostartItems() {
    }

    static long newId() {
        long id;
        do {
            id = RANDOM.nextLong() & Long.MAX_VALUE;
        } while (id == 0);
        return id;
    }

    static List<AutostartItem> load(Context context, SharedPreferences p) {
        List<AutostartItem> list = new ArrayList<>();
        String json = p.getString(Prefs.AUTOSTART_ITEMS, null);
        if (json == null) {
            migrateLegacy(context, p, list);
            return list;
        }
        boolean dirty = false;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AutostartItem it = new AutostartItem();
                it.id = o.optLong("i", 0);
                it.type = "sh".equals(o.optString("t")) ? AutostartItem.SHELL : AutostartItem.APP;
                it.target = o.optString("c", "");
                it.enabled = o.optBoolean("e", true);
                it.pause = Math.max(0, o.optInt("p", 1));
                it.root = o.optBoolean("r", false);
                it.waitDone = o.optBoolean("w", false);
                if (it.id == 0) {
                    it.id = newId(); // пункты прежней версии: выдаём идентификатор
                    dirty = true;
                }
                list.add(it);
            }
        } catch (Exception e) {
            list.clear(); // запись повреждена — считаем список пустым
            return list;
        }
        if (dirty) {
            save(p, list);
        }
        return list;
    }

    static void save(SharedPreferences p, List<AutostartItem> items) {
        JSONArray arr = new JSONArray();
        try {
            for (AutostartItem it : items) {
                JSONObject o = new JSONObject();
                o.put("i", it.id);
                o.put("t", it.type == AutostartItem.SHELL ? "sh" : "app");
                o.put("c", it.target);
                o.put("e", it.enabled);
                o.put("p", it.pause);
                o.put("r", it.root);
                o.put("w", it.waitDone);
                arr.put(o);
            }
        } catch (Exception e) {
            return;
        }
        p.edit().putString(Prefs.AUTOSTART_ITEMS, arr.toString()).commit();
    }

    static boolean hasEnabled(Context context, SharedPreferences p) {
        for (AutostartItem it : load(context, p)) {
            if (it.enabled) {
                return true;
            }
        }
        return false;
    }

    /** Перенос из прежних настроек: набор приложений и общая пауза между запусками. */
    private static void migrateLegacy(Context context, SharedPreferences p, List<AutostartItem> out) {
        Set<String> legacy = p.getStringSet(Prefs.AUTOSTART_APPS, null);
        if (legacy != null && !legacy.isEmpty()) {
            int pause = Math.max(0, Prefs.getInt(p, Prefs.APPS_PAUSE, Prefs.DEF_APPS_PAUSE));
            for (String[] app : AppUtil.listApps(context)) { // по алфавиту названий, как раньше
                if (legacy.contains(app[1])) {
                    AutostartItem it = new AutostartItem();
                    it.id = newId();
                    it.target = app[1];
                    it.pause = pause;
                    out.add(it);
                }
            }
        }
        save(p, out);
        p.edit().remove(Prefs.AUTOSTART_APPS).remove(Prefs.APPS_PAUSE).commit();
    }
}
