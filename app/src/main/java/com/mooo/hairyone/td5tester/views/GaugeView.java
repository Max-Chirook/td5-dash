package com.mooo.hairyone.td5tester.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.R;

/**
 * A thin-arc gauge that stays monochrome while a reading sits in its normal
 * band, and only takes colour when it leaves it, so the one thing going wrong
 * is the one thing that catches the eye.
 */
public class GaugeView extends View {

    public static final int STATE_NORMAL = 0;
    public static final int STATE_COLD = 1;
    public static final int STATE_WARN = 2;
    public static final int STATE_ALERT = 3;

    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final RectF cardBounds = new RectF();

    // Protected so the other gauge styles (ClusterDialView, BarGaugeView,
    // ReadoutView) share the reading logic and only draw differently.
    protected final float density;
    private float cornerRadius;

    protected float minValue = 0f;
    protected float maxValue = 100f;
    private float targetValue = 0f;
    protected float shownValue = 0f;
    protected int decimals = 0;
    protected String unit = "";
    protected String caption = "";
    protected boolean hasValue = false;
    private boolean hero = false;
    protected String secondary = null;
    // A small line under the caption, styled like the session peak.
    protected String footnote = null;

    private ValueAnimator animator;

    // A failed ECU read leaves the last value on screen, which would otherwise
    // be indistinguishable from a live one.
    private static final long STALE_AFTER_MS = 2500;
    /** For readings polled in turn rather than on every pass. */
    public static final long STALE_SLOW_MS = 8000;
    /** For ambient pressure, which is only read now and then. */
    public static final long STALE_RARE_MS = 60000;
    private long staleAfterMs = STALE_AFTER_MS;
    protected boolean stale = false;
    private final Runnable goStale = () -> { stale = true; invalidate(); };

    private boolean trackPeak = false;
    private float peak = Float.NEGATIVE_INFINITY;
    // Session peaks by gauge id, kept outside the view so they survive the
    // screen being rebuilt when the radio switches to split screen.
    private static final java.util.Map<Integer, Float> PEAKS = new java.util.HashMap<>();

    private String infoTitle = null;
    private String infoBody = null;

    private float coldBelow = Float.NEGATIVE_INFINITY;
    private float alertBelow = Float.NEGATIVE_INFINITY;
    private float warnBelow = Float.NEGATIVE_INFINITY;
    protected float warnAbove = Float.POSITIVE_INFINITY;
    protected float alertAbove = Float.POSITIVE_INFINITY;
    private Float origin = null;

    protected int neutralColor, coldColor, warnColor, alertColor, surfaceColor, trackColor;

    public GaugeView(Context context) { this(context, null); }

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        cornerRadius = 18f * density;

        neutralColor = ContextCompat.getColor(context, R.color.gaugeNeutral);
        coldColor = ContextCompat.getColor(context, R.color.stateInfo);
        warnColor = ContextCompat.getColor(context, R.color.stateHigh);
        alertColor = ContextCompat.getColor(context, R.color.stateAlert);
        surfaceColor = ContextCompat.getColor(context, R.color.surface);
        trackColor = ContextCompat.getColor(context, R.color.track);

