package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.SignalHistory;

/**
 * A scrolling line chart of one signal over the last few minutes. The vertical
 * scale follows what is on screen, so a small variation stays readable, and
 * the session mean is drawn as a dashed line when it falls inside it.
 */
public class LineChartView extends View {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path fill = new Path();
    private final RectF card = new RectF();

    private final float density;
    private int lineColor;
    private final int dim;

    /** Colours each stretch of the curve by its value (fuel economy). */
    public interface Colorizer { int colorFor(float value); }

    private Colorizer colorizer = null;
    private boolean compact = false;
    private LinearGradient shader = null;
    private float shaderTop = -1, shaderBottom = -1;
    private int shaderColor = 0;

    private SignalHistory history;
    /** Readings further apart than this are not joined. */
    private static final long GAP_MS = 3000;
    private int decimals = 0;
    private long windowMs = 120_000;
    private String emptyText = "";

    public LineChartView(Context context) { this(context, null); }

    public LineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        lineColor = ContextCompat.getColor(context, R.color.dataBlue);
        dim = ContextCompat.getColor(context, R.color.dim);

        cardPaint.setColor(0);

        gridPaint.setColor(0xFF1F2125);
        gridPaint.setStrokeWidth(Math.max(1f, density));

        labelPaint.setColor(dim);
        labelPaint.setTextSize(10.5f * density);
        labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        labelPaint.setFontFeatureSettings("tnum");

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(lineColor);

        fillPaint.setStyle(Paint.Style.FILL);

