package com.ffps.ksk;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Журнал состояния устройства: одна строка на запись, файл во внутренней памяти приложения. */
final class HealthLog {
    private static final String FILE = "health.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final long KEEP_BYTES = 128 * 1024;

    private HealthLog() {
    }

    /** Собирает строку состояния; можно вызывать из фонового потока. */
    static String snapshot(Context context, String appState) {
        StringBuilder sb = new StringBuilder();
        sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        sb.append(" | работа ").append(formatUptime(SystemClock.elapsedRealtime() / 1000));
        try {
            memory(context, sb);
        } catch (Exception e) {
            sb.append(" | память: ошибка");
        }
        try {
            battery(context, sb);
        } catch (Exception e) {
            sb.append(" | батарея: ошибка");
        }
        try {
            network(context, sb);
        } catch (Exception e) {
            sb.append(" | сеть: ошибка");
        }
        if (appState != null && appState.length() > 0) {
            sb.append(" | ").append(appState);
        }
        return sb.toString();
    }

    private static void memory(Context context, StringBuilder sb) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        sb.append(" | ОЗУ свободно ").append(mi.availMem >> 20).append('/').append(mi.totalMem >> 20).append(" МБ");
        if (mi.lowMemory) {
            sb.append(" (МАЛО ПАМЯТИ)");
        }
        int pssKb = am.getProcessMemoryInfo(new int[]{android.os.Process.myPid()})[0].getTotalPss();
        Runtime rt = Runtime.getRuntime();
        sb.append(" | приложение: PSS ").append(pssKb >> 10)
                .append(" МБ, java ").append((rt.totalMemory() - rt.freeMemory()) >> 20)
                .append(" МБ, native ").append(Debug.getNativeHeapAllocatedSize() >> 20).append(" МБ");
    }

    private static void battery(Context context, StringBuilder sb) {
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null || !b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            sb.append(" | батарея: нет");
            return;
        }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        sb.append(" | батарея ").append(scale > 0 ? level * 100 / scale : -1).append("% ")
                .append(String.format(Locale.US, "%.2f В %.1f °C", mv / 1000.0, temp / 10.0))
                .append(", питание: ")
                .append(plugged == 0 ? "от батареи" : plugged == 1 ? "сеть" : plugged == 2 ? "USB" : "другое")
                .append(", ").append(statusName(status));
    }

    private static String statusName(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "заряд";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "разряд";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "не заряжается";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "полная";
            default:
                return "статус ?";
        }
    }

    private static void network(Context context, StringBuilder sb) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) {
            sb.append(" | сеть: нет");
            return;
        }
        sb.append(" | сеть: ").append(ni.getTypeName());
        if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                sb.append(' ').append(wi.getRssi()).append(" дБм");
            }
        }
    }

    private static String formatUptime(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format(Locale.US, "%dд %02d:%02d:%02d", d, h, m, s);
    }

    /** Дописывает строку и сбрасывает её на флеш-память, чтобы она пережила внезапное зависание. */
    static synchronized void append(Context context, String line) {
        File f = new File(context.getFilesDir(), FILE);
        try {
            if (f.length() > MAX_BYTES) {
                trim(f);
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((line + "\n").getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // нет места или файл недоступен — журнал не критичен
        }
    }

    private static void trim(File f) throws IOException {
        byte[] tail;
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            int n = (int) Math.min(KEEP_BYTES, len);
            tail = new byte[n];
            raf.seek(len - n);
            raf.readFully(tail);
        } finally {
            raf.close();
        }
        int start = 0;
        for (int i = 0; i < tail.length; i++) { // отбрасываем обрезанную первую строку
            if (tail[i] == '\n') {
                start = i + 1;
                break;
            }
        }
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(tail, start, tail.length - start);
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static synchronized String readTail(Context context, int maxBytes) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) {
            return "";
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                long len = raf.length();
                int n = (int) Math.min(maxBytes, len);
                byte[] buf = new byte[n];
                raf.seek(len - n);
                raf.readFully(buf);
                String s = new String(buf, "UTF-8");
                if (len > n) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0) {
                        s = s.substring(nl + 1);
                    }
                }
                return s;
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    static synchronized void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
    }

    static long size(Context context) {
        return new File(context.getFilesDir(), FILE).length();
    }
}
