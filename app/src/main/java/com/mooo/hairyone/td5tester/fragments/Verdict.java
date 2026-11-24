package com.mooo.hairyone.td5tester.fragments;

import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/**
 * A short verdict in a pill tinted with its own colour: green when all is
 * well, orange to watch, red to act, grey while measuring.
 */
final class Verdict {

    private Verdict() { }

    static void show(TextView view, CharSequence text, int color) {
        if (view == null) return;
        if (!text.toString().contentEquals(view.getText())) view.setText(text);
        Object tag = view.getTag();
        if (tag instanceof Integer && (Integer) tag == color) return;
        view.setTag(color);
        view.setTextColor(color);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor((color & 0x00FFFFFF) | (0x1F << 24));
        pill.setCornerRadius(17 * view.getResources().getDisplayMetrics().density);
        view.setBackground(pill);
    }
}
