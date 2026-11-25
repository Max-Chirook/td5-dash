package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * A reading centred on zero as a fine graduated rail, for the injector
 * balance: the value on top, the name below, a white stem from zero and a dot
 * at the reading. Several side by side share the green band (the ideal range)
 * and the zero line, since each draws them across its full width.
 */
public class ColumnGaugeView extends GaugeView {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean scaleMarks = false;
    private float dashScale = -1;
    private final int ink, inkStrong, hairline, good, grey, dim;

    public ColumnGaugeView(Context context) { this(context, null); }

    public ColumnGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            scaleMarks = a.getBoolean(R.styleable.GaugeView_gaugeScaleMarks, false);
            a.recycle();
        }
        ink = color(R.color.ink);
        inkStrong = color(R.color.inkStrong);
        hairline = color(R.color.hairline);
        good = color(R.color.stateNormal);
        grey = color(R.color.labelSecondary);
        dim = color(R.color.dim);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFontFeatureSettings("tnum");
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        dashed.setStyle(Paint.Style.STROKE);
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    private float yOf(float v, float top, float bottom) {
        return bottom - (bottom - top) * fractionOf(v);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float s = Math.min(h / 400f, w / 110f);
        float cx = w / 2f;
        float top = 64f * s;
        float bottom = h - 40f * s;
        float zero = origin != null ? origin : 0f;
        float zy = yOf(zero, top, bottom);

        // The ideal range, across the full width so neighbours join up.
        if (!Float.isInfinite(warnAbove) && !Float.isInfinite(warnBelow)) {
            float a = yOf(warnAbove, top, bottom), b = yOf(warnBelow, top, bottom);
            paint.setColor((good & 0x00FFFFFF) | (28 << 24));
            canvas.drawRect(0, a, w, b, paint);
            dashed.setColor((good & 0x00FFFFFF) | (130 << 24));
            dashed.setStrokeWidth(Math.max(1f, s));
            if (dashScale != s) {
                dashed.setPathEffect(new DashPathEffect(new float[]{3 * s, 4 * s}, 0));
                dashScale = s;
            }
            canvas.drawLine(0, a, w, a, dashed);
            canvas.drawLine(0, b, w, b, dashed);
            if (scaleMarks) {
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(TF_REGULAR);
                text.setTextSize(11f * s);
                text.setColor(good);
                canvas.drawText("+" + Math.round(warnAbove), 2f * s, a - 5f * s, text);
                canvas.drawText("−" + Math.round(Math.abs(warnBelow)), 2f * s, b + 14f * s, text);
                text.setTextAlign(Paint.Align.CENTER);
            }
        }

        // The rail and its graduations, then the zero line across.
        line.setStrokeWidth(2f * s);
        line.setColor(hairline);
        canvas.drawLine(cx, top, cx, bottom, line);
        line.setStrokeWidth(Math.max(1f, s));
        line.setColor(0xFF6E7076);
        float step = (maxValue - minValue) / 6f;
        for (int k = 0; k <= 6; k++) {
            float v = minValue + k * step;
            if (Math.abs(v - zero) < step / 10f) continue;
            float y = yOf(v, top, bottom);
            canvas.drawLine(cx - 5 * s, y, cx + 5 * s, y, line);
        }
        paint.setColor(0xFF8E8E93);
        canvas.drawRect(0, zy - 0.5f * s, w, zy + 0.5f * s, paint);

        int state = stale ? STATE_NORMAL : getState();
        int mark = stale ? dim : state == STATE_NORMAL ? inkStrong : stateColor();
        if (hasValue) {
            float vy = yOf(shownValue, top, bottom);
            line.setStrokeWidth(6f * s);
            line.setColor(mark);
            if (Math.abs(vy - zy) > 2 * s) canvas.drawLine(cx, zy, cx, vy, line);
            paint.setColor(mark);
            canvas.drawCircle(cx, vy, 7f * s, paint);
        }

        String value = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        if (hasValue && shownValue > zero && !value.matches("[0.,]+")) value = "+" + value;
        if (hasValue && shownValue < zero) value = value.replace('-', '−');
        text.setTypeface(TF_LIGHT);
        text.setTextSize(30f * s);
        text.setLetterSpacing(0f);
        text.setColor(stale ? dim : state == STATE_NORMAL ? ink : stateColor());
        canvas.drawText(value, cx, 38f * s, text);

        text.setTypeface(TF_MEDIUM);
        text.setTextSize(11f * s);
        text.setLetterSpacing(0.2f);
        text.setColor(stale ? dim : grey);
        String name = stale ? "PERDU" : caption == null ? "" : caption.toUpperCase();
        canvas.drawText(name, cx, h - 12f * s, text);
    }
}
