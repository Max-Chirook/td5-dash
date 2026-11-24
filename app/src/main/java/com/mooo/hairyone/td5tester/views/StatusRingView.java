package com.mooo.hairyone.td5tester.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * The ring around the connect button, drawn like a dial: white once
 * connected, red without a signal, dim while connecting.
 */
public class StatusRingView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private int color;
    private final float density;

    public StatusRingView(Context context) { this(context, null); }

    public StatusRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        color = ContextCompat.getColor(context, R.color.dim);
    }

    public void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float r = Math.min(getWidth(), getHeight()) / 2f - 4 * density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float u = r / 116f;
        paint.setStrokeWidth(Math.max(1f, u));
        paint.setColor(ContextCompat.getColor(getContext(), R.color.hairline));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStrokeWidth(3 * u);
        paint.setColor(color);
        bounds.set(cx - r + 10 * u, cy - r + 10 * u, cx + r - 10 * u, cy + r - 10 * u);
        canvas.drawArc(bounds, 125, 290, false, paint);
        paint.setStrokeWidth(Math.max(1f, 1.2f * u));
        paint.setColor(0xFF55575C);
        for (int k = 0; k < 30; k++) {
            double a = Math.toRadians(125 + 290 * k / 29.0);
            float outer = r - 20 * u, inner = r - (k % 5 == 0 ? 30 : 25) * u;
            canvas.drawLine(cx + (float) (inner * Math.cos(a)), cy + (float) (inner * Math.sin(a)),
                    cx + (float) (outer * Math.cos(a)), cy + (float) (outer * Math.sin(a)), paint);
        }
    }
}