        cardPaint.setColor(surfaceColor);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(trackColor);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        // Tabular figures, so digits keep their column and the number stops jittering.
        valuePaint.setFontFeatureSettings("tnum");

        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        captionPaint.setColor(ContextCompat.getColor(context, R.color.labelSecondary));

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            minValue = a.getFloat(R.styleable.GaugeView_gaugeMin, minValue);
            maxValue = a.getFloat(R.styleable.GaugeView_gaugeMax, maxValue);
            decimals = a.getInt(R.styleable.GaugeView_gaugeDecimals, decimals);
            hero = a.getBoolean(R.styleable.GaugeView_gaugeHero, false);
            if (a.hasValue(R.styleable.GaugeView_gaugeUnit)) unit = a.getString(R.styleable.GaugeView_gaugeUnit);
            if (a.hasValue(R.styleable.GaugeView_gaugeCaption)) caption = a.getString(R.styleable.GaugeView_gaugeCaption);
            a.recycle();
        }
    }

    /** Everything between warnBelow and warnAbove reads as normal and stays monochrome. */
    public void setBands(float alertBelow, float warnBelow, float warnAbove, float alertAbove) {
        this.alertBelow = alertBelow;
        this.warnBelow = warnBelow;
        this.warnAbove = warnAbove;
        this.alertAbove = alertAbove;
        invalidate();
    }

    /** Under this the reading is simply cold, which is information rather than a fault. */
    public void setColdBelow(float coldBelow) {
        this.coldBelow = coldBelow;
        invalidate();
    }

    /** Makes the arc grow from this value instead of the minimum, for readings centred on zero. */
    public void setOrigin(float origin) {
        this.origin = origin;
        invalidate();
    }

    /** How long without a reading before the gauge greys out as lost. */
    public void setStaleAfter(long ms) { this.staleAfterMs = ms; }

    /** Keeps the highest reading of the session, shown under the caption. */
    public void trackPeak() {
        this.trackPeak = true;
        Float saved = PEAKS.get(getId());
        if (saved != null) peak = saved;
    }

    /** Attaches the note shown when the gauge is tapped, and marks it tappable. */
    public void setInfo(String title, String body) {
        this.infoTitle = title;
        this.infoBody = body;
        setClickable(true);
        invalidate();
    }

    public String getInfoTitle() { return infoTitle; }

    public String getInfoBody() { return infoBody; }

    public boolean hasPeak() { return trackPeak && peak > Float.NEGATIVE_INFINITY; }

    public String getPeakText() {
        if (!hasPeak()) return null;
        String u = unit == null ? "" : " " + unit.toLowerCase();
        return String.format("%." + decimals + "f%s", peak, u);
    }

    public void resetPeak() {
        peak = Float.NEGATIVE_INFINITY;
        PEAKS.remove(getId());
        invalidate();
    }

    public void setValue(float value) {
        this.targetValue = value;
        stale = false;
        removeCallbacks(goStale);
        postDelayed(goStale, staleAfterMs);
        if (trackPeak && value > peak) {
            peak = value;
            PEAKS.put(getId(), peak);
        }
        if (!hasValue) {
            hasValue = true;
            shownValue = value;
            invalidate();
            return;
        }
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(shownValue, value);
        animator.setDuration(260);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            shownValue = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void clearValue() {
        if (animator != null) animator.cancel();
        removeCallbacks(goStale);
        stale = false;
        hasValue = false;
        invalidate();
    }

    public boolean isStale() { return stale && hasValue; }

    public void setCaption(String caption) { this.caption = caption; invalidate(); }

    /** Changes the unit under the reading, for a gauge that switches between two. */
    public void setUnit(String unit) {
        if (unit == null ? this.unit == null : unit.equals(this.unit)) return;
        this.unit = unit;
        invalidate();
    }

    /** A second line under the main reading, used by the hero gauge for speed. */
    public void setSecondary(String secondary) { this.secondary = secondary; invalidate(); }

    /** A small grey line under the caption, the same as the "max" line of the other gauges. */
    public void setFootnote(String footnote) { this.footnote = footnote; invalidate(); }

    // The colour of that line, when it says something other than the trip
    // (the long-term total). 0 keeps the usual grey.
    private int footColor = 0;

    public void setFootColor(int color) {
        if (footColor == color) return;
        footColor = color;
        invalidate();
    }

    protected int footColor(int fallback) { return footColor != 0 ? footColor : fallback; }

    public int getState() {
        if (!hasValue) return STATE_NORMAL;
        if (targetValue >= alertAbove || targetValue <= alertBelow) return STATE_ALERT;
        if (targetValue >= warnAbove || targetValue <= warnBelow) return STATE_WARN;
        if (targetValue < coldBelow) return STATE_COLD;
        return STATE_NORMAL;
    }

    /** The grey line under the reading: the session peak if tracked, else the footnote. */
    protected String footText() {
        if (!hasValue) return null;
        if (trackPeak && peak > Float.NEGATIVE_INFINITY) {
            return String.format("max %." + decimals + "f", peak);
        }
        return footnote;
    }

    /** Where a value sits between the minimum and the maximum, from 0 to 1. */
    protected float fractionOf(float value) {
        float span = maxValue - minValue;
        if (span <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (value - minValue) / span));
    }

    protected int stateColor() {
        switch (getState()) {
            case STATE_ALERT: return alertColor;
            case STATE_WARN: return warnColor;
            case STATE_COLD: return coldColor;
            default: return neutralColor;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int state = stale ? STATE_NORMAL : getState();
        int color = stale ? trackColor : stateColor();

        // The card itself picks up the alert colour, so a problem is visible
        // from the corner of the eye and not only in the arc.
        cardBounds.set(0, 0, w, h);
        if (state == STATE_ALERT) {
            cardPaint.setColor(blend(surfaceColor, alertColor, 0.22f));
        } else if (state == STATE_WARN) {
            cardPaint.setColor(blend(surfaceColor, warnColor, 0.12f));
        } else {
            cardPaint.setColor(surfaceColor);
        }
        canvas.drawRoundRect(cardBounds, cornerRadius, cornerRadius, cardPaint);

        float pad = (hero ? 20f : 14f) * density;
        float captionSize = Math.min(w, h) * (hero ? 0.062f : 0.10f);
        float captionGap = captionSize * 2.0f;
        float dialSize = Math.min(w - pad * 2, h - captionGap - pad);
        float stroke = (hero ? 8f : 5f) * density;

        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        glowPaint.setStrokeWidth(stroke * 2.6f);

        float cx = w / 2f;
        float cy = pad + (h - captionGap - pad) / 2f - pad / 2f;
        float radius = dialSize / 2f - stroke;

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        if (hasValue) {
            float span = maxValue - minValue;
            float fraction = span <= 0 ? 0 : (shownValue - minValue) / span;
            fraction = Math.max(0f, Math.min(1f, fraction));
            float from = 0f;
            if (origin != null && span > 0) {
                from = Math.max(0f, Math.min(1f, (origin - minValue) / span));
            }
            float start = START_ANGLE + SWEEP_ANGLE * Math.min(from, fraction);
            float sweep = SWEEP_ANGLE * Math.abs(fraction - from);
            if (sweep > 0.5f) {
                glowPaint.setColor(withAlpha(color, 46));
                canvas.drawArc(arcBounds, start, sweep, false, glowPaint);
                arcPaint.setColor(color);
                canvas.drawArc(arcBounds, start, sweep, false, arcPaint);
            }
        }

        String text = hasValue ? String.format("%." + decimals + "f", shownValue) : "—";
        float size = radius * (hero ? 0.72f : 0.62f);
        int valueColor = state == STATE_NORMAL ? ContextCompat.getColor(getContext(), R.color.label) : color;
        if (stale) valueColor = ContextCompat.getColor(getContext(), R.color.labelTertiary);
        valuePaint.setColor(valueColor);
        valuePaint.setTextSize(size);
        float maxWidth = radius * 1.42f;
        float measured = valuePaint.measureText(text);
        if (measured > maxWidth) valuePaint.setTextSize(size * maxWidth / measured);
        canvas.drawText(text, cx, cy + radius * (secondary != null ? -0.04f : 0.08f), valuePaint);

        if (unit != null && !unit.isEmpty()) {
            unitPaint.setColor(ContextCompat.getColor(getContext(), R.color.labelSecondary));
            unitPaint.setTextSize(radius * (hero ? 0.15f : 0.21f));
            unitPaint.setLetterSpacing(0.06f);
            canvas.drawText(unit, cx, cy + radius * (secondary != null ? 0.26f : 0.46f), unitPaint);
        }

        if (secondary != null) {
            unitPaint.setColor(ContextCompat.getColor(getContext(), R.color.label));
            unitPaint.setTextSize(radius * 0.28f);
            unitPaint.setLetterSpacing(0f);
            canvas.drawText(secondary, cx, cy + radius * 0.66f, unitPaint);
        }

        String footText = null;
        if (trackPeak && peak > Float.NEGATIVE_INFINITY && hasValue) {
            footText = String.format("max %." + decimals + "f", peak);
        } else if (footnote != null && hasValue) {
            footText = footnote;
        }
        boolean showPeak = footText != null;
        if (caption != null && !caption.isEmpty()) {
            captionPaint.setColor(ContextCompat.getColor(getContext(),
                    stale ? R.color.labelTertiary : R.color.labelSecondary));
            captionPaint.setTextSize(captionSize);
            captionPaint.setLetterSpacing(0.10f);
            // Sit under the dial rather than at the card's foot, so a narrow
            // card does not leave a hole between the two.
            float foot = h - captionSize * (showPeak ? 1.9f : 0.7f);
            float baseline = Math.min(cy + radius + captionSize * 1.7f, foot);
            canvas.drawText(caption, cx, baseline, captionPaint);

            if (showPeak) {
                captionPaint.setTextSize(captionSize * 0.82f);
                captionPaint.setLetterSpacing(0.02f);
                captionPaint.setColor(footColor(ContextCompat.getColor(getContext(), R.color.labelTertiary)));
                canvas.drawText(footText, cx, baseline + captionSize * 1.35f, captionPaint);
            }
        }

        if (stale) {
            unitPaint.setColor(ContextCompat.getColor(getContext(), R.color.stateHigh));
            unitPaint.setTextSize(captionSize * 0.9f);
            unitPaint.setLetterSpacing(0.08f);
            canvas.drawText("SIGNAL PERDU", cx, pad + captionSize, unitPaint);
        }

        if (infoBody != null) {
            float r = 7f * density;
            float ix = w - pad - r;
            float iy = pad + r;
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(1.4f * density);
            trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.labelTertiary));
            canvas.drawCircle(ix, iy, r, trackPaint);
            captionPaint.setColor(ContextCompat.getColor(getContext(), R.color.labelTertiary));
            captionPaint.setTextSize(r * 1.45f);
            captionPaint.setLetterSpacing(0f);
            canvas.drawText("i", ix, iy + r * 0.52f, captionPaint);
            trackPaint.setStrokeWidth(stroke);
            trackPaint.setColor(trackColor);
        }
    }

    protected static int blend(int base, int tint, float amount) {
        return Color.rgb(
                (int) (Color.red(base) * (1 - amount) + Color.red(tint) * amount),
                (int) (Color.green(base) * (1 - amount) + Color.green(tint) * amount),
                (int) (Color.blue(base) * (1 - amount) + Color.blue(tint) * amount));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
