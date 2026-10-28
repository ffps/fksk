package com.ffps.fksk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final long TICK_MS = 10000;

    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;

    // настройки
    private String url = "";
    private String schedule = Prefs.DEF_SCHEDULE;
    private int maxRetries = Prefs.DEF_RETRY_COUNT;
    private int retryPause = Prefs.DEF_RETRY_PAUSE;
    private boolean ignoreSsl;
    private boolean pullRefresh = true;

    private String launchApp = "";

    // жесты
    private GestureIcon reloadIcon;
    private GestureIcon homeIcon;
    private int iconSize;
    private float swipeThreshold;
    private float startX;
    private float startY;
    private boolean pullTracking; // свайп вниз -> обновить страницу
    private boolean homeTracking; // свайп вверх в нижней половине -> другое приложение

    // состояние загрузки
    private static boolean bootChecked; // проверка "первый запуск после загрузки" — раз за процесс
    private boolean bootPending;
    private boolean hwAccelApplied;
    private long lastCleanup;
    private int cleanups;
    private boolean started;
    private boolean autoOpenedSettings;
    private int attempt;
    private int gen;
    private boolean loadFailed;
    private boolean showingError;
    private boolean retryPending;

    // расписание
    private long nextReload = -1;
    private long lastTick;

    private final Runnable retryRunnable = () -> {
        retryPending = false;
        loadPage();
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (Math.abs(now - lastTick) > 120000) {
                // системные часы скакнули (например, синхронизация по сети после загрузки)
                nextReload = Schedule.next(schedule, now);
            }
            lastTick = now;
            if (nextReload > 0 && now >= nextReload) {
                nextReload = Schedule.next(schedule, now);
                reloadNow();
            }
            maybeCleanup();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable hideRunnable = this::hideSystemUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!bootChecked) {
            bootChecked = true;
            if (Boot.isNewBoot(this, prefs)) {
                Boot.mark(this, prefs);
                bootPending = true;
            }
        }
        hwAccelApplied = prefs.getBoolean(Prefs.HW_ACCEL, true);
        if (hwAccelApplied) {
            // в манифесте для окна киоска ускорение выключено; включаем его до создания содержимого
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
        lastCleanup = SystemClock.elapsedRealtime();
        if (Prefs.getInt(prefs, Prefs.HEALTH_MINUTES, Prefs.DEF_HEALTH_MINUTES) > 0) {
            writeHealth(bootPending ? "boot_start" : "app_start");
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setupWebView();
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        float density = getResources().getDisplayMetrics().density;
        swipeThreshold = 100 * density;
        iconSize = (int) (72 * density);
        reloadIcon = addGestureIcon(GestureIcon.RELOAD);
        homeIcon = addGestureIcon(GestureIcon.HOME);

        setContentView(root);

        root.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                handler.removeCallbacks(hideRunnable);
                handler.postDelayed(hideRunnable, 2000);
            }
        });
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        // долгое нажатие на экран открывает настройки
        web.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });
        web.setHapticFeedbackEnabled(false);

        // жесты: свайп вниз — обновить, свайп вверх в нижней половине — запустить другое приложение
        web.setOnTouchListener((v, e) -> onWebTouch(e));

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String target) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                if (finishedUrl == null || finishedUrl.startsWith("about:") || finishedUrl.startsWith("data:")) {
                    return;
                }
                // успех засчитываем с небольшой задержкой: на старых WebView порядок
                // onReceivedError / onPageFinished не гарантирован
                final int g = gen;
                handler.postDelayed(() -> {
                    if (g == gen && !loadFailed && !showingError) {
                        attempt = 0;
                    }
                }, 1000);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                onLoadFailed();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
                if (ignoreSsl) {
                    h.proceed();
                } else {
                    h.cancel();
                    onLoadFailed();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean(Prefs.HW_ACCEL, true) != hwAccelApplied) {
            recreate(); // режим отрисовки окна меняется только при его пересоздании
            return;
        }
        web.onResume();

        String oldUrl = url;
        readSettings();

        if (url.length() == 0) {
            showPage(getString(R.string.err_no_url_title), getString(R.string.err_no_url));
            if (!autoOpenedSettings) {
                autoOpenedSettings = true;
                openSettings();
            }
        } else if (!started || !url.equals(oldUrl) || (showingError && !retryPending)) {
            started = true;
            reloadNow();
        }

        long now = System.currentTimeMillis();
        lastTick = now;
        nextReload = Schedule.next(schedule, now);
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);

        hideSystemUi();

        if (bootPending) {
            bootPending = false;
            startAppSequence();
        }

        rescheduleHealth();
    }

    @Override
    protected void onPause() {
        publishHealthState();
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(retryRunnable);
        retryPending = false;
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    private void readSettings() {
        String u = prefs.getString(Prefs.URL, "").trim();
        if (u.equals("http://") || u.equals("https://")) {
            u = "";
        }
        if (u.length() > 0 && !u.contains("://")) {
            u = "http://" + u;
        }
        url = u;
        schedule = prefs.getString(Prefs.SCHEDULE, Prefs.DEF_SCHEDULE).trim();
        maxRetries = Math.max(0, Prefs.getInt(prefs, Prefs.RETRY_COUNT, Prefs.DEF_RETRY_COUNT));
        retryPause = Math.max(1, Prefs.getInt(prefs, Prefs.RETRY_PAUSE, Prefs.DEF_RETRY_PAUSE));
        ignoreSsl = prefs.getBoolean(Prefs.IGNORE_SSL, false);
        pullRefresh = prefs.getBoolean(Prefs.PULL_REFRESH, true);
        launchApp = prefs.getString(Prefs.LAUNCH_APP, "").trim();
    }

    private void reloadNow() {
        handler.removeCallbacks(retryRunnable);
        retryPending = false;
        attempt = 0;
        loadPage();
    }

    private void loadPage() {
        gen++;
        loadFailed = false;
        showingError = false;
        web.loadUrl(url);
    }

    private void onLoadFailed() {
        if (retryPending || showingError) {
            return;
        }
        loadFailed = true;
        attempt++;
        final String title = getString(R.string.err_title);
        if (maxRetries == 0 || attempt <= maxRetries) {
            retryPending = true;
            final String text = url + "\n" + getString(R.string.err_retry,
                    attempt, maxRetries == 0 ? "∞" : String.valueOf(maxRetries), retryPause);
            handler.post(() -> showPage(title, text));
            handler.postDelayed(retryRunnable, retryPause * 1000L);
        } else {
            final String text = getString(R.string.err_giveup, maxRetries);
            handler.post(() -> showPage(title, text));
        }
    }

    private void showPage(String title, String text) {
        showingError = true;
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='background:#000;color:#ddd;font-family:sans-serif;text-align:center;padding:20% 6% 0'>"
                + "<h2>" + TextUtils.htmlEncode(title) + "</h2>"
                + "<p>" + TextUtils.htmlEncode(text).replace("\n", "<br>") + "</p></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            int minutes = Prefs.getInt(prefs, Prefs.HEALTH_MINUTES, Prefs.DEF_HEALTH_MINUTES);
            if (minutes > 0) {
                writeHealth(null);
                handler.postDelayed(this, minutes * 60000L);
            }
        }
    };

    private void rescheduleHealth() {
        handler.removeCallbacks(healthTick);
        if (Prefs.getInt(prefs, Prefs.HEALTH_MINUTES, Prefs.DEF_HEALTH_MINUTES) > 0) {
            handler.postDelayed(healthTick, 3000);
        }
    }

    /** Запись в журнал состояния (CSV в Download): данные собираются и пишутся в фоне. */
    private void writeHealth(String event) {
        publishHealthState();
        HealthLog.recordAsync(getApplicationContext(), event == null ? "" : event);
    }

    /** Передаёт журналу текущее состояние страницы (его же использует экран настроек). */
    private void publishHealthState() {
        HealthLog.setAppState(!showingError, attempt, cleanups, hwAccelApplied);
    }

    private void maybeCleanup() {
        int hours = Prefs.getInt(prefs, Prefs.CLEANUP_HOURS, Prefs.DEF_CLEANUP_HOURS);
        if (hours > 0 && SystemClock.elapsedRealtime() - lastCleanup >= hours * 3600000L) {
            cleanupMemory();
        }
    }

    /** Очистка памяти: сбрасывает кэш, пересоздаёт окно браузера и заново загружает страницу. */
    private void cleanupMemory() {
        lastCleanup = SystemClock.elapsedRealtime();
        cleanups++;
        recreateWebView();
        System.gc();
        if (started && url.length() > 0) {
            reloadNow();
        }
        if (Prefs.getInt(prefs, Prefs.HEALTH_MINUTES, Prefs.DEF_HEALTH_MINUTES) > 0) {
            writeHealth("cleanup_" + cleanups);
        }
    }

    private void recreateWebView() {
        WebView old = web;
        root.removeView(old);
        old.stopLoading();
        old.clearCache(true);
        old.clearHistory();
        old.removeAllViews();
        old.destroy();
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setupWebView();
        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.onResume();
        cancelGestures();
    }

    private GestureIcon addGestureIcon(int type) {
        GestureIcon g = new GestureIcon(this, type);
        g.setVisibility(View.GONE);
        root.addView(g, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        return g;
    }

    /** Показать/скрыть пиктограмму; центр по вертикали — на заданной доле высоты экрана. */
    private void showGestureIcon(GestureIcon g, float heightFraction, boolean show) {
        if (show) {
            g.setY(root.getHeight() * heightFraction - iconSize / 2f);
        }
        g.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void cancelGestures() {
        pullTracking = false;
        homeTracking = false;
        reloadIcon.setVisibility(View.GONE);
        homeIcon.setVisibility(View.GONE);
    }

    private boolean onWebTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = e.getX();
                startY = e.getY();
                pullTracking = pullRefresh && web.getScrollY() == 0;
                homeTracking = launchApp.length() > 0
                        && startY > web.getHeight() / 2f
                        && !web.canScrollVertically(1);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() > 1) {
                    cancelGestures();
                    break;
                }
                float dy = e.getY() - startY;
                boolean vertical = Math.abs(dy) > Math.abs(e.getX() - startX);
                if (pullTracking) {
                    showGestureIcon(reloadIcon, 0.25f, vertical && dy > swipeThreshold);
                }
                if (homeTracking) {
                    showGestureIcon(homeIcon, 0.75f, vertical && -dy > swipeThreshold);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                float dy = e.getY() - startY;
                boolean vertical = Math.abs(dy) > Math.abs(e.getX() - startX);
                if (pullTracking && vertical && dy > swipeThreshold && web.getScrollY() == 0) {
                    cancelGestures();
                    reloadNow();
                } else if (homeTracking && vertical && -dy > swipeThreshold) {
                    cancelGestures();
                    launchOtherApp();
                } else {
                    cancelGestures();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelGestures();
                break;
            default:
                break;
        }
        return false;
    }

    private void launchOtherApp() {
        try {
            startActivity(AppUtil.launchIntent(getPackageManager(), launchApp));
        } catch (Exception ex) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Автостарт: после загрузки системы по очереди выполняет включённые пункты списка (приложение или
     * команда shell), перед каждым ждёт его паузу. После запуска последнего пункта ждёт "Задержку запуска"
     * и возвращает киоск на экран.
     */
    private void startAppSequence() {
        final List<AutostartItem> items = new ArrayList<>();
        for (AutostartItem item : AutostartItems.load(this, prefs)) {
            if (item.enabled) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        final int delay = Math.max(0, Prefs.getInt(prefs, Prefs.LAUNCH_DELAY, Prefs.DEF_LAUNCH_DELAY));
        final int[] state = {0, 0}; // индекс следующего пункта, число запущенных без ошибки
        final Runnable done = () -> {
            prefs.edit().putString(Prefs.LAST_APPS,
                    System.currentTimeMillis() + "|" + state[1] + "|" + items.size()).commit();
            Intent back = new Intent(this, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(back);
            } catch (Exception ignored) {
                // на Android 10+ без разрешения "Поверх других окон" система может не пустить
            }
        };
        final Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (state[0] >= items.size()) {
                // "Задержка запуска" — от старта последнего пункта (для ожидаемой команды — от её завершения)
                handler.postDelayed(done, delay * 1000L);
                return;
            }
            final AutostartItem item = items.get(state[0]);
            handler.postDelayed(() -> ItemRunner.run(this, item, item.waitDone, null, ok -> {
                if (ok) {
                    state[1]++;
                }
                state[0]++;
                next[0].run();
            }), item.pause * 1000L);
        };
        handler.postDelayed(next[0], 1000);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!showingError && web.canGoBack()) {
                web.goBack();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        root.setSystemUiVisibility(flags);
    }
}
