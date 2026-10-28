package com.ffps.fksk;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/** Запуск команды shell в фоне: потоковый вывод, остановка, таймаут. */
final class Shell {
    static final int DONE = 0;
    static final int CANCELLED = 1;
    static final int TIMEOUT = 2;
    static final int ERROR = 3;

    private static final int LOG_LIMIT = 16000;

    private Shell() {
    }

    static final class Task {
        volatile Process process;
        volatile boolean cancelled;
        volatile boolean timedOut;

        void cancel() {
            cancelled = true;
            Process p = process;
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** Все методы вызываются в главном потоке. */
    interface Listener {
        void onStarted();

        void onLog(String log);

        /** code == null, если процесс запустить не удалось (тогда detail — текст ошибки). */
        void onFinished(Integer code, String log, int status, String detail);
    }

    /** timeoutSec == 0 — без ограничения по времени. Вызывать из главного потока. */
    static Task run(final String command, final boolean root, final int timeoutSec, final Listener listener) {
        final Task task = new Task();
        final Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> execute(command, root, timeoutSec, listener, task, main));
        worker.setDaemon(true);
        worker.start();
        return task;
    }

    private static void execute(String command, boolean root, int timeoutSec, final Listener listener,
                                final Task task, final Handler main) {
        final Process proc;
        try {
            String[] argv = root ? new String[]{"su", "-c", command} : new String[]{"sh", "-c", command};
            proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (final IOException e) {
            final String detail = String.valueOf(e.getMessage());
            main.post(() -> listener.onFinished(null, "", ERROR, detail));
            return;
        }
        task.process = proc;
        if (task.cancelled) {
            proc.destroy();
        }
        try {
            proc.getOutputStream().close(); // stdin не нужен
        } catch (IOException ignored) {
            // не критично
        }
        main.post(listener::onStarted);

        final StringBuilder log = new StringBuilder();
        Thread reader = new Thread(() -> {
            long lastPost = 0;
            try {
                Reader in = new InputStreamReader(proc.getInputStream(), "UTF-8");
                char[] buf = new char[1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    final String snapshot;
                    synchronized (log) {
                        log.append(buf, 0, n);
                        if (log.length() > LOG_LIMIT) {
                            log.delete(0, log.length() - LOG_LIMIT);
                        }
                        snapshot = log.toString();
                    }
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPost >= 250) {
                        lastPost = now;
                        main.post(() -> listener.onLog(snapshot));
                    }
                }
            } catch (IOException ignored) {
                // канал закрыт после завершения или остановки процесса
            }
        });
        reader.setDaemon(true);
        reader.start();

        final Runnable timeoutKill = () -> {
            task.timedOut = true;
            proc.destroy();
        };
        if (timeoutSec > 0) {
            main.postDelayed(timeoutKill, timeoutSec * 1000L);
        }

        int exit;
        try {
            exit = proc.waitFor();
        } catch (InterruptedException e) {
            exit = -1;
        }
        main.removeCallbacks(timeoutKill);
        try {
            reader.join(700); // фоновые потомки команды могут держать канал вывода открытым
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final String finalLog;
        synchronized (log) {
            finalLog = log.toString();
        }
        final int code = exit;
        final int status = task.cancelled ? CANCELLED : task.timedOut ? TIMEOUT : DONE;
        main.post(() -> listener.onFinished(code, finalLog, status, null));
    }
}
