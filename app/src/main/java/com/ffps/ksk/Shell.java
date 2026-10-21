package com.ffps.ksk;

import java.io.IOException;
import java.io.InputStream;

final class Shell {
    private Shell() {
    }

    /**
     * Запускает команду в фоне и не ждёт её завершения. Бросает IOException, если запустить не удалось
     * (например, нет su при включённой опции root).
     */
    static void run(String command, boolean root) throws IOException {
        String[] argv = root ? new String[]{"su", "-c", command} : new String[]{"sh", "-c", command};
        final Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getOutputStream().close();
        Thread reader = new Thread(() -> {
            try {
                InputStream in = process.getInputStream();
                byte[] buf = new byte[1024];
                while (in.read(buf) >= 0) {
                    // вывод отбрасываем, чтобы процесс не завис на заполненном буфере
                }
                process.waitFor();
            } catch (Exception ignored) {
                // команда завершилась или поток закрыт
            }
        });
        reader.setDaemon(true);
        reader.start();
    }
}
