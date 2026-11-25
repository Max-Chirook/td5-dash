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
 * A round instrument in the style of a recent driver display: a fine outer
 * ring, graduations, a white needle and a thin trace behind it, the orange and
 * red zones set into the ring, and the reading in a dark disc at the centre.
 * Everything is sized from the radius, so it scales with the space given.
 */
public class ClusterDialView extends GaugeView {

    private static final float START = 125f;
    private static final float SWEEP = 290f;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float tickStep = 0f;
    private float tickScale = 1f;
    private final int ink, inkStrong, hairline, tick, disc, grey;

    public ClusterDialView(Context context) { this(context, null); }

    public ClusterDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            tickStep = a.getFloat(R.styleable.GaugeView_gaugeTickStep, 0f);
            tickScale = a.getFloat(R.styleable.GaugeView_gaugeTickScale, 1f);
            a.recycle();
        }
        ink = color(R.color.ink);
        inkStrong = color(R.color.inkStrong);
        hairline = color(R.color.hairline);
        tick = color(R.color.tick);
        disc = color(R.color.disc);
        grey = color(R.color.labelSecondary);
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(TF_LIGHT);
        big.setFontFeatureSettings("tnum");
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(TF_REGULAR);
        small.setFontFeatureSettings("tnum");
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    private float angleOf(float value) { return START + SWEEP * fractionOf(value); }

    private void arc(Canvas canvas, float cx, float cy, float radius, float from, float to) {
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(bounds, from, to - from, false, stroke);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        // The dial is a full circle; its outer ring takes a few percent more.
        float r = Math.min(w, h) / 2f / 1.04f;
        float cx = w / 2f;
        float cy = h / 2f;
        float u = r / 150f;   // one unit of the 150 px mock-up radius

        // Fine outer ring.
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeWidth(Math.max(1f, 1.5f * u));
        stroke.setColor(hairline);
        canvas.drawCircle(cx, cy, r + 4 * u, stroke);

        // Orange and red zones set into the ring.
        stroke.setStrokeWidth(8 * u);
        if (warnAbove > minValue && warnAbove < maxValue) {
            stroke.setColor(withAlpha(warnColor, 191));
            arc(canvas, cx, cy, r - 6 * u, angleOf(warnAbove), angleOf(Math.min(alertAbove, maxValue)));
        }
        if (alertAbove > minValue && alertAbove < maxValue) {
            stroke.setColor(withAlpha(alertColor, 204));
            arc(canvas, cx, cy, r - 6 * u, angleOf(alertAbove), START + SWEEP);
        }

        // The trace from zero to the reading.
        float angle = angleOf(hasValue ? shownValue : minValue);
        if (hasValue && !stale) {
            stroke.setStrokeWidth(4 * u);
            stroke.setColor(inkStrong);
            arc(canvas, cx, cy, r - 3 * u, START, angle);
        }

        // Graduations and figures.
        if (tickStep > 0) {
            float minor = tickStep / 4f;
            small.setTextSize(20 * u);
            small.setLetterSpacing(0f);
            int n = Math.round((maxValue - minValue) / minor);
            for (int k = 0; k <= n; k++) {
                float v = minValue + k * minor;
                boolean major = k % 4 == 0;
                boolean red = v >= alertAbove - minor / 100f;
                double a = Math.toRadians(angleOf(v));
                float outer = r - 10 * u, inner = outer - (major ? 16 : 8) * u;
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeWidth((major ? 3.2f : 1.7f) * u);
                stroke.setColor(red ? alertColor : tick);
                canvas.drawLine(cx + (float) (inner * Math.cos(a)), cy + (float) (inner * Math.sin(a)),
                        cx + (float) (outer * Math.cos(a)), cy + (float) (outer * Math.sin(a)), stroke);
                if (major) {
                    float lr = r - 42 * u;
                    float label = v / tickScale;
                    String text = Math.abs(label - Math.round(label)) < 0.001f
                            ? String.valueOf(Math.round(label)) : String.format("%.1f", label);
                    small.setColor(red ? alertColor : ink);
                    canvas.drawText(text, cx + (float) (lr * Math.cos(a)),
                            cy + (float) (lr * Math.sin(a)) + small.getTextSize() * 0.36f, small);
                }
            }
        }

        // Needle, with a faint glow.
        if (hasValue) {
            double a = Math.toRadians(angle);
            float nx = cx + (float) ((r - 12 * u) * Math.cos(a));
            float ny = cy + (float) ((r - 12 * u) * Math.sin(a));
            float bx = cx + (float) (60 * u * Math.cos(a));
            float by = cy + (float) (60 * u * Math.sin(a));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(13 * u);
            stroke.setColor(withAlpha(stale ? tick : inkStrong, 46));
            canvas.drawLine(bx, by, nx, ny, stroke);
            stroke.setStrokeWidth(4.5f * u);
            stroke.setColor(stale ? tick : inkStrong);
            canvas.drawLine(bx, by, nx, ny, stroke);
        }

        // Central disc with the reading.
        fill.setColor(disc);
        canvas.drawCircle(cx, cy, 74 * u, fill);
        stroke.setStrokeWidth(Math.max(1f, 1.5f * u));
        stroke.setColor(hairline);
        canvas.drawCircle(cx, cy, 74 * u, stroke);

        int state = stale ? STATE_NORMAL : getState();
        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        big.setTextSize(44 * u);
        float measured = big.measureText(text);
        if (measured > 128 * u) big.setTextSize(big.getTextSize() * 128 * u / measured);
        big.setColor(stale ? tick : state == STATE_NORMAL ? ink : stateColor());
        canvas.drawText(text, cx, cy + 6 * u, big);

        if (unit != null && !unit.isEmpty()) {
            small.setColor(grey);
            small.setTextSize(12 * u);
            small.setLetterSpacing(0.15f);
            canvas.drawText(unit, cx, cy + 30 * u, small);
        }

        if (stale) {
            small.setColor(warnColor);
            small.setTextSize(10 * u);
            small.setLetterSpacing(0.12f);
            canvas.drawText("SIGNAL PERDU", cx, cy - 34 * u, small);
        }

        // In the opening at the bottom: the speed on the engine dial, the name elsewhere.
        String line = secondary != null ? secondary : caption == null ? null : caption.toUpperCase();
        if (line != null && !line.isEmpty()) {
            small.setColor(stale ? tick : ink);
            small.setTypeface(TF_MEDIUM);
            small.setTextSize(secondary != null ? 22 * u : 15 * u);
            small.setLetterSpacing(secondary != null ? 0f : 0.2f);
            canvas.drawText(line, cx, cy + 120 * u, small);
            small.setTypeface(TF_REGULAR);
        }
        String foot = footText();
        if (foot != null) {
            small.setColor(footColor(grey));
            small.setTextSize(12 * u);
            small.setLetterSpacing(0.02f);
            canvas.drawText(foot, cx, cy + 142 * u, small);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
