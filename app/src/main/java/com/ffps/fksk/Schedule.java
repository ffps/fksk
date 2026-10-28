package com.ffps.fksk;

import java.util.Calendar;

/**
 * Расписание перезагрузки: либо число минут ("15"), либо crontab из 5 полей
 * (минута час день_месяца месяц день_недели). Поддерживаются *, списки (1,2),
 * диапазоны (1-5) и шаги (* /5, 10-30/5).
 */
final class Schedule {
    private final boolean[] min = new boolean[60];
    private final boolean[] hour = new boolean[24];
    private final boolean[] dom = new boolean[32];
    private final boolean[] mon = new boolean[13];
    private final boolean[] dow = new boolean[8];
    private final boolean domStar;
    private final boolean dowStar;

    private Schedule(String[] f) {
        fill(f[0], 0, 59, min);
        fill(f[1], 0, 23, hour);
        fill(f[2], 1, 31, dom);
        fill(f[3], 1, 12, mon);
        fill(f[4], 0, 7, dow);
        if (dow[7]) {
            dow[0] = true;
        }
        domStar = f[2].startsWith("*");
        dowStar = f[4].startsWith("*");
    }

    private static void fill(String field, int lo, int hi, boolean[] out) {
        for (String part : field.split(",")) {
            if (part.length() == 0) {
                throw new IllegalArgumentException();
            }
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(part.substring(slash + 1));
                range = part.substring(0, slash);
                if (step <= 0) {
                    throw new IllegalArgumentException();
                }
            }
            int a;
            int b;
            if (range.equals("*")) {
                a = lo;
                b = hi;
            } else {
                int dash = range.indexOf('-');
                if (dash >= 0) {
                    a = Integer.parseInt(range.substring(0, dash));
                    b = Integer.parseInt(range.substring(dash + 1));
                } else {
                    a = Integer.parseInt(range);
                    b = slash >= 0 ? hi : a;
                }
            }
            if (a < lo || b > hi || a > b) {
                throw new IllegalArgumentException();
            }
            for (int v = a; v <= b; v += step) {
                out[v] = true;
            }
        }
    }

    private static Schedule parseCron(String expr) {
        String[] f = expr.trim().split("\\s+");
        if (f.length != 5) {
            return null;
        }
        try {
            return new Schedule(f);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isMinutes(String s) {
        return s.matches("\\d{1,6}");
    }

    /** Пустая строка допустима (расписание выключено). */
    static boolean isValid(String spec) {
        String s = spec == null ? "" : spec.trim();
        return s.length() == 0 || isMinutes(s) || parseCron(s) != null;
    }

    /** Время следующей перезагрузки строго после nowMs или -1, если расписания нет. */
    static long next(String spec, long nowMs) {
        String s = spec == null ? "" : spec.trim();
        if (s.length() == 0) {
            return -1;
        }
        if (isMinutes(s)) {
            long m = Long.parseLong(s);
            return m > 0 ? nowMs + m * 60000L : -1;
        }
        Schedule c = parseCron(s);
        return c == null ? -1 : c.nextAfter(nowMs);
    }

    private long nextAfter(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, 1);
        long limit = nowMs + 5L * 366L * 86400000L;
        while (c.getTimeInMillis() < limit) {
            if (!mon[c.get(Calendar.MONTH) + 1]) {
                c.set(Calendar.DAY_OF_MONTH, 1);
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.add(Calendar.MONTH, 1);
                continue;
            }
            if (!dayMatches(c)) {
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.add(Calendar.DAY_OF_MONTH, 1);
                continue;
            }
            if (!hour[c.get(Calendar.HOUR_OF_DAY)]) {
                c.set(Calendar.MINUTE, 0);
                c.add(Calendar.HOUR_OF_DAY, 1);
                continue;
            }
            if (!min[c.get(Calendar.MINUTE)]) {
                c.add(Calendar.MINUTE, 1);
                continue;
            }
            return c.getTimeInMillis();
        }
        return -1;
    }

    private boolean dayMatches(Calendar c) {
        boolean d = dom[c.get(Calendar.DAY_OF_MONTH)];
        boolean w = dow[c.get(Calendar.DAY_OF_WEEK) - 1];
        if (domStar || dowStar) {
            return d && w;
        }
        return d || w; // как в классическом cron: если заданы оба поля, достаточно любого
    }
}
