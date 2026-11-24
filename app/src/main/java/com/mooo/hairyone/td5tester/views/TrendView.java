package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

import java.util.Collections;
import java.util.List;

/**
 * A small line of one figure per trip, with dashed thresholds, the last
 * point coloured by its verdict.
 */
public class TrendView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;

    private List<Double> values = Collections.emptyList();
    private double lo = 0, hi = 1;
    private double[] thresholds = new double[0];
    private int[] thresholdColors = new int[0];
    private int lastColor;

    public TrendView(Context context) { this(context, null); }

    public TrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(1.8f * d);
        line.setColor(ContextCompat.getColor(context, R.color.inkStrong));
        line.setAlpha(217);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(Math.max(1f, d));
        dashed.setPathEffect(new DashPathEffect(new float[]{3 * d, 3 * d}, 0));
        text.setTextSize(13 * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(ContextCompat.getColor(context, R.color.dim));
        lastColor = ContextCompat.getColor(context, R.color.ink);
    }

    public void setData(List<Double> values, double lo, double hi, double[] thresholds, int[] thresholdColors, int lastColor) {
        this.values = values;
        this.lo = lo;
        this.hi = hi;
        this.thresholds = thresholds;
        this.thresholdColors = thresholdColors;
        this.lastColor = lastColor;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = 4 * d, right = getWidth() - 6 * d, top = 6 * d, bottom = getHeight() - 6 * d;
        for (int i = 0; i < thresholds.length; i++) {
            float y = y(thresholds[i], top, bottom);
            dashed.setColor((thresholdColors[i] & 0x00FFFFFF) | (150 << 24));
            canvas.drawLine(left, y, right, y, dashed);
        }
        dashed.setColor(0xFF2A2C30);
        canvas.drawLine(left, bottom, right, bottom, dashed);
        int n = values.size();
        if (n == 0) {
            canvas.drawText("pas encore de trajet mesuré", (left + right) / 2, (top + bottom) / 2 + 4 * d, text);
            return;
        }
        path.reset();
        float px = 0, py = 0;
        dot.setColor(line.getColor());
        for (int i = 0; i < n; i++) {
            px = n == 1 ? right : left + (right - left) * i / (n - 1);
            py = y(values.get(i), top, bottom);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            if (i < n - 1) canvas.drawCircle(px, py, 2.4f * d, dot);
        }
        canvas.drawPath(path, line);
        dot.setColor(lastColor);
        canvas.drawCircle(px, py, 4.5f * d, dot);
    }

    private float y(double v, float top, float bottom) {
        double f = (v - lo) / (hi - lo);
        f = Math.max(0, Math.min(1, f));
        return (float) (bottom - (bottom - top) * f);
    }
}
