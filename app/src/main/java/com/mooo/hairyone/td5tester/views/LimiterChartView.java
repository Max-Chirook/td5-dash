package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.SignalHistory;

/**
 * The fuel against its limits over the last seconds: the driver's demand in
 * grey, the injected quantity in white, the smoke and torque limits dashed in
 * orange and red, and the stretches where a limit held the fuel back tinted.
 */
public class LimiterChartView extends View {

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint();
    private final Path path = new Path();
    private final float d;
    private final int ink, inkStrong, grey, orange, red, dim;

    private SignalHistory demand, injected, smoke, torque, state;
    private long windowMs = 30_000;
    private final DashPathEffect dashEffect;

    public LimiterChartView(Context context) { this(context, null); }

    public LimiterChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        d = getResources().getDisplayMetrics().density;
        ink = ContextCompat.getColor(context, R.color.ink);
        inkStrong = ContextCompat.getColor(context, R.color.inkStrong);
        grey = ContextCompat.getColor(context, R.color.labelSecondary);
        orange = ContextCompat.getColor(context, R.color.stateHigh);
        red = ContextCompat.getColor(context, R.color.stateAlert);
        dim = ContextCompat.getColor(context, R.color.dim);
        grid.setColor(0xFF1F2125);
        grid.setStrokeWidth(Math.max(1f, d));
        label.setTextSize(10.5f * d);
        label.setFontFeatureSettings("tnum");
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        dashEffect = new DashPathEffect(new float[]{5 * d, 4 * d}, 0);
    }

    public void setSources(SignalHistory demand, SignalHistory injected, SignalHistory smoke,
                           SignalHistory torque, SignalHistory state) {
        this.demand = demand;
        this.injected = injected;
        this.smoke = smoke;
        this.torque = torque;
        this.state = state;
        invalidate();
    }

    public void setWindowMs(long windowMs) { this.windowMs = windowMs; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float left = 40 * d, right = w - 6 * d, top = 10 * d, bottom = h - 24 * d;
        if (right <= left || bottom <= top || demand == null) return;
        long now = SystemClock.elapsedRealtime();
        long from = now - windowMs;
        float yMax = 60;

        for (int v = 0; v <= 60; v += 20) {
            float y = bottom - (bottom - top) * v / yMax;
            canvas.drawLine(left, y, right, y, grid);
            label.setTextAlign(Paint.Align.RIGHT);
            label.setColor(dim);
            canvas.drawText(String.valueOf(v), left - 10 * d, y + 4 * d, label);
        }

        // Tinted stretches where a limit held the fuel back.
        int n = state.size();
        for (int i = 0; i < n; i++) {
            long t = state.timeAt(i);
            if (t < from) continue;
            int s = Math.round(state.valueAt(i));
            if (s == 0) continue;
            long t1 = i + 1 < n ? state.timeAt(i + 1) : now;
            float x0 = x(t, from, left, right), x1 = x(Math.min(t1, t + 3000), from, left, right);
            zone.setColor(((s == 1 ? orange : red) & 0x00FFFFFF) | (24 << 24));
            canvas.drawRect(x0, top, x1, bottom, zone);
        }

        series(canvas, torque, red, 1.5f, true, from, left, right, top, bottom, yMax);
        series(canvas, smoke, orange, 1.5f, true, from, left, right, top, bottom, yMax);
        series(canvas, demand, grey, 1.6f, false, from, left, right, top, bottom, yMax);
        series(canvas, injected, inkStrong, 2.6f, false, from, left, right, top, bottom, yMax);

        label.setColor(dim);
        for (int i = 0; i <= 4; i++) {
            float xx = left + (right - left) * i / 4;
            long ago = windowMs / 1000 * (4 - i) / 4;
            String t = ago == 0 ? "maintenant" : ago % 60 == 0 ? "−" + ago / 60 + " min" : "−" + ago + " s";
            label.setTextAlign(i == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(t, i == 4 ? right : xx, bottom + 18 * d, label);
        }
        postInvalidateDelayed(250);
    }

    private void series(Canvas canvas, SignalHistory hist, int color, float width, boolean dash,
                        long from, float left, float right, float top, float bottom, float yMax) {
        if (hist == null) return;
        path.reset();
        boolean started = false;
        for (int i = 0; i < hist.size(); i++) {
            if (hist.timeAt(i) < from) continue;
            float px = x(hist.timeAt(i), from, left, right);
            float py = bottom - (bottom - top) * Math.min(hist.valueAt(i), yMax) / yMax;
            if (!started) { path.moveTo(px, py); started = true; } else path.lineTo(px, py);
        }
        stroke.setColor(dash ? (color & 0x00FFFFFF) | (204 << 24) : color);
        stroke.setStrokeWidth(width * d);
        stroke.setPathEffect(dash ? dashEffect : null);
        canvas.drawPath(path, stroke);
    }

    private float x(long t, long from, float left, float right) {
        return left + (right - left) * (t - from) / (float) windowMs;
    }
}
