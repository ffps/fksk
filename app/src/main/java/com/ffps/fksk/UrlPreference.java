package com.ffps.fksk;

import android.content.Context;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

/** Поле адреса: если адрес ещё не задан, при открытии диалога сразу подставляется "http://". */
public class UrlPreference extends EditTextPreference {

    public UrlPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public UrlPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public UrlPreference(Context context) {
        super(context);
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        EditText edit = getEditText();
        if (edit.getText().length() == 0) {
            edit.setText("http://");
            edit.setSelection(edit.getText().length());
        }
    }
}
