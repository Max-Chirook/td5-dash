package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * A plain figure with no dial, for the centre of the cluster between the two
 * round instruments: name, value, unit, then the grey line under a divider.
 */
public class ReadoutView extends GaugeView {

    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ReadoutView(Context context) { this(context, null); }

    public ReadoutView(Context context, AttributeSet attrs) {
        super(context, attrs);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setFontFeatureSettings("tnum");
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setFontFeatureSettings("tnum");
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        // Sized on the 174 × 300 px of the mock-up, and scaled to fit.
        float s = Math.min(w / 174f, h / 300f);
        float cx = w / 2f;
        // The figure sits a little above the middle, level with the dial readings.
        float base = h * 0.49f;

        int state = stale ? STATE_NORMAL : getState();
        int stateColor = stale ? trackColor : stateColor();

        small.setTextSize(15f * s);
        small.setLetterSpacing(0.08f);
        small.setColor(color(stale ? R.color.labelTertiary : R.color.labelSecondary));
        if (caption != null) canvas.drawText(caption, cx, base - 66f * s, small);

        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        big.setTextSize(60f * s);
        float measured = big.measureText(text);
        if (measured > w * 0.92f) big.setTextSize(big.getTextSize() * w * 0.92f / measured);
        int textColor = state == STATE_NORMAL ? color(R.color.label) : stateColor;
        if (stale) textColor = color(R.color.labelTertiary);
        big.setColor(textColor);
        canvas.drawText(text, cx, base, big);

        if (unit != null && !unit.isEmpty()) {
            small.setTextSize(14f * s);
            small.setLetterSpacing(0.04f);
            small.setColor(color(R.color.labelSecondary));
            canvas.drawText(unit, cx, base + 26f * s, small);
        }

        rule.setColor(trackColor);
        rule.setStrokeWidth(Math.max(1f, density));
        canvas.drawLine(cx - 50f * s, base + 46f * s, cx + 50f * s, base + 46f * s, rule);

        String foot = stale ? "SIGNAL PERDU" : footText();
        if (foot != null) {
            small.setTextSize(14f * s);
            small.setLetterSpacing(0.02f);
            small.setColor(stale ? warnColor : color(R.color.labelSecondary));
            canvas.drawText(foot, cx, base + 72f * s, small);
        }
    }
}
