package com.ffps.fksk;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал состояния устройства в формате CSV: файл fksk.log в папке Download (заголовок и далее строки значений).
 * Android 10+: через MediaStore, ниже — обычным файлом (на Android 6-9 нужно разрешение на запись).
 * Если папка Download недоступна, пишем в папку приложения.
 */
final class HealthLog {
    static final String FILE_NAME = "fksk.log";
    static final String HEADER = "time,uptime_s,ram_free_mb,ram_total_mb,low_mem,app_pss_mb,java_heap_mb,"
            + "native_heap_mb,bat_pct,bat_mv,bat_temp_c,plugged,bat_status,net,wifi_rssi_dbm,"
            + "page_ok,attempts,cleanups,hw_accel,event";

    private static final long MAX_BYTES = 1024 * 1024;

    private static volatile boolean statePageOk = true;
    private static volatile int stateAttempts;
    private static volatile int stateCleanups;
    private static volatile boolean stateHwAccel = true;

    private HealthLog() {
    }

    /** Состояние страницы и приложения для очередной строки (обновляется из MainActivity). */
    static void setAppState(boolean pageOk, int attempts, int cleanups, boolean hwAccel) {
        statePageOk = pageOk;
        stateAttempts = attempts;
        stateCleanups = cleanups;
        stateHwAccel = hwAccel;
    }

