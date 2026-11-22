package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * A slow reading as a horizontal bar: the name on the left, the value on the
 * right, the bar under them and the peak or mean below. Used for the strip at
 * the foot of the cluster.
 */
public class BarGaugeView extends GaugeView {

    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public BarGaugeView(Context context) { this(context, null); }

    public BarGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        value.setFontFeatureSettings("tnum");
        value.setTextAlign(Paint.Align.RIGHT);
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float s = h / 90f;
        float pad = 6f * density;
        float left = pad;
        float right = w - pad;

        int state = stale ? STATE_NORMAL : getState();
        int stateColor = stale ? trackColor : stateColor();

        label.setTextSize(15f * s);
        label.setLetterSpacing(0.04f);
        label.setColor(color(stale ? R.color.labelTertiary : R.color.labelSecondary));
        canvas.drawText(caption == null ? "" : caption, left, 32f * s, label);

        // The value, its unit set smaller just after it.
        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        String u = unit == null || unit.isEmpty() ? "" : " " + unit;
        value.setTextSize(14f * s);
        float unitWidth = value.measureText(u);
        value.setColor(color(R.color.labelSecondary));
        canvas.drawText(u, right, 34f * s, value);
        value.setTextSize(27f * s);
        int textColor = state == STATE_NORMAL ? color(R.color.label) : stateColor;
        if (stale) textColor = color(R.color.labelTertiary);
        value.setColor(textColor);
        canvas.drawText(text, right - unitWidth, 34f * s, value);

        float top = 46f * s;
        float thick = 6f * s;
        bar.setColor(trackColor);
        rect.set(left, top, right, top + thick);
        canvas.drawRoundRect(rect, thick / 2, thick / 2, bar);
        if (hasValue) {
            float f = fractionOf(shownValue);
            if (f > 0f) {
                bar.setColor(stateColor);
                rect.set(left, top, left + Math.max(thick, (right - left) * f), top + thick);
                canvas.drawRoundRect(rect, thick / 2, thick / 2, bar);
            }
        }

        String foot = stale ? "SIGNAL PERDU" : footText();
        if (foot != null) {
            label.setTextSize(13f * s);
            label.setLetterSpacing(0.02f);
            label.setColor(stale ? warnColor : color(R.color.labelTertiary));
            canvas.drawText(foot, left, 76f * s, label);
        }
    }
}
