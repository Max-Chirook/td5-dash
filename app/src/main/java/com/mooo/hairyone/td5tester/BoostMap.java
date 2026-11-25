package com.mooo.hairyone.td5tester;

import java.util.HashMap;
import java.util.Map;

/**
 * Turbo pressure under firm load, by engine speed band. Each reading is the
 * peak one acceleration reached in the band, so a pull counts once however
 * long it lasts. The first pulls in each band become its reference; later
 * averages are compared with it, so a slow loss (a leak, a tired wastegate)
 * shows as a drop in percent.
 */
public final class BoostMap {

    /** Band centres in rpm; each band spans ±250 rpm. */
    public static final int[] CENTRES = {1000, 1500, 2000, 2500, 3000, 3500};
    /** Share of the full-pedal demand from which a reading counts: a firm acceleration. */
    public static final double LOAD_SHARE = 0.6;
    /** Readings of one band within one pull before its peak counts. */
    public static final int PULL_READINGS = 2;
    /** Pulls a band takes before its reference is set. */
    public static final int REFERENCE_SAMPLES = 8;
    /** Pulls a band needs before its average is shown. */
    public static final int MIN_SAMPLES = 3;

    private final double[] sum = new double[CENTRES.length];
    private final int[] count = new int[CENTRES.length];
    private final double[] max = new double[CENTRES.length];
    private final double[] refSum = new double[CENTRES.length];
    private final int[] refCount = new int[CENTRES.length];

    public BoostMap() { java.util.Arrays.fill(max, Double.NaN); }

    /** The peaks of one acceleration, band by band, until the foot lifts. */
    public static final class Pull {
        private final double[] peak = new double[CENTRES.length];
        private final int[] readings = new int[CENTRES.length];

        public Pull() { clear(); }

        public void add(double rpm, double bar) {
            int b = bandOf(rpm);
            if (b < 0 || Double.isNaN(bar)) return;
            readings[b]++;
            if (Double.isNaN(peak[b]) || bar > peak[b]) peak[b] = bar;
        }

        public boolean isEmpty() {
            for (int r : readings) if (r > 0) return false;
            return true;
        }

        /** The peak of each band read often enough, NaN elsewhere; the pull starts over. */
        public double[] finish() {
            double[] out = new double[CENTRES.length];
            for (int b = 0; b < CENTRES.length; b++) out[b] = readings[b] >= PULL_READINGS ? peak[b] : Double.NaN;
            clear();
            return out;
        }

        private void clear() {
            java.util.Arrays.fill(peak, Double.NaN);
            java.util.Arrays.fill(readings, 0);
        }
    }

    public static int bandOf(double rpm) {
        if (Double.isNaN(rpm)) return -1;
        int i = (int) Math.round((rpm - CENTRES[0]) / 500.0);
        return i >= 0 && i < CENTRES.length ? i : -1;
    }

    public void add(double rpm, double bar) {
        int b = bandOf(rpm);
        if (b < 0 || Double.isNaN(bar)) return;
        sum[b] += bar;
        count[b]++;
        if (Double.isNaN(max[b]) || bar > max[b]) max[b] = bar;
        if (refCount[b] < REFERENCE_SAMPLES) { refSum[b] += bar; refCount[b]++; }
    }

    public int bands() { return CENTRES.length; }
    public int count(int b) { return count[b]; }
    public double average(int b) { return count[b] < MIN_SAMPLES ? Double.NaN : sum[b] / count[b]; }
    public double max(int b) { return count[b] < MIN_SAMPLES ? Double.NaN : max[b]; }

    /** The band's reference, once it has all its readings. */
    public double reference(int b) { return refCount[b] < REFERENCE_SAMPLES ? Double.NaN : refSum[b] / refCount[b]; }

    public int total() { int n = 0; for (int c : count) n += c; return n; }

    /** Mean gap to the reference over the bands that have both, in percent. */
    public double deviationPercent() { return deviationFrom(this); }

    /** The same, against another map's reference (the total's, for a trip). */
    public double deviationFrom(BoostMap ref) {
        double s = 0; int n = 0;
        for (int b = 0; b < CENTRES.length; b++) {
            double a = average(b), r = ref.reference(b);
            if (!Double.isNaN(a) && !Double.isNaN(r) && r > 0.2) { s += (a / r - 1) * 100; n++; }
        }
        return n == 0 ? Double.NaN : s / n;
    }

    /** The band where the pressure peaks, or -1. */
    public int peakBand() {
        int best = -1;
        for (int b = 0; b < CENTRES.length; b++) {
            double a = average(b);
            if (!Double.isNaN(a) && (best < 0 || a > average(best))) best = b;
        }
        return best;
    }

    /** Averages and maxima start over; the reference stays. */
    public void reset() {
        java.util.Arrays.fill(sum, 0);
        java.util.Arrays.fill(count, 0);
        java.util.Arrays.fill(max, Double.NaN);
    }

    /** Forgets the reference too, after work on the turbo for instance. */
    public void resetReference() {
        java.util.Arrays.fill(refSum, 0);
        java.util.Arrays.fill(refCount, 0);
    }

    public Map<String, Double> toMap(String prefix) {
        Map<String, Double> m = new HashMap<>();
        for (int b = 0; b < CENTRES.length; b++) {
            m.put(prefix + "s" + b, sum[b]);
            m.put(prefix + "c" + b, (double) count[b]);
            if (!Double.isNaN(max[b])) m.put(prefix + "m" + b, max[b]);
            m.put(prefix + "rs" + b, refSum[b]);
            m.put(prefix + "rc" + b, (double) refCount[b]);
        }
        return m;
    }

    public void restore(Map<String, Double> m, String prefix) {
        for (int b = 0; b < CENTRES.length; b++) {
            sum[b] = Limiters.get(m, prefix + "s" + b);
            count[b] = (int) Math.round(Limiters.get(m, prefix + "c" + b));
            Double mx = m.get(prefix + "m" + b);
            max[b] = mx == null ? Double.NaN : mx;
            refSum[b] = Limiters.get(m, prefix + "rs" + b);
            refCount[b] = (int) Math.round(Limiters.get(m, prefix + "rc" + b));
        }
    }
}