    /** Записывает строку в фоне; если файла нет или он пуст — сначала заголовок. */
    static void recordAsync(Context context, final String event) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> record(app, event));
        t.setDaemon(true);
        t.start();
    }

    static synchronized void record(Context context, String event) {
        try {
            Store store = open(context);
            long size = store.size();
            if (size > MAX_BYTES) {
                rotate(store);
                size = store.size();
            }
            StringBuilder out = new StringBuilder();
            if (size == 0) {
                out.append(HEADER).append('\n');
            }
            out.append(row(context, event)).append('\n');
            store.append(out.toString());
        } catch (Exception ignored) {
            // нет места или доступа — журнал не критичен
        }
        new File(context.getFilesDir(), "health.log").delete(); // файл прежней версии
    }

    /** Оставляет заголовок и последние ~60% строк. */
    private static void rotate(Store store) throws IOException {
        String[] lines = store.readTail((int) (MAX_BYTES * 2)).split("\n");
        int start = Math.max(1, lines.length * 2 / 5);
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = start; i < lines.length; i++) {
            if (lines[i].length() > 0) {
                sb.append(lines[i]).append('\n');
            }
        }
        store.replace(sb.toString());
    }

    static synchronized String readTail(Context context, int maxBytes) {
        try {
            return open(context).readTail(maxBytes);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear(Context context) {
        try {
            open(context).delete();
        } catch (Exception ignored) {
            // не удалось — оставляем как есть
        }
    }

    static long size(Context context) {
        try {
            return open(context).size();
        } catch (Exception e) {
            return 0;
        }
    }

    static String location(Context context) {
        return open(context).describe();
    }

    // ---------- строка CSV ----------

    private static String row(Context context, String event) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        sb.append(',').append(SystemClock.elapsedRealtime() / 1000);
        String memory;
        try {
            memory = memoryFields(context);
        } catch (Exception e) {
            memory = ",,,,,,";
        }
        sb.append(memory);
        String battery;
        try {
            battery = batteryFields(context);
        } catch (Exception e) {
            battery = ",,,,,";
        }
        sb.append(battery);
        String network;
        try {
            network = networkFields(context);
        } catch (Exception e) {
            network = ",,";
        }
        sb.append(network);
        sb.append(',').append(statePageOk ? 1 : 0)
                .append(',').append(stateAttempts)
                .append(',').append(stateCleanups)
                .append(',').append(stateHwAccel ? 1 : 0);
        sb.append(',').append(event == null ? "" : event.replace(',', ';').replace('\n', ' '));
        return sb.toString();
    }

    /** 6 полей: ram_free_mb, ram_total_mb, low_mem, app_pss_mb, java_heap_mb, native_heap_mb. */
    private static String memoryFields(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int pssKb = am.getProcessMemoryInfo(new int[]{android.os.Process.myPid()})[0].getTotalPss();
        Runtime rt = Runtime.getRuntime();
        return "," + (mi.availMem >> 20) + "," + (mi.totalMem >> 20) + "," + (mi.lowMemory ? 1 : 0)
                + "," + (pssKb >> 10) + "," + ((rt.totalMemory() - rt.freeMemory()) >> 20)
                + "," + (Debug.getNativeHeapAllocatedSize() >> 20);
    }

    /** 5 полей: bat_pct, bat_mv, bat_temp_c, plugged, bat_status (пустые, если батареи нет). */
    private static String batteryFields(Context context) {
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null || !b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            return ",,,,,";
        }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        return "," + (scale > 0 ? level * 100 / scale : -1) + "," + mv
                + "," + String.format(Locale.US, "%.1f", temp / 10.0)
                + "," + (plugged == 0 ? "none" : plugged == 1 ? "ac" : plugged == 2 ? "usb" : "other")
                + "," + statusName(status);
    }

    private static String statusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "not_charging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "full";
            default:
                return "unknown";
        }
    }

    /** 2 поля: net, wifi_rssi_dbm. */
    private static String networkFields(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) {
            return ",none,";
        }
        String rssi = "";
        if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                rssi = String.valueOf(wi.getRssi());
            }
        }
        return "," + ni.getTypeName().replace(',', ';') + "," + rssi;
    }

    // ---------- хранилище ----------

    private interface Store {
        long size() throws IOException;

        String readTail(int maxBytes) throws IOException;

        void append(String text) throws IOException;

        void replace(String text) throws IOException;

        void delete() throws IOException;

        String describe();
    }

    private static Store open(Context context) {
        if (Build.VERSION.SDK_INT >= 29) {
            return new MediaStoreStore(context);
        }
        File dir = null;
        boolean mounted = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        boolean permitted = Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (mounted && permitted) {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                dir = null;
            }
        }
        if (dir == null) { // нет доступа к Download: папка приложения (видна по USB)
            dir = mounted ? context.getExternalFilesDir(null) : null;
        }
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return new FileStore(new File(dir, FILE_NAME));
    }

    private static String trimToLines(String s, boolean cutAtStart) {
        if (cutAtStart) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                return s.substring(nl + 1);
            }
        }
        return s;
    }

    private static void syncQuietly(FileOutputStream out) {
        try {
            out.getFD().sync(); // данные должны дойти до флеш-памяти до возможного зависания
        } catch (IOException ignored) {
            // не все файловые системы это поддерживают
        }
    }

    private static final class FileStore implements Store {
        private final File file;

        FileStore(File file) {
            this.file = file;
        }

        @Override
        public long size() {
            return file.length();
        }

        @Override
        public String readTail(int maxBytes) throws IOException {
            if (!file.exists()) {
                return "";
            }
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                long len = raf.length();
                int n = (int) Math.min(maxBytes, len);
                byte[] buf = new byte[n];
                raf.seek(len - n);
                raf.readFully(buf);
                return trimToLines(new String(buf, "UTF-8"), len > n);
            } finally {
                raf.close();
            }
        }

        @Override
        public void append(String text) throws IOException {
            write(text, true);
        }

        @Override
        public void replace(String text) throws IOException {
            write(text, false);
        }

        private void write(String text, boolean append) throws IOException {
            FileOutputStream out = new FileOutputStream(file, append);
            try {
                out.write(text.getBytes("UTF-8"));
                out.flush();
                syncQuietly(out);
            } finally {
                out.close();
            }
        }

        @Override
        public void delete() {
            file.delete();
        }

        @Override
        public String describe() {
            return file.getAbsolutePath();
        }
    }

    /** Android 10+: файл в Download через MediaStore (доступен только создавший его установке приложения). */
    private static final class MediaStoreStore implements Store {
        private final ContentResolver cr;

        MediaStoreStore(Context context) {
            this.cr = context.getContentResolver();
        }

        private Uri find() {
            Cursor c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/"}, null);
            if (c == null) {
                return null;
            }
            try {
                if (c.moveToFirst()) {
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                }
            } finally {
                c.close();
            }
            return null;
        }

        private Uri findOrCreate() throws IOException {
            Uri u = find();
            if (u != null) {
                return u;
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) {
                throw new IOException("MediaStore insert failed");
            }
            return u;
        }

        @Override
        public long size() {
            Uri u = find();
            if (u == null) {
                return 0;
            }
            Cursor c = cr.query(u, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null);
            if (c == null) {
                return 0;
            }
            try {
                return c.moveToFirst() ? c.getLong(0) : 0;
            } finally {
                c.close();
            }
        }

        @Override
        public String readTail(int maxBytes) throws IOException {
            Uri u = find();
            if (u == null) {
                return "";
            }
            InputStream in = cr.openInputStream(u);
            if (in == null) {
                return "";
            }
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                byte[] all = bos.toByteArray();
                int start = Math.max(0, all.length - maxBytes);
                return trimToLines(new String(all, start, all.length - start, "UTF-8"), start > 0);
            } finally {
                in.close();
            }
        }

        private void write(String text, String mode) throws IOException {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(findOrCreate(), mode);
            if (pfd == null) {
                throw new IOException("no file descriptor");
            }
            try {
                FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
                out.write(text.getBytes("UTF-8"));
                out.flush();
                syncQuietly(out);
            } finally {
                pfd.close();
            }
        }

        @Override
        public void append(String text) throws IOException {
            write(text, "wa");
        }

        @Override
        public void replace(String text) throws IOException {
            write(text, "wt");
        }

        @Override
        public void delete() {
            Uri u = find();
            if (u != null) {
                cr.delete(u, null, null);
            }
        }

        @Override
        public String describe() {
            return Environment.DIRECTORY_DOWNLOADS + "/" + FILE_NAME;
        }
    }
}
