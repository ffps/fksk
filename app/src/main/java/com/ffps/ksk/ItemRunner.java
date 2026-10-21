package com.ffps.ksk;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** Выполняет пункт автостарта и сохраняет результат (время, код, журнал). Вызывать из главного потока. */
final class ItemRunner {
    static final int TIMEOUT_SEC = 300; // предел ожидания завершения команды

    private static final String KEY_PREFIX = "autostart_res_";
    private static final Set<Long> RUNNING = Collections.synchronizedSet(new HashSet<Long>());

    private ItemRunner() {
    }

    static final class Result {
        long time;
        boolean running;
        boolean ok;
        Integer code;
        String message = "";
        String log = "";

        Result copy() {
            Result r = new Result();
            r.time = time;
            r.running = running;
            r.ok = ok;
            r.code = code;
            r.message = message;
            r.log = log;
            return r;
        }
    }

    /** Вызывается в главном потоке при каждом изменении состояния. */
    interface Listener {
        void onUpdate(Result r);
    }

    /** Вызывается один раз, когда можно переходить к следующему пункту. */
    interface Proceed {
        void go(boolean ok);
    }

    static final class Handle {
        volatile boolean finished;
        volatile Shell.Task task;

        void cancel() {
            Shell.Task t = task;
            if (t != null) {
                t.cancel();
            }
        }
    }

    /**
     * wait: для команды shell — ждать завершения (proceed вызывается после него), иначе сразу после запуска.
     * Для приложения параметр не используется.
     */
    static Handle run(Context context, final AutostartItem item, final boolean wait,
                      final Listener listener, final Proceed proceed) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        final Handle handle = new Handle();
        final Result r = new Result();
        r.time = System.currentTimeMillis();

        if (item.type != AutostartItem.SHELL) {
            try {
                context.startActivity(AppUtil.launchIntent(app.getPackageManager(), item.target));
                r.ok = true;
                r.message = app.getString(R.string.res_app_ok);
            } catch (Exception e) {
                r.message = app.getString(R.string.res_app_error, e.getClass().getSimpleName());
            }
            handle.finished = true;
            publish(prefs, item.id, r, listener);
            if (proceed != null) {
                proceed.go(r.ok);
            }
            return handle;
        }

        r.running = true;
        r.message = app.getString(R.string.res_running);
        RUNNING.add(item.id);
        publish(prefs, item.id, r, listener);

        final boolean[] flags = {false, false}; // [0] завершено, [1] proceed уже вызван
        handle.task = Shell.run(item.target, item.root, wait ? TIMEOUT_SEC : 0, new Shell.Listener() {
            @Override
            public void onStarted() {
                if (!wait && !flags[1]) {
                    flags[1] = true;
                    if (proceed != null) {
                        proceed.go(true);
                    }
                }
            }

            @Override
            public void onLog(String log) {
                if (flags[0]) {
                    return;
                }
                r.log = log;
                notifyListener(listener, r);
            }

            @Override
            public void onFinished(Integer code, String log, int status, String detail) {
                flags[0] = true;
                RUNNING.remove(item.id);
                r.running = false;
                r.log = log;
                r.code = code;
                switch (status) {
                    case Shell.CANCELLED:
                        r.ok = false;
                        r.message = app.getString(R.string.res_cancelled);
                        break;
                    case Shell.TIMEOUT:
                        r.ok = false;
                        r.message = app.getString(R.string.res_timeout, TIMEOUT_SEC);
                        break;
                    case Shell.ERROR:
                        r.ok = false;
                        r.message = app.getString(R.string.res_start_error, detail);
                        break;
                    default:
                        r.ok = code != null && code == 0;
                        r.message = app.getString(R.string.res_code, code);
                        break;
                }
                handle.finished = true;
                publish(prefs, item.id, r, listener);
                if (!flags[1]) {
                    flags[1] = true;
                    if (proceed != null) {
                        proceed.go(r.ok);
                    }
                }
            }
        });
        return handle;
    }

    private static void publish(SharedPreferences prefs, long id, Result r, Listener listener) {
        saveResult(prefs, id, r);
        notifyListener(listener, r);
    }

    private static void notifyListener(Listener listener, Result r) {
        if (listener != null) {
            listener.onUpdate(r.copy());
        }
    }

    static void saveResult(SharedPreferences p, long id, Result r) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", r.time);
            o.put("r", r.running);
            o.put("o", r.ok);
            if (r.code != null) {
                o.put("c", r.code.intValue());
            }
            o.put("m", r.message);
            o.put("l", r.log);
            p.edit().putString(KEY_PREFIX + id, o.toString()).commit();
        } catch (Exception ignored) {
            // результат не сохранился — не критично
        }
    }

    static Result loadResult(Context context, SharedPreferences p, long id) {
        String s = p.getString(KEY_PREFIX + id, null);
        if (s == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(s);
            Result r = new Result();
            r.time = o.optLong("t");
            r.running = o.optBoolean("r");
            r.ok = o.optBoolean("o");
            r.code = o.has("c") ? Integer.valueOf(o.getInt("c")) : null;
            r.message = o.optString("m", "");
            r.log = o.optString("l", "");
            if (r.running && !RUNNING.contains(id)) {
                r.running = false; // процесс принадлежал прежнему запуску приложения
                r.ok = false;
                r.message = context.getString(R.string.res_interrupted);
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    static void clearResult(SharedPreferences p, long id) {
        p.edit().remove(KEY_PREFIX + id).commit();
    }

    static String describe(Context context, Result r, boolean shortForm) {
        if (r == null) {
            return context.getString(R.string.never_run);
        }
        DateFormat f = shortForm
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                : DateFormat.getDateTimeInstance();
        return context.getString(R.string.last_run, f.format(new Date(r.time)), r.message);
    }
}