        meanPaint.setStyle(Paint.Style.STROKE);
        meanPaint.setStrokeWidth(Math.max(1f, density));
        meanPaint.setColor((ContextCompat.getColor(context, R.color.ink) & 0x00FFFFFF) | (115 << 24));
        meanPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 5 * density}, 0));

        dotPaint.setColor(lineColor);
    }

    public void setSource(SignalHistory history, int decimals) {
        this.history = history;
        this.decimals = decimals;
        invalidate();
    }

    public void setLineColor(int color) {
        lineColor = color;
        linePaint.setColor(color);
        dotPaint.setColor(color);
        invalidate();
    }

    public void setColorizer(Colorizer colorizer) {
        this.colorizer = colorizer;
        invalidate();
    }

    /** The small strip under the main chart: no time axis, two scale figures. */
    public void setCompact(boolean compact) {
        this.compact = compact;
        linePaint.setStrokeWidth((compact ? 1.5f : 2.2f) * density);
        invalidate();
    }

    public void setWindowMs(long windowMs) {
        this.windowMs = windowMs;
        invalidate();
    }

    public long getWindowMs() { return windowMs; }

    public void setEmptyText(String emptyText) {
        this.emptyText = emptyText;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float left = 40f * density;
        float right = w - 6f * density;
        float top = (compact ? 4f : 10f) * density;
        float bottom = h - (compact ? 4f : 24f) * density;
        if (right <= left || bottom <= top) return;

        long now = SystemClock.elapsedRealtime();
        long from = now - windowMs;

        // Range of what is on screen.
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        int first = -1;
        int n = history == null ? 0 : history.size();
        for (int i = 0; i < n; i++) {
            if (history.timeAt(i) < from) continue;
            if (first < 0) first = i;
            float v = history.valueAt(i);
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }

        if (!compact) drawTimeAxis(canvas, left, right, bottom);

        if (first < 0) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTextSize((compact ? 11f : 15f) * density);
            canvas.drawText(compact ? "—" : emptyText, (left + right) / 2f, (top + bottom) / 2f, labelPaint);
            labelPaint.setTextSize(10.5f * density);
            postInvalidateDelayed(1000);
            return;
        }

        // Pad the range and round it to tidy steps.
        float span = hi - lo;
        if (span < 1e-6f) span = Math.max(Math.abs(hi) * 0.1f, 1f);
        float step = niceStep(span / 4f);
        float yMin = (float) (Math.floor((lo - span * 0.08f) / step) * step);
        float yMax = (float) (Math.ceil((hi + span * 0.08f) / step) * step);
        // A reading that is never negative should not get a scale below zero.
        if (lo >= 0 && yMin < 0) yMin = 0;
        if (yMax - yMin < step) yMax = yMin + step;

        // Horizontal grid with its labels.
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        int stepDecimals = decimalsFor(step);
        if (compact) {
            canvas.drawLine(left, bottom, right, bottom, gridPaint);
            canvas.drawText(String.format("%." + stepDecimals + "f", yMax), left - 8f * density, top + 8f * density, labelPaint);
            canvas.drawText(String.format("%." + stepDecimals + "f", yMin), left - 8f * density, bottom, labelPaint);
        } else {
            for (float y = yMin; y <= yMax + step * 0.01f; y += step) {
                float py = toY(y, yMin, yMax, top, bottom);
                canvas.drawLine(left, py, right, py, gridPaint);
                canvas.drawText(String.format("%." + stepDecimals + "f", y),
                        left - 10f * density, py + 4f * density, labelPaint);
            }
        }

        // Session mean, when it sits inside the scale.
        float mean = compact ? Float.NaN : history.stats().mean();
        if (!Float.isNaN(mean) && mean >= yMin && mean <= yMax) {
            float py = toY(mean, yMin, yMax, top, bottom);
            canvas.drawLine(left, py, right, py, meanPaint);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            labelPaint.setColor(meanPaint.getColor() | 0xFF000000);
            canvas.drawText(String.format("moy. %." + decimals + "f", mean), right - 4f * density, py - 6f * density, labelPaint);
            labelPaint.setColor(dim);
        }

        // A pause in the readings (stopped, for fuel economy) breaks the curve
        // rather than drawing a straight line across it.
        long gap = Math.max(GAP_MS, windowMs / 60);
        line.reset();
        fill.reset();
        float px = 0, py = 0;
        for (int i = first; i < n; i++) {
            float x = left + (right - left) * (history.timeAt(i) - from) / (float) windowMs;
            float y = toY(history.valueAt(i), yMin, yMax, top, bottom);
            boolean broken = i > first && history.timeAt(i) - history.timeAt(i - 1) > gap;
            if (i == first || broken) {
                if (broken) {
                    fill.lineTo(px, bottom);
                    fill.close();
                }
                line.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            px = x;
            py = y;
        }
        fill.lineTo(px, bottom);
        fill.close();

        if (colorizer != null) {
            // Stretch by stretch, each in the colour of where it starts.
            float qx = 0, qy = 0;
            for (int i = first; i < n; i++) {
                float x = left + (right - left) * (history.timeAt(i) - from) / (float) windowMs;
                float y = toY(history.valueAt(i), yMin, yMax, top, bottom);
                if (i > first && history.timeAt(i) - history.timeAt(i - 1) <= gap) {
                    linePaint.setColor(colorizer.colorFor(history.valueAt(i - 1)));
                    canvas.drawLine(qx, qy, x, y, linePaint);
                }
                qx = x;
                qy = y;
            }
            dotPaint.setColor(colorizer.colorFor(history.valueAt(n - 1)));
            canvas.drawCircle(px, py, 4.5f * density, dotPaint);
        } else {
            if (!compact) {
                if (shader == null || shaderTop != top || shaderBottom != bottom || shaderColor != lineColor) {
                    shader = new LinearGradient(0, top, 0, bottom,
                            withAlpha(lineColor, 70), withAlpha(lineColor, 0), Shader.TileMode.CLAMP);
                    shaderTop = top;
                    shaderBottom = bottom;
                    shaderColor = lineColor;
                }
                fillPaint.setShader(shader);
                canvas.drawPath(fill, fillPaint);
            }
            linePaint.setColor(lineColor);
            canvas.drawPath(line, linePaint);
            if (!compact) {
                dotPaint.setColor(lineColor);
                canvas.drawCircle(px, py, 4.5f * density, dotPaint);
            }
        }

        // Keeps the curve sliding left between readings.
        postInvalidateDelayed(250);
    }

    private void drawTimeAxis(Canvas canvas, float left, float right, float bottom) {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        int ticks = 4;
        for (int i = 0; i <= ticks; i++) {
            float x = left + (right - left) * i / ticks;
            long agoS = windowMs / 1000 * (ticks - i) / ticks;
            String text;
            if (agoS == 0) text = "maintenant";
            else if (agoS % 60 == 0) text = "\u2212" + (agoS / 60) + " min";
            else text = "\u2212" + agoS + " s";
            if (i == ticks) labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(text, i == ticks ? right : x, bottom + 18f * density, labelPaint);
        }
    }

    private static float toY(float v, float yMin, float yMax, float top, float bottom) {
        return bottom - (bottom - top) * (v - yMin) / (yMax - yMin);
    }

    /** Enough decimals to write the step exactly: 2.5 → 1, 0.25 → 2, 50 → 0. */
    static int decimalsFor(float step) {
        for (int d = 0; d < 4; d++) {
            double scaled = step * Math.pow(10, d);
            if (Math.abs(scaled - Math.round(scaled)) < 1e-4) return d;
        }
        return 3;
    }

    /** 1, 2, 2.5 or 5 times a power of ten, the closest above the raw step. */
    static float niceStep(float raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double r = raw / magnitude;
        double nice = r <= 1 ? 1 : r <= 2 ? 2 : r <= 2.5 ? 2.5 : r <= 5 ? 5 : 10;
        return (float) (nice * magnitude);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
