package com.ffps.ksk;

import android.app.Activity;
import android.app.AlertDialog;
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

/** Редактор списка автостарта: приложения и команды shell, порядок, пауза, включение. */
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
        if (it.type == AutostartItem.SHELL && it.root) {
            sb.append(" · ").append(getString(R.string.use_root_short));
        }
        sb.append(" · ").append(getString(R.string.pause_short, it.pause));
        return sb.toString();
    }

    private void showEditor(final int index) {
        final boolean isNew = index < 0;
        final AutostartItem it = isNew ? new AutostartItem() : items.get(index);

        View view = getLayoutInflater().inflate(R.layout.dialog_autostart_item, null);
        final RadioGroup typeGroup = (RadioGroup) view.findViewById(R.id.type);
        final RadioButton rbApp = (RadioButton) view.findViewById(R.id.type_app);
        final RadioButton rbShell = (RadioButton) view.findViewById(R.id.type_shell);
        final Spinner spApp = (Spinner) view.findViewById(R.id.app_spinner);
        final EditText etCommand = (EditText) view.findViewById(R.id.command);
        final CheckBox cbRoot = (CheckBox) view.findViewById(R.id.root);
        final EditText etPause = (EditText) view.findViewById(R.id.pause);

        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        for (String[] app : apps) {
            labels.add(app[0]);
            pkgs.add(app[1]);
        }
        if (it.type == AutostartItem.APP && it.target.length() > 0 && !pkgs.contains(it.target)) {
            labels.add(labelOf(it.target)); // приложение удалено: показываем пакет
            pkgs.add(it.target);
        }
        ArrayAdapter<String> spinnerAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spApp.setAdapter(spinnerAdapter);
        int selected = pkgs.indexOf(it.target);
        if (selected >= 0) {
            spApp.setSelection(selected);
        }
        etCommand.setText(it.type == AutostartItem.SHELL ? it.target : "");
        cbRoot.setChecked(it.root);
        etPause.setText(String.valueOf(it.pause));
        if (it.type == AutostartItem.SHELL) {
            rbShell.setChecked(true);
        } else {
            rbApp.setChecked(true);
        }

        final Runnable syncVisibility = () -> {
            boolean shell = rbShell.isChecked();
            spApp.setVisibility(shell ? View.GONE : View.VISIBLE);
            etCommand.setVisibility(shell ? View.VISIBLE : View.GONE);
            cbRoot.setVisibility(shell ? View.VISIBLE : View.GONE);
        };
        typeGroup.setOnCheckedChangeListener((group, checkedId) -> syncVisibility.run());
        syncVisibility.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isNew ? R.string.item_new : R.string.item_edit)
                .setView(view)
                .setPositiveButton(R.string.save, null) // обработчик ниже: окно не закрывается при ошибке ввода
                .setNegativeButton(R.string.cancel, null);
        if (!isNew) {
            builder.setNeutralButton(R.string.delete, (d, which) -> {
                items.remove(index);
                persist();
            });
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean shell = rbShell.isChecked();
            String target;
            if (shell) {
                target = etCommand.getText().toString().trim();
                if (target.length() == 0) {
                    Toast.makeText(this, R.string.toast_need_command, Toast.LENGTH_LONG).show();
                    return;
                }
            } else {
                int pos = spApp.getSelectedItemPosition();
                if (pos < 0 || pos >= pkgs.size()) {
                    Toast.makeText(this, R.string.toast_need_app, Toast.LENGTH_LONG).show();
                    return;
                }
                target = pkgs.get(pos);
            }
            int pause;
            try {
                pause = Integer.parseInt(etPause.getText().toString().trim());
            } catch (NumberFormatException e) {
                pause = -1;
            }
            if (pause < 0 || pause > 3600) {
                Toast.makeText(this, R.string.toast_bad_number, Toast.LENGTH_LONG).show();
                return;
            }
            it.type = shell ? AutostartItem.SHELL : AutostartItem.APP;
            it.target = target;
            it.root = shell && cbRoot.isChecked();
            it.pause = pause;
            if (isNew) {
                items.add(it);
            }
            persist();
            dialog.dismiss();
        }));
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
