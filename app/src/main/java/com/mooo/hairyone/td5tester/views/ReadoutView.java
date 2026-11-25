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
 * The centre of the cluster: fuel economy on a fine half dial with a mark at
 * the average, the average and distance under it, then the gear in use.
 * Drawn on a 240 × 290 px pattern, scaled to fit and centred.
 */
public class ReadoutView extends GaugeView {

    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final int ink, inkStrong, hairline, grey, dim;

    private float average = Float.NaN;
    private String extra = null;
    private String gear = null;

    public ReadoutView(Context context) { this(context, null); }

    public ReadoutView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ink = color(R.color.ink);
        inkStrong = color(R.color.inkStrong);
        hairline = color(R.color.hairline);
        grey = color(R.color.labelSecondary);
        dim = color(R.color.dim);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(TF_LIGHT);
        big.setFontFeatureSettings("tnum");
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(TF_REGULAR);
        small.setFontFeatureSettings("tnum");
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    /** The mark on the dial, at the trip or long-term average. */
    public void setAverage(float average) {
        if (Float.compare(average, this.average) == 0) return;
        this.average = average;
        invalidate();
    }

    /** The second part of the grey line, the distance. */
    public void setExtra(String extra) {
        if (extra == null ? this.extra == null : extra.equals(this.extra)) return;
        this.extra = extra;
        invalidate();
    }

    /** The gear in use, "4e"; null hides the badge. */
    public void setGear(String gear) {
        if (gear == null ? this.gear == null : gear.equals(this.gear)) return;
        this.gear = gear;
        invalidate();
    }

    private float angle(float v) { return 180f + 180f * fractionOf(v); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        boolean withGear = gear != null;
        float ph = withGear ? 290f : 220f;
        float s = Math.min(w / 240f, h / ph);
        canvas.save();
        canvas.translate((w - 240f * s) / 2f, (h - ph * s) / 2f);
        float cx = 120f * s;

        small.setTextSize(10.5f * s);
        small.setLetterSpacing(0.28f);
        small.setTypeface(TF_MEDIUM);
        small.setColor(grey);
        if (caption != null) canvas.drawText(caption.toUpperCase(), cx, 11f * s, small);
        small.setTypeface(TF_REGULAR);

        float cy = 137f * s, r = 92f * s;
        bounds.set(cx - r, cy - r, cx + r, cy + r);
        stroke.setStrokeWidth(7f * s);
        stroke.setColor(hairline);
        canvas.drawArc(bounds, 180, 180, false, stroke);
        if (warnAbove > minValue && warnAbove < maxValue) {
            stroke.setColor((warnColor & 0x00FFFFFF) | (191 << 24));
            canvas.drawArc(bounds, angle(warnAbove), 360 - angle(warnAbove), false, stroke);
        }
        if (hasValue && !stale && shownValue > minValue) {
            stroke.setColor(inkStrong);
            canvas.drawArc(bounds, 180, angle(shownValue) - 180, false, stroke);
        }
        if (!Float.isNaN(average)) {
            double a = Math.toRadians(angle(average));
            stroke.setStrokeWidth(3.5f * s);
            stroke.setColor(ink);
            float in = r - 9 * s, out = r + 9 * s;
            canvas.drawLine(cx + (float) (in * Math.cos(a)), cy + (float) (in * Math.sin(a)),
                    cx + (float) (out * Math.cos(a)), cy + (float) (out * Math.sin(a)), stroke);
            small.setTextSize(10.5f * s);
            small.setLetterSpacing(0f);
            small.setColor(ink);
            float lr = r + 16 * s;
            canvas.drawText("moy.", cx + (float) (lr * Math.cos(a)), cy + (float) (lr * Math.sin(a)) + 4 * s, small);
        }
        small.setTextSize(11f * s);
        small.setLetterSpacing(0f);
        small.setColor(dim);
        canvas.drawText(String.valueOf(Math.round(minValue)), cx - r, cy + 20 * s, small);
        canvas.drawText(String.valueOf(Math.round(maxValue)), cx + r, cy + 20 * s, small);

        int state = stale ? STATE_NORMAL : getState();
        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        big.setTextSize(52f * s);
        big.setColor(stale ? dim : state == STATE_NORMAL ? ink : stateColor());
        canvas.drawText(text, cx, cy - 12f * s, big);
        if (unit != null && !unit.isEmpty()) {
            small.setTextSize(12.5f * s);
            small.setColor(grey);
            canvas.drawText(unit, cx, cy + 12f * s, small);
        }

        stroke.setStrokeWidth(Math.max(1f, s));
        stroke.setColor(hairline);
        canvas.drawLine(cx - 50 * s, 177 * s, cx + 50 * s, 177 * s, stroke);

        String foot = stale ? "SIGNAL PERDU" : footText();
        if (foot != null) {
            String line = extra != null && !stale ? foot + "  ·  " + extra : foot;
            small.setTextSize(14f * s);
            small.setColor(stale ? warnColor : footColor(ink));
            float max = 236f * s;
            if (small.measureText(line) > max) small.setTextSize(small.getTextSize() * max / small.measureText(line));
            canvas.drawText(line, cx, 203f * s, small);
        }

        if (withGear) {
            bounds.set(cx - 22 * s, 233 * s, cx + 22 * s, 263 * s);
            stroke.setColor((inkStrong & 0x00FFFFFF) | (204 << 24));
            stroke.setStrokeWidth(Math.max(1f, 1.2f * s));
            canvas.drawRoundRect(bounds, 7 * s, 7 * s, stroke);
            small.setTypeface(TF_BOLD);
            small.setTextSize(16f * s);
            small.setColor(inkStrong);
            canvas.drawText(gear, cx, 254f * s, small);
            small.setTypeface(TF_MEDIUM);
            small.setTextSize(9.5f * s);
            small.setLetterSpacing(0.22f);
            small.setColor(grey);
            canvas.drawText("RAPPORT", cx, 281f * s, small);
            small.setTypeface(TF_REGULAR);
        }
        canvas.restore();
    }
}
