package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * A reading centred on zero as a vertical column, for the injector balance:
 * the value on top, the name below, and the column growing up or down from a
 * zero line. Several placed side by side share one green band (the ideal
 * range) and one zero line, since each draws them across its full width.
 */
public class ColumnGaugeView extends GaugeView {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean scaleMarks = false;

    public ColumnGaugeView(Context context) { this(context, null); }

    public ColumnGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            scaleMarks = a.getBoolean(R.styleable.GaugeView_gaugeScaleMarks, false);
            a.recycle();
        }
        text.setTextAlign(Paint.Align.CENTER);
        text.setFontFeatureSettings("tnum");
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    /** Vertical position of a value: the maximum at the top, the minimum at the bottom. */
    private float yOf(float v, float top, float bottom) {
        return bottom - (bottom - top) * fractionOf(v);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float s = Math.min(h / 420f, w / 115f);
        float cx = w / 2f;
        float valueBase = 40f * s;
        float top = 60f * s;
        float bottom = h - 46f * s;
        float zero = origin != null ? origin : 0f;
        float zy = yOf(zero, top, bottom);

        // The ideal range, across the full width so neighbours join up.
        if (!Float.isInfinite(warnAbove) && !Float.isInfinite(warnBelow)) {
            paint.setColor(color(R.color.goodBand));
            canvas.drawRect(0, yOf(warnAbove, top, bottom), w, yOf(warnBelow, top, bottom), paint);
            if (scaleMarks) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                text.setTextSize(12f * s);
                text.setColor(color(R.color.goodBandText));
                canvas.drawText("+" + Math.round(warnAbove), 4f * s, yOf(warnAbove, top, bottom) - 6f * s, text);
                canvas.drawText("−" + Math.round(Math.abs(warnBelow)), 4f * s, yOf(warnBelow, top, bottom) + 16f * s, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
        }

        // The column's track, then the zero line across.
        float half = 20f * s;
        paint.setColor(trackColor);
        rect.set(cx - half, top, cx + half, bottom);
        canvas.drawRoundRect(rect, 8f * s, 8f * s, paint);
        paint.setColor(color(R.color.labelTertiary));
        canvas.drawRect(0, zy - 1f * s, w, zy + 1f * s, paint);

        int state = stale ? STATE_NORMAL : getState();
        int fill = stale ? trackColor : stateColor();
        if (hasValue) {
            float vy = yOf(shownValue, top, bottom);
            float a = Math.min(vy, zy), b = Math.max(vy, zy);
            float min = 6f * s;
            if (b - a < min) { float m = (a + b) / 2; a = m - min / 2; b = m + min / 2; }
            paint.setColor(fill);
            rect.set(cx - half, a, cx + half, b);
            canvas.drawRoundRect(rect, 6f * s, 6f * s, paint);
        }

        String value = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        if (hasValue && shownValue > zero && !value.matches("[0.,]+")) value = "+" + value;
        if (hasValue && shownValue < zero) value = value.replace('-', '−');
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setTextSize(30f * s);
        text.setColor(stale ? color(R.color.labelTertiary)
                : state == STATE_NORMAL ? color(R.color.label) : fill);
        canvas.drawText(value, cx, valueBase, text);

        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(16f * s);
        text.setColor(color(stale ? R.color.labelTertiary : R.color.labelTile));
        canvas.drawText(stale ? "perdu" : (caption == null ? "" : caption), cx, h - 16f * s, text);
    }
}
