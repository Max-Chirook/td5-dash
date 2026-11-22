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
        // Drawn on a 280 × 90 px pattern, scaled to the height but never wider
        // than the width allows, and centred when the height is generous.
        float s = Math.min(h / 90f, w / 280f);
        float oy = (h - 90f * s) / 2f;
        canvas.save();
        canvas.translate(0, oy);
        float pad = 6f * density;
        float left = pad;
        float right = w - pad;

        int state = stale ? STATE_NORMAL : getState();
        int stateColor = stale ? trackColor : stateColor();

        label.setTextSize(15f * s);
        label.setLetterSpacing(0.04f);
        label.setColor(color(stale ? R.color.labelTertiary : R.color.labelSecondary));
        canvas.drawText(caption == null ? "" : caption, left, 32f * s, label);

        // The value, its unit set smaller just after it. Readings centred on
        // zero carry their sign either way.
        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        if (hasValue && origin != null && shownValue > origin && !text.matches("[0.,]+")) text = "+" + text;
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
        float span = right - left;
        bar.setColor(trackColor);
        rect.set(left, top, right, top + thick);
        canvas.drawRoundRect(rect, thick / 2, thick / 2, bar);

        // The orange and red bands, dimmed on the track as on the dials, so
        // the limits show before the reading gets there.
        band(canvas, minValue, alertBelow, left, span, top, thick, blend(trackColor, alertColor, 0.35f));
        band(canvas, alertBelow, warnBelow, left, span, top, thick, blend(trackColor, warnColor, 0.30f));
        band(canvas, warnAbove, alertAbove, left, span, top, thick, blend(trackColor, warnColor, 0.30f));
        band(canvas, alertAbove, maxValue, left, span, top, thick, blend(trackColor, alertColor, 0.35f));

        if (origin != null) {
            // A small mark where zero sits.
            bar.setColor(color(R.color.labelTertiary));
            float zx = left + span * fractionOf(origin);
            rect.set(zx - 1f * s, top - 4f * s, zx + 1f * s, top + thick + 4f * s);
            canvas.drawRect(rect, bar);
        }
        if (hasValue) {
            float from = origin != null ? fractionOf(origin) : 0f;
            float to = fractionOf(shownValue);
            float a = left + span * Math.min(from, to);
            float b = left + span * Math.max(from, to);
            if (b - a < thick) {
                if (origin == null) b = a + thick; else { float m = (a + b) / 2; a = m - thick / 2; b = m + thick / 2; }
            }
            bar.setColor(stateColor);
            rect.set(a, top, b, top + thick);
            canvas.drawRoundRect(rect, thick / 2, thick / 2, bar);
        }

        String foot = stale ? "SIGNAL PERDU" : footText();
        if (foot != null) {
            label.setTextSize(13f * s);
            label.setLetterSpacing(0.02f);
            label.setColor(stale ? warnColor : footColor(color(R.color.labelTertiary)));
            canvas.drawText(foot, left, 76f * s, label);
        }

        // A short verdict on the right of the same line, after a coloured dot.
        if (status != null) {
            label.setTextSize(13f * s);
            label.setLetterSpacing(0.02f);
            label.setColor(statusColor);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(status, right, 76f * s, label);
            float width = label.measureText(status);
            label.setTextAlign(Paint.Align.LEFT);
            bar.setColor(statusColor);
            canvas.drawCircle(right - width - 9f * s, 71.5f * s, 4f * s, bar);
        }
        canvas.restore();
    }

    /** A dimmed stretch of track between two values, when they fall on the scale. */
    private void band(Canvas canvas, float from, float to, float left, float span,
                      float top, float thick, int color) {
        if (Float.isInfinite(from) || Float.isInfinite(to)) return;
        float a = Math.max(from, minValue);
        float b = Math.min(to, maxValue);
        if (b <= a) return;
        bar.setColor(color);
        rect.set(left + span * fractionOf(a), top, left + span * fractionOf(b), top + thick);
        canvas.drawRect(rect, bar);
    }

    private String status = null;
    private int statusColor = 0;

    /** A verdict shown on the right of the grey line, such as the air meter's health. */
    public void setStatus(String status, int color) {
        boolean same = status == null ? this.status == null : status.equals(this.status);
        if (same && color == statusColor) return;
        this.status = status;
        this.statusColor = color;
        invalidate();
    }
}
