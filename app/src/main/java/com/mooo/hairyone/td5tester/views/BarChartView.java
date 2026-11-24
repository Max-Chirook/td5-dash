package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * Bars for the bilans: one per slot (speed band, gear, engine speed), its
 * figure above it and two lines of labels below. Optionally a white mark for
 * a maximum, a grey dashed line for a reference, a dashed average across, and
 * one bar outlined (the gear in use).
 */
public class BarChartView extends View {

    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float d;
    private final int ink, inkStrong, grid, dim, refColor;

    private String[] labels = new String[0];
    private String[] subLabels = new String[0];
    private double[] values = new double[0];
    private int[] colors = new int[0];
    private double[] maxes = null;
    private double[] reference = null;
    private double average = Double.NaN;
    private int outlined = -1;
    private int decimals = 1;
    private String empty = "";
    private String title = null;
    private final DashPathEffect refDash, avgDash;

    public BarChartView(Context context) { this(context, null); }

    public BarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        ink = ContextCompat.getColor(context, R.color.ink);
        inkStrong = ContextCompat.getColor(context, R.color.inkStrong);
        grid = 0xFF1F2125;
        dim = ContextCompat.getColor(context, R.color.dim);
        refColor = ContextCompat.getColor(context, R.color.labelSecondary);
        text.setFontFeatureSettings("tnum");
        dashed.setStyle(Paint.Style.STROKE);
        refDash = new DashPathEffect(new float[]{5 * d, 4 * d}, 0);
        avgDash = new DashPathEffect(new float[]{5 * d, 5 * d}, 0);
    }

    public void setData(String[] labels, String[] subLabels, double[] values, int[] colors) {
        this.labels = labels;
        this.subLabels = subLabels;
        this.values = values;
        this.colors = colors;
        invalidate();
    }

    public void setMaxes(double[] maxes) { this.maxes = maxes; invalidate(); }
    public void setReference(double[] reference) { this.reference = reference; invalidate(); }
    public void setAverage(double average) { this.average = average; invalidate(); }
    public void setOutlined(int index) { this.outlined = index; invalidate(); }
    public void setDecimals(int decimals) { this.decimals = decimals; invalidate(); }
    public void setEmptyText(String empty) { this.empty = empty; invalidate(); }
    /** A small heading at the top left of the chart. */
    public void setTitle(String title) { this.title = title; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float left = 40 * d, right = w - 6 * d, top = 30 * d, bottom = h - 40 * d;
        if (right <= left || bottom <= top) return;

        if (title != null) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(10 * d);
            text.setLetterSpacing(0.2f);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(dim);
            canvas.drawText(title, 12 * d, 14 * d, text);
            text.setLetterSpacing(0f);
        }

        double hi = 0;
        boolean any = false;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) { hi = Math.max(hi, values[i]); any = true; }
            if (maxes != null && !Double.isNaN(maxes[i])) hi = Math.max(hi, maxes[i]);
            if (reference != null && !Double.isNaN(reference[i])) hi = Math.max(hi, reference[i]);
        }
        if (!Double.isNaN(average)) hi = Math.max(hi, average);
        if (hi <= 0) hi = 1;
        float step = LineChartView.niceStep((float) (hi / 4));
        double yMax = Math.ceil(hi * 1.12 / step) * step;

        // Horizontal grid.
        paint.setStrokeWidth(Math.max(1f, d));
        text.setTypeface(REGULAR);
        text.setTextSize(10.5f * d);
        text.setTextAlign(Paint.Align.RIGHT);
        int stepDecimals = LineChartView.decimalsFor(step);
        for (double v = 0; v <= yMax + step * 0.01; v += step) {
            float y = y(v, yMax, top, bottom);
            paint.setColor(grid);
            canvas.drawLine(left, y, right, y, paint);
            text.setColor(dim);
            canvas.drawText(String.format("%." + stepDecimals + "f", v), left - 10 * d, y + 4 * d, text);
        }

        int n = values.length;
        if (n == 0) return;
        float slot = (right - left) / n;
        float bw = Math.min(70 * d, slot * 0.62f);
        float[] centres = new float[n];

        for (int i = 0; i < n; i++) {
            float cx = left + slot * (i + 0.5f);
            centres[i] = cx;
            double v = values[i];
            if (!Double.isNaN(v)) {
                float yv = y(v, yMax, top, bottom);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(colors.length > i ? colors[i] : ink);
                rect.set(cx - bw / 2, yv, cx + bw / 2, bottom);
                canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
                if (i == outlined) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * d);
                    paint.setColor(inkStrong);
                    canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                if (maxes == null) {
                    text.setTypeface(LIGHT);
                    text.setTextSize(16 * d);
                    text.setTextAlign(Paint.Align.CENTER);
                    text.setColor(ink);
                    canvas.drawText(String.format("%." + decimals + "f", v), cx, yv - 9 * d, text);
                }
            }
            if (maxes != null && !Double.isNaN(maxes[i])) {
                float ym = y(maxes[i], yMax, top, bottom);
                paint.setColor(inkStrong);
                paint.setStrokeWidth(2.5f * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(cx - bw / 2 + 4 * d, ym, cx + bw / 2 - 4 * d, ym, paint);
                text.setTypeface(REGULAR);
                text.setTextSize(11.5f * d);
                text.setTextAlign(Paint.Align.CENTER);
                text.setColor(0xFFAEAEB2);
                canvas.drawText(String.format("%." + decimals + "f", maxes[i]), cx, ym - 8 * d, text);
            }
            text.setTypeface(i == outlined ? Typeface.DEFAULT_BOLD : REGULAR);
            text.setTextSize(12 * d);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(ink);
            if (i < labels.length) canvas.drawText(labels[i], cx, bottom + 18 * d, text);
            text.setTypeface(REGULAR);
            text.setTextSize(10.5f * d);
            text.setColor(i == outlined ? ink : dim);
            if (i < subLabels.length && subLabels[i] != null) canvas.drawText(subLabels[i], cx, bottom + 34 * d, text);
        }

        // Reference line through the bar centres.
        if (reference != null) {
            path.reset();
            boolean started = false;
            dashed.setColor(refColor);
            dashed.setStrokeWidth(1.8f * d);
            dashed.setPathEffect(refDash);
            paint.setColor(refColor);
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(reference[i])) continue;
                float yr = y(reference[i], yMax, top, bottom);
                if (!started) { path.moveTo(centres[i], yr); started = true; } else path.lineTo(centres[i], yr);
                canvas.drawCircle(centres[i], yr, 3 * d, paint);
            }
            canvas.drawPath(path, dashed);
        }

        if (!Double.isNaN(average)) {
            float ya = y(average, yMax, top, bottom);
            dashed.setColor((ink & 0x00FFFFFF) | (115 << 24));
            dashed.setStrokeWidth(Math.max(1f, d));
            dashed.setPathEffect(avgDash);
            canvas.drawLine(left, ya, right, ya, dashed);
            text.setTypeface(REGULAR);
            text.setTextSize(10.5f * d);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(ink);
            canvas.drawText(String.format("moy. %.1f", average), left + 4 * d, ya - 7 * d, text);
        }

        if (!any && empty != null && !empty.isEmpty()) {
            text.setTypeface(REGULAR);
            text.setTextSize(14 * d);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(refColor);
            canvas.drawText(empty, (left + right) / 2, (top + bottom) / 2, text);
        }
    }

    private static float y(double v, double yMax, float top, float bottom) {
        return (float) (bottom - (bottom - top) * v / yMax);
    }
}
