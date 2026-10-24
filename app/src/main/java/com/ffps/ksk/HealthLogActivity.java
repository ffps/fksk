package com.ffps.ksk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

/** Просмотр журнала состояния: прокрутка, выделение, копирование. */
public class HealthLogActivity extends Activity {
    private static final int MAX_BYTES = 60000;

    private EditText text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health);
        setTitle(R.string.health_title);

        text = (EditText) findViewById(R.id.health_text);
        text.setKeyListener(null); // только чтение, но с выделением
        text.setTextIsSelectable(true);
        text.setCursorVisible(false);

        findViewById(R.id.refresh).setOnClickListener(v -> load());
        findViewById(R.id.copy_all).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.health_title),
                    HealthLog.readTail(this, MAX_BYTES)));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.clear).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.health_clear_q)
                .setPositiveButton(R.string.clear, (d, which) -> {
                    HealthLog.clear(this);
                    load();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        load();
    }

    private void load() {
        String s = HealthLog.readTail(this, MAX_BYTES);
        text.setText(s.length() == 0 ? getString(R.string.health_empty) : s);
        text.setSelection(text.length()); // показываем самые свежие записи
    }
}
