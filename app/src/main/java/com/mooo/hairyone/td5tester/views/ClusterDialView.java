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
 * A large dial drawn straight on the black background, like the round
 * instruments of a digital cluster. The orange and red bands sit dimmed on
 * the track, so the limit can be seen before the needle gets there.
 */
public class ClusterDialView extends GaugeView {

    private static final float START = 150f;
    private static final float SWEEP = 240f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float tickStep = 0f;
    private float tickScale = 1f;

    public ClusterDialView(Context context) { this(context, null); }

    public ClusterDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            tickStep = a.getFloat(R.styleable.GaugeView_gaugeTickStep, 0f);
            tickScale = a.getFloat(R.styleable.GaugeView_gaugeTickScale, 1f);
            a.recycle();
        }
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.BUTT);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setFontFeatureSettings("tnum");
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setFontFeatureSettings("tnum");
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    private float angleOf(float value) { return START + SWEEP * fractionOf(value); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        // The dial is open at the bottom: it spans 2r across and about 1.9r down
        // once the lines under the opening are counted.
        float r = Math.min(w * 0.5f, h / 1.95f) * 0.92f;
        float cx = w / 2f;
        float cy = (h - r * 1.95f) / 2f + r * 1.02f;
        float stroke = r * 0.075f;
        float ring = r - stroke;

        bounds.set(cx - ring, cy - ring, cx + ring, cy + ring);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);

        // Track, then the dimmed warning and alert bands on top of it.
        track.setColor(trackColor);
        canvas.drawArc(bounds, START, SWEEP, false, track);
        if (warnAbove < maxValue && warnAbove > minValue) {
            float to = Math.min(alertAbove, maxValue);
            track.setColor(blend(trackColor, warnColor, 0.30f));
            canvas.drawArc(bounds, angleOf(warnAbove), angleOf(to) - angleOf(warnAbove), false, track);
        }
        if (alertAbove < maxValue && alertAbove > minValue) {
            track.setColor(blend(trackColor, alertColor, 0.35f));
            canvas.drawArc(bounds, angleOf(alertAbove), START + SWEEP - angleOf(alertAbove), false, track);
        }

        int state = stale ? STATE_NORMAL : getState();
        int valueColor = stale ? trackColor : stateColor();
        if (hasValue) {
            float sweep = SWEEP * fractionOf(shownValue);
            if (sweep > 0.5f) {
                arc.setColor(valueColor);
                canvas.drawArc(bounds, START, sweep, false, arc);
            }
        }

        // Scale numbers inside the ring.
        if (tickStep > 0) {
            small.setTextSize(r * 0.085f);
            small.setLetterSpacing(0f);
            float tr = ring - stroke * 1.9f;
            for (float v = minValue; v <= maxValue + tickStep / 1000f; v += tickStep) {
                double a = Math.toRadians(angleOf(v));
                float x = cx + (float) (tr * Math.cos(a));
                float y = cy + (float) (tr * Math.sin(a)) + small.getTextSize() * 0.36f;
                boolean red = v >= alertAbove;
                small.setColor(red ? alertColor : color(R.color.labelTertiary));
                float label = v / tickScale;
                String text = Math.abs(label - Math.round(label)) < 0.001f
                        ? String.valueOf(Math.round(label)) : String.format("%.1f", label);
                canvas.drawText(text, x, y, small);
            }
        }

        // The reading.
        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        int textColor = state == STATE_NORMAL ? color(R.color.label) : valueColor;
        if (stale) textColor = color(R.color.labelTertiary);
        big.setColor(textColor);
        big.setTextSize(r * 0.50f);
        float maxWidth = ring * 1.25f;
        float measured = big.measureText(text);
        if (measured > maxWidth) big.setTextSize(big.getTextSize() * maxWidth / measured);
        canvas.drawText(text, cx, cy - r * 0.01f, big);

        if (unit != null && !unit.isEmpty()) {
            small.setColor(color(R.color.labelSecondary));
            small.setTextSize(r * 0.09f);
            small.setLetterSpacing(0.08f);
            canvas.drawText(unit, cx, cy + r * 0.19f, small);
        }

        // Under the opening: the speed on the engine dial, the name elsewhere.
        String line = secondary != null ? secondary : caption;
        if (line != null && !line.isEmpty()) {
            small.setColor(color(stale ? R.color.labelTertiary : R.color.label));
            small.setTextSize(secondary != null ? r * 0.21f : r * 0.16f);
            small.setLetterSpacing(secondary != null ? 0f : 0.06f);
            canvas.drawText(line, cx, cy + r * 0.70f, small);
        }

        String foot = footText();
        if (foot != null) {
            small.setColor(color(R.color.labelSecondary));
            small.setTextSize(r * 0.085f);
            small.setLetterSpacing(0.02f);
            canvas.drawText(foot, cx, cy + r * 0.88f, small);
        }

        if (stale) {
            small.setColor(warnColor);
            small.setTextSize(r * 0.08f);
            small.setLetterSpacing(0.08f);
            canvas.drawText("SIGNAL PERDU", cx, cy - r * 0.50f, small);
        }
    }
}
