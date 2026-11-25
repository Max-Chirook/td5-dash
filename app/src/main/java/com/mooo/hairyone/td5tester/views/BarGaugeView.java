package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * A reading as a name, a value and a fine bar.
 *
 * Two forms: the strip along the foot of the cluster (name in small capitals,
 * slim bar, the peak or mean below) and the row of the detail tabs (name in
 * plain text, slightly thicker bar, a hint below). A row can also show its
 * value as a mark on the bar instead of a fill, for a limit compared with
 * other readings on the same scale.
 */
public class BarGaugeView extends GaugeView {

    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean row = false;
    private boolean marker = false;
    private final int ink, inkStrong, hairline, grey, dim, labelRow;

    private String status = null;
    private int statusColor = 0;
    private int fillColor = 0;

    public BarGaugeView(Context context) { this(context, null); }

    public BarGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            row = a.getBoolean(R.styleable.GaugeView_gaugeTile, false);
            marker = a.getBoolean(R.styleable.GaugeView_gaugeMarker, false);
            a.recycle();
        }
        ink = color(R.color.ink);
        inkStrong = color(R.color.inkStrong);
        hairline = color(R.color.hairline);
        grey = color(R.color.labelSecondary);
        dim = color(R.color.dim);
        labelRow = color(R.color.labelRow);
        value.setTypeface(TF_LIGHT);
        value.setFontFeatureSettings("tnum");
        value.setTextAlign(Paint.Align.RIGHT);
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    /** The colour of the fill or the mark, when it should differ from white. */
    public void setFillColor(int color) {
        if (fillColor == color) return;
        fillColor = color;
        invalidate();
    }

    /** A verdict shown on the right of the line under the bar. */
    public void setStatus(String status, int color) {
        boolean same = status == null ? this.status == null : status.equals(this.status);
        if (same && color == statusColor) return;
        this.status = status;
        this.statusColor = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        // Drawn in dp on a fixed pattern: it only shrinks when the height or
        // the width runs short, and a tall row keeps its figures at the top.
        float ph = row ? 66f : 60f;
        float s = Math.min(h / ph, density);
        float minWidth = (row ? 190f : 170f) * density;
        if (w < minWidth) s = Math.min(s, density * w / minWidth);
        canvas.save();
        canvas.translate(0, Math.min((h - ph * s) / 2f, 4f * density));
        float left = 0, right = w;

        int state = stale ? STATE_NORMAL : getState();
        int stateColor = stale ? hairline : stateColor();
        int barColor = state == STATE_NORMAL && fillColor != 0 ? fillColor : stateColor;
        if (state == STATE_NORMAL && fillColor == 0) barColor = ink;

        // Name.
        if (row) {
            label.setTypeface(TF_REGULAR);
            label.setTextSize(14.5f * s);
            label.setLetterSpacing(0f);
            label.setColor(stale ? dim : labelRow);
        } else {
            label.setTypeface(TF_MEDIUM);
            label.setTextSize(10.5f * s);
            label.setLetterSpacing(0.2f);
            label.setColor(stale ? dim : grey);
        }
        label.setTextAlign(Paint.Align.LEFT);
        String name = caption == null ? "" : row ? caption : caption.toUpperCase();
        float labelY = row ? 15f : 12f;
        canvas.drawText(name, left, labelY * s, label);

        // Value, its unit smaller after it. Readings centred on zero carry a sign.
        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        if (hasValue && origin != null && shownValue > origin && !text.matches("[0.,]+")) text = "+" + text;
        String u = unit == null || unit.isEmpty() ? "" : " " + unit;
        float valueY = row ? 19f : 17f;
        value.setTypeface(TF_REGULAR);
        value.setTextSize(11f * s);
        float unitWidth = value.measureText(u);
        value.setColor(grey);
        canvas.drawText(u, right, valueY * s, value);
        value.setTypeface(TF_LIGHT);
        value.setTextSize((row ? 26f : 24f) * s);
        value.setColor(stale ? dim : state == STATE_NORMAL ? ink : stateColor);
        canvas.drawText(text, right - unitWidth, valueY * s, value);

        // Bar.
        float top = (row ? 31f : 27f) * s;
        float thick = (row ? 5f : 3f) * s;
        float span = right - left;
        bar.setColor(hairline);
        rect.set(left, top, right, top + thick);
        canvas.drawRoundRect(rect, thick / 2, thick / 2, bar);
        band(canvas, minValue, alertBelow, left, span, top, thick, alertColor);
        band(canvas, alertBelow, warnBelow, left, span, top, thick, warnColor);
        band(canvas, warnAbove, alertAbove, left, span, top, thick, warnColor);
        band(canvas, alertAbove, maxValue, left, span, top, thick, alertColor);

        if (hasValue) {
            float to = left + span * fractionOf(shownValue);
            if (marker) {
                bar.setColor(fillColor != 0 ? fillColor : warnColor);
                rect.set(to - 1.5f * s, top - 5f * s, to + 1.5f * s, top + thick + 5f * s);
                canvas.drawRoundRect(rect, 1.5f * s, 1.5f * s, bar);
            } else if (origin != null) {
                float zx = left + span * fractionOf(origin);
                bar.setColor(grey);
                rect.set(zx - 0.5f * s, top - 6f * s, zx + 0.5f * s, top + thick + 6f * s);
                canvas.drawRect(rect, bar);
                bar.setColor(barColor == ink ? inkStrong : barColor);
                canvas.drawCircle(to, top + thick / 2, (row ? 6f : 4f) * s, bar);
            } else {
                float fat = row ? thick : 4f * s;
                float y0 = top + thick / 2 - fat / 2;
                bar.setColor(barColor);
                rect.set(left, y0, Math.max(left + fat, to), y0 + fat);
                canvas.drawRoundRect(rect, fat / 2, fat / 2, bar);
            }
        }

        // Under the bar: lost signal, or the peak / mean / hint, and a verdict on the right.
        String foot = stale ? "SIGNAL PERDU" : footText();
        float footY = row ? 57f : 53f;
        if (foot != null) {
            label.setTypeface(TF_REGULAR);
            label.setTextSize((row ? 11.5f : 11f) * s);
            label.setLetterSpacing(0f);
            label.setColor(stale ? warnColor : footColor(row ? dim : grey));
            canvas.drawText(foot, left, footY * s, label);
        }
        if (status != null && !stale) {
            label.setTypeface(TF_REGULAR);
            label.setTextSize(11f * s);
            label.setLetterSpacing(0f);
            label.setColor(statusColor);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(status, right, footY * s, label);
            float width = label.measureText(status);
            label.setTextAlign(Paint.Align.LEFT);
            bar.setColor(statusColor);
            canvas.drawCircle(right - width - 8f * s, (footY - 4f) * s, 3f * s, bar);
        }
        canvas.restore();
    }

    /** A coloured stretch of track between two values, when they fall on the scale. */
    private void band(Canvas canvas, float from, float to, float left, float span,
                      float top, float thick, int color) {
        if (Float.isInfinite(from) || Float.isInfinite(to)) return;
        float a = Math.max(from, minValue);
        float b = Math.min(to, maxValue);
        if (b <= a) return;
        bar.setColor((color & 0x00FFFFFF) | (191 << 24));
        rect.set(left + span * fractionOf(a), top, left + span * fractionOf(b), top + thick);
        canvas.drawRect(rect, bar);
    }
}
