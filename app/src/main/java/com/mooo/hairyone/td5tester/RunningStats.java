package com.mooo.hairyone.td5tester;

/** Minimum, maximum and mean of every reading since the last reset. Missing readings (NaN) are skipped. */
public final class RunningStats {

    private long count = 0;
    private double sum = 0;
    private float min = Float.POSITIVE_INFINITY;
    private float max = Float.NEGATIVE_INFINITY;

    public void add(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        count++;
        sum += value;
        if (value < min) min = value;
        if (value > max) max = value;
    }

    public void reset() {
        count = 0;
        sum = 0;
        min = Float.POSITIVE_INFINITY;
        max = Float.NEGATIVE_INFINITY;
    }

    public boolean isEmpty() { return count == 0; }

    public long count() { return count; }

    public float min() { return isEmpty() ? Float.NaN : min; }

    public float max() { return isEmpty() ? Float.NaN : max; }

    public float mean() { return isEmpty() ? Float.NaN : (float) (sum / count); }
}
