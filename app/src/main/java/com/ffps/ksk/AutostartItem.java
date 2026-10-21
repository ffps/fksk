package com.ffps.ksk;

/** Пункт списка автостарта: приложение или команда shell. */
final class AutostartItem {
    static final int APP = 0;
    static final int SHELL = 1;

    int type = APP;
    String target = "";  // пакет приложения или текст команды
    boolean enabled = true;
    int pause = 1;       // пауза перед запуском пункта, сек
    boolean root;        // только для команды shell
}
