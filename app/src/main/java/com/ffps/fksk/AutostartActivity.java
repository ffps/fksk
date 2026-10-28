package com.ffps.fksk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Редактор списка автостарта: приложения и команды shell, порядок, пауза, ожидание, тест и журнал. */
public class AutostartActivity extends Activity {
    private SharedPreferences prefs;
    private List<AutostartItem> items;
    private List<String[]> apps; // {название, пакет}
    private ItemAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_autostart);
        setTitle(R.string.pref_autostart_apps_title);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        apps = AppUtil.listApps(this);
        items = AutostartItems.load(this, prefs);

        ListView list = (ListView) findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty));
        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showEditor(position));
        findViewById(R.id.add).setOnClickListener(v -> showEditor(-1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.notifyDataSetChanged(); // результаты запусков могли обновиться
    }

    private void persist() {
        AutostartItems.save(prefs, items);
        adapter.notifyDataSetChanged();
    }

    private void move(int position, int delta) {
        int target = position + delta;
        if (target < 0 || target >= items.size()) {
            return;
        }
        Collections.swap(items, position, target);
        persist();
    }

    private String labelOf(String pkg) {
        for (String[] app : apps) {
            if (app[1].equals(pkg)) {
                return app[0];
            }
        }
        return pkg + getString(R.string.not_found_suffix);
    }

    private String titleOf(AutostartItem it) {
        return it.type == AutostartItem.SHELL ? it.target : labelOf(it.target);
    }

    private String subtitleOf(AutostartItem it) {
        StringBuilder sb = new StringBuilder(getString(
                it.type == AutostartItem.SHELL ? R.string.type_shell : R.string.type_app));
        if (it.type == AutostartItem.SHELL) {
            if (it.root) {
                sb.append(" · ").append(getString(R.string.use_root_short));
            }
            if (it.waitDone) {
                sb.append(" · ").append(getString(R.string.wait_short));
            }
        }
        sb.append(" · ").append(getString(R.string.pause_short, it.pause));
        ItemRunner.Result last = ItemRunner.loadResult(this, prefs, it.id);
        if (last != null) {
            sb.append("\n").append(ItemRunner.describe(this, last, true));
        }
        return sb.toString();
    }

    /** Поля окна редактирования пункта. */
    private final class Form {
        final View view;
        final RadioButton rbApp;
        final RadioButton rbShell;
        final Spinner spApp;
        final EditText etCommand;
        final EditText etPause;
        final EditText log;
        final CheckBox cbRoot;
        final CheckBox cbWait;
        final TextView lastRun;
        final TextView logLabel;
        final Button test;
        final Button copy;
        final List<String> pkgs = new ArrayList<>();

        Form(AutostartItem it) {
            view = getLayoutInflater().inflate(R.layout.dialog_autostart_item, null);
            RadioGroup typeGroup = (RadioGroup) view.findViewById(R.id.type);
            rbApp = (RadioButton) view.findViewById(R.id.type_app);
            rbShell = (RadioButton) view.findViewById(R.id.type_shell);
            spApp = (Spinner) view.findViewById(R.id.app_spinner);
            etCommand = (EditText) view.findViewById(R.id.command);
            etPause = (EditText) view.findViewById(R.id.pause);
            log = (EditText) view.findViewById(R.id.log);
            cbRoot = (CheckBox) view.findViewById(R.id.root);
            cbWait = (CheckBox) view.findViewById(R.id.wait_done);
            lastRun = (TextView) view.findViewById(R.id.last_run);
            logLabel = (TextView) view.findViewById(R.id.log_label);
            test = (Button) view.findViewById(R.id.test);
            copy = (Button) view.findViewById(R.id.copy_log);

            List<String> labels = new ArrayList<>();
            for (String[] app : apps) {
                labels.add(app[0]);
                pkgs.add(app[1]);
            }
            if (it.type == AutostartItem.APP && it.target.length() > 0 && !pkgs.contains(it.target)) {
                labels.add(labelOf(it.target)); // приложение удалено: показываем пакет
                pkgs.add(it.target);
            }
            ArrayAdapter<String> spinnerAdapter =
                    new ArrayAdapter<>(AutostartActivity.this, android.R.layout.simple_spinner_item, labels);
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spApp.setAdapter(spinnerAdapter);
            int selected = pkgs.indexOf(it.target);
            if (selected >= 0) {
                spApp.setSelection(selected);
            }
            etCommand.setText(it.type == AutostartItem.SHELL ? it.target : "");
            cbRoot.setChecked(it.root);
            cbWait.setChecked(it.waitDone);
            etPause.setText(String.valueOf(it.pause));
            if (it.type == AutostartItem.SHELL) {
                rbShell.setChecked(true);
            } else {
                rbApp.setChecked(true);
            }

            // журнал: только чтение, но с выделением и копированием
            log.setKeyListener(null);
            log.setTextIsSelectable(true);
            log.setCursorVisible(false);
            log.setOnTouchListener((v, e) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true); // прокрутка внутри окна
                return false;
            });

            typeGroup.setOnCheckedChangeListener((group, checkedId) -> syncVisibility());
            syncVisibility();
            showResult(ItemRunner.loadResult(AutostartActivity.this, prefs, it.id));
        }

        void syncVisibility() {
            boolean shell = rbShell.isChecked();
            int shellOnly = shell ? View.VISIBLE : View.GONE;
            spApp.setVisibility(shell ? View.GONE : View.VISIBLE);
            etCommand.setVisibility(shellOnly);
            cbRoot.setVisibility(shellOnly);
            cbWait.setVisibility(shellOnly);
            logLabel.setVisibility(shellOnly);
            log.setVisibility(shellOnly);
            copy.setVisibility(shellOnly);
        }

        void showResult(ItemRunner.Result r) {
            lastRun.setText(ItemRunner.describe(AutostartActivity.this, r, false));
            log.setText(r == null ? "" : r.log);
            log.setSelection(log.length());
        }

        /** Читает форму; при ошибке ввода показывает сообщение и возвращает null. */
        AutostartItem read() {
            AutostartItem item = new AutostartItem();
            boolean shell = rbShell.isChecked();
            if (shell) {
                item.target = etCommand.getText().toString().trim();
                if (item.target.length() == 0) {
                    Toast.makeText(AutostartActivity.this, R.string.toast_need_command, Toast.LENGTH_LONG).show();
                    return null;
                }
            } else {
                int pos = spApp.getSelectedItemPosition();
                if (pos < 0 || pos >= pkgs.size()) {
                    Toast.makeText(AutostartActivity.this, R.string.toast_need_app, Toast.LENGTH_LONG).show();
                    return null;
                }
                item.target = pkgs.get(pos);
            }
            int pause;
            try {
                pause = Integer.parseInt(etPause.getText().toString().trim());
            } catch (NumberFormatException e) {
                pause = -1;
            }
            if (pause < 0 || pause > 3600) {
                Toast.makeText(AutostartActivity.this, R.string.toast_bad_number, Toast.LENGTH_LONG).show();
                return null;
            }
            item.type = shell ? AutostartItem.SHELL : AutostartItem.APP;
            item.root = shell && cbRoot.isChecked();
            item.waitDone = shell && cbWait.isChecked();
            item.pause = pause;
            return item;
        }
    }

    private void showEditor(final int index) {
        final boolean isNew = index < 0;
        final AutostartItem it;
        if (isNew) {
            it = new AutostartItem();
            it.id = AutostartItems.newId();
        } else {
            it = items.get(index);
        }
        final Form form = new Form(it);
        final ItemRunner.Handle[] running = {null};
        final boolean[] saved = {false};

        // тест: выполняет пункт с текущими значениями формы (даже несохранёнными), для команды — с ожиданием
        form.test.setOnClickListener(v -> {
            if (running[0] != null && !running[0].finished) {
                running[0].cancel();
                return;
            }
            AutostartItem trial = form.read();
            if (trial == null) {
                return;
            }
            trial.id = it.id;
            running[0] = ItemRunner.run(this, trial, true, r -> {
                if (form.view.getWindowToken() == null) {
                    return; // окно уже закрыто
                }
                form.showResult(r);
                form.test.setText(r.running ? R.string.stop : R.string.test);
            }, null);
        });
        form.copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_label), form.log.getText().toString()));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isNew ? R.string.item_new : R.string.item_edit)
                .setView(form.view)
                .setPositiveButton(R.string.save, null) // обработчик ниже: окно не закрывается при ошибке ввода
                .setNegativeButton(R.string.cancel, null);
        if (!isNew) {
            builder.setNeutralButton(R.string.delete, (d, which) -> {
                items.remove(index);
                ItemRunner.clearResult(prefs, it.id);
                persist();
            });
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            AutostartItem edited = form.read();
            if (edited == null) {
                return;
            }
            it.type = edited.type;
            it.target = edited.target;
            it.root = edited.root;
            it.waitDone = edited.waitDone;
            it.pause = edited.pause;
            if (isNew) {
                items.add(it);
            }
            saved[0] = true;
            persist();
            dialog.dismiss();
        }));
        dialog.setOnDismissListener(d -> {
            if (running[0] != null && !running[0].finished) {
                running[0].cancel(); // не оставляем тестовую команду работать после закрытия окна
            }
            if (isNew && !saved[0]) {
                ItemRunner.clearResult(prefs, it.id);
            }
            adapter.notifyDataSetChanged();
        });
        dialog.show();
    }

    private class ItemAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View v = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_autostart, parent, false);
            final AutostartItem it = items.get(position);

            CheckBox enabled = (CheckBox) v.findViewById(R.id.enabled);
            enabled.setOnCheckedChangeListener(null);
            enabled.setChecked(it.enabled);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                it.enabled = checked;
                AutostartItems.save(prefs, items);
            });

            ((TextView) v.findViewById(R.id.title)).setText(titleOf(it));
            ((TextView) v.findViewById(R.id.sub)).setText(subtitleOf(it));

            Button up = (Button) v.findViewById(R.id.up);
            up.setEnabled(position > 0);
            up.setOnClickListener(x -> move(position, -1));
            Button down = (Button) v.findViewById(R.id.down);
            down.setEnabled(position < items.size() - 1);
            down.setOnClickListener(x -> move(position, 1));
            return v;
        }
    }
}
