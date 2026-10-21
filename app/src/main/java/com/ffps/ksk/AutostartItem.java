package com.ffps.ksk;

/** Пункт списка автостарта: приложение или команда shell. */
final class AutostartItem {
    static final int APP = 0;
    static final int SHELL = 1;

    long id;             // нужен, чтобы хранить результат запуска отдельно от порядка в списке
    int type = APP;
    String target = "";  // пакет приложения или текст команды
    boolean enabled = true;
    int pause = 1;       // пауза перед запуском пункта, сек
    boolean root;        // только для команды shell
    boolean waitDone;    // только для команды shell: ждать завершения перед следующим пунктом
}
