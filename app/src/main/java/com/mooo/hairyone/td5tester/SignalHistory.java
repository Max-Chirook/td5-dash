package com.mooo.hairyone.td5tester;

/**
 * The recent readings of one signal, oldest first, in a fixed ring so a long
 * drive never grows memory, plus the session statistics, which cover every
 * reading since the last reset and not only the ones still in the ring.
 */
public final class SignalHistory {

    private final long[] times;
    private final float[] values;
    private int head = 0;   // index of the oldest sample
    private int size = 0;

    private final RunningStats stats = new RunningStats();
    private float last = Float.NaN;
    private long lastTime = 0;

    public SignalHistory(int capacity) {
        times = new long[capacity];
        values = new float[capacity];
    }

    public void add(long timeMs, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        int index = (head + size) % times.length;
        times[index] = timeMs;
        values[index] = value;
        if (size < times.length) size++;
        else head = (head + 1) % times.length;
        stats.add(value);
        last = value;
        lastTime = timeMs;
    }

    public int size() { return size; }

    public long timeAt(int i) { return times[(head + i) % times.length]; }

    public float valueAt(int i) { return values[(head + i) % values.length]; }

    public float last() { return last; }

    public long lastTime() { return lastTime; }

    public RunningStats stats() { return stats; }

    public void resetStats() { stats.reset(); }

    public void clear() {
        head = 0;
        size = 0;
        last = Float.NaN;
        lastTime = 0;
        stats.reset();
    }
}
