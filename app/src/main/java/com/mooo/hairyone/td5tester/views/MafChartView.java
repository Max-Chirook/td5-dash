package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The air meter's drift: a coloured scale from -30 % to +30 % with a marker,
 * then the figure of each recent trip as a line, so a slow drift shows.
 */
public class MafChartView extends View {

    private static final float RANGE = 30f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float density;

    private double deviation = Double.NaN;
    private float warn = 8f;
    private float alert = 15f;
    private List<Double> trips = new ArrayList<>();

    public MafChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        text.setTextSize(11f * density);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void set(double deviation, List<Double> trips, float warn, float alert) {
        this.deviation = deviation;
        this.trips = new ArrayList<>(trips);
        this.warn = warn;
        this.alert = alert;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (130 * density));
    }

    private int color(int res) { return ContextCompat.getColor(getContext(), res); }

    private float x(double percent, float left, float width) {
        double p = Math.max(-RANGE, Math.min(RANGE, percent));
        return left + (float) ((p + RANGE) / (2 * RANGE)) * width;
    }

    private int dim(int c, float a) {
        int bg = color(R.color.track);
        return android.graphics.Color.rgb(
                (int) (android.graphics.Color.red(bg) * (1 - a) + android.graphics.Color.red(c) * a),
                (int) (android.graphics.Color.green(bg) * (1 - a) + android.graphics.Color.green(c) * a),
                (int) (android.graphics.Color.blue(bg) * (1 - a) + android.graphics.Color.blue(c) * a));
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = 4 * density;
        float width = getWidth() - 8 * density;
        float top = 10 * density;
        float thick = 8 * density;

        // The scale: red at the ends, orange, green in the middle.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dim(color(R.color.stateAlert), 0.45f));
        rect.set(left, top, left + width, top + thick);
        canvas.drawRoundRect(rect, thick / 2, thick / 2, paint);
        paint.setColor(dim(color(R.color.stateHigh), 0.40f));
        canvas.drawRect(x(-alert, left, width), top, x(alert, left, width), top + thick, paint);
        paint.setColor(dim(color(R.color.stateNormal), 0.40f));
        canvas.drawRect(x(-warn, left, width), top, x(warn, left, width), top + thick, paint);

        if (!Double.isNaN(deviation)) {
            paint.setColor(color(R.color.label));
            paint.setStrokeWidth(3 * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float mx = x(deviation, left, width);
            canvas.drawLine(mx, top - 6 * density, mx, top + thick + 6 * density, paint);
        }

        text.setColor(color(R.color.labelTertiary));
        float ty = top + thick + 20 * density;
        canvas.drawText("−30 %", left + 16 * density, ty, text);
        canvas.drawText("−" + Math.round(warn), x(-warn, left, width), ty, text);
        canvas.drawText("0", x(0, left, width), ty, text);
        canvas.drawText("+" + Math.round(warn), x(warn, left, width), ty, text);
        canvas.drawText("+30 %", left + width - 16 * density, ty, text);

        // The last trips, oldest on the left, around a dashed zero line.
        float chartTop = ty + 18 * density;
        float chartBottom = getHeight() - 4 * density;
        float mid = (chartTop + chartBottom) / 2;
        float half = (chartBottom - chartTop) / 2;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1 * density);
        paint.setColor(color(R.color.track));
        paint.setPathEffect(new DashPathEffect(new float[] { 4 * density, 4 * density }, 0));
        canvas.drawLine(left, mid, left + width, mid, paint);
        paint.setPathEffect(null);

        if (trips.size() >= 2) {
            path.reset();
            for (int i = 0; i < trips.size(); i++) {
                float px = left + width * i / (trips.size() - 1);
                double d = Math.max(-RANGE, Math.min(RANGE, trips.get(i)));
                float py = mid - (float) (d / RANGE) * half;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            paint.setColor(color(R.color.label));
            paint.setStrokeWidth(2 * density);
            canvas.drawPath(path, paint);
        } else if (trips.size() == 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(R.color.label));
            double d = Math.max(-RANGE, Math.min(RANGE, trips.get(0)));
            canvas.drawCircle(left + width / 2, mid - (float) (d / RANGE) * half, 3 * density, paint);
        }
    }
}
