package com.mooo.hairyone.td5tester;

import java.util.Map;

/**
 * Works out the gear in use from engine speed and road speed.
 *
 * The ECU has no gear sensor, but in a given gear the engine turns a fixed
 * number of revolutions per km/h. The R380 box spaces its five gears in known
 * proportions, so only one figure needs learning: the revolutions per km/h in
 * fifth, which depends on the axles and the tyres. Steady readings are kept
 * in a histogram, and the comb of the five gears is slid along it until it
 * catches the most readings. Low range (transfer box) multiplies every gear by
 * the same factor, and is recognised the same way.
 */
public final class GearEstimator {

    /** R380 gear ratios, first to fifth. */
    static final double[] RATIOS = {3.692, 2.132, 1.397, 1.000, 0.770};
    /** LT230 transfer box: low range against high range. */
    static final double LOW_FACTOR = 3.320 / 1.211;
    /** How far a reading may sit from a gear and still count as that gear. */
    static final double TOLERANCE = 0.06;
    /** Readings needed before the learnt figure is trusted. */
    public static final int MIN_SAMPLES = 150;

    // Log-spaced bins of revolutions per km/h, from 10 to 400.
    static final int BINS = 240;
    private static final double LOG_MIN = Math.log(10);
    private static final double LOG_MAX = Math.log(400);

    private final int[] bins = new int[BINS];
    private int samples = 0;
    private double fifth = Double.NaN;
    private int sinceFit = 0;

    private double prevRatio = Double.NaN;
    private int gear = 0;

    static int binOf(double ratio) {
        double f = (Math.log(ratio) - LOG_MIN) / (LOG_MAX - LOG_MIN);
        return (int) Math.floor(f * BINS);
    }

    static double ratioOf(int bin) {
        return Math.exp(LOG_MIN + (bin + 0.5) / BINS * (LOG_MAX - LOG_MIN));
    }

    /**
     * One reading. Returns the gear in use: 1 to 5 in high range, -1 to -5 in
     * low range, 0 when it cannot be told (stopped, clutch down, changing
     * gear, or not learnt yet).
     */
    public int update(double rpm, double kmh) {
        if (Double.isNaN(rpm) || Double.isNaN(kmh) || kmh < 8 || rpm < 600) {
            prevRatio = Double.NaN;
            gear = 0;
            return gear;
        }
        double ratio = rpm / kmh;
        boolean steady = !Double.isNaN(prevRatio) && Math.abs(ratio / prevRatio - 1) < 0.03;
        prevRatio = ratio;
        if (!steady) {
            gear = 0;
            return gear;
        }
        if (rpm >= 1000 && ratio > 10 && ratio < 400) {
            int b = binOf(ratio);
            if (b >= 0 && b < BINS) {
                bins[b]++;
                samples++;
                if (++sinceFit >= 50 || Double.isNaN(fifth)) fit();
            }
        }
        gear = gearFor(ratio);
        return gear;
    }

    public int gear() { return gear; }

    /** The gear for a ratio, from the learnt fifth; 0 when none fits. */
    int gearFor(double ratio) {
        if (Double.isNaN(fifth) || samples < MIN_SAMPLES) return 0;
        for (int g = 0; g < RATIOS.length; g++) {
            double expected = fifth * RATIOS[g] / RATIOS[4];
            if (Math.abs(ratio / expected - 1) < TOLERANCE) return g + 1;
        }
        for (int g = 0; g < RATIOS.length; g++) {
            double expected = fifth * RATIOS[g] / RATIOS[4] * LOW_FACTOR;
            if (Math.abs(ratio / expected - 1) < TOLERANCE) return -(g + 1);
        }
        return 0;
    }

    /**
     * Slides the comb of gears along the histogram. On a tie the largest
     * figure for fifth wins: a drive spent in fifth alone would otherwise
     * fit just as well read as fourth.
     */
    void fit() {
        sinceFit = 0;
        if (samples < 20) return;
        double bestScore = -1, best = Double.NaN;
        double[] scores = new double[BINS];
        for (int c = 0; c < BINS; c++) {
            double candidate = ratioOf(c);
            double score = 0;
            for (double r : RATIOS) score += countNear(candidate * r / RATIOS[4]);
            scores[c] = score;
            if (score > bestScore) bestScore = score;
        }
        if (bestScore <= 0) return;
        // The topmost stretch of near-best candidates; its middle is the fit.
        int top = BINS - 1;
        while (top > 0 && scores[top] < bestScore * 0.95) top--;
        int bottom = top;
        while (bottom > 0 && scores[bottom - 1] >= bestScore * 0.95) bottom--;
        best = ratioOf((top + bottom) / 2);
        // Refine: the weighted centre of the readings around the comb's fifth.
        double sum = 0, weight = 0;
        for (int b = 0; b < BINS; b++) {
            double r = ratioOf(b);
            if (Math.abs(r / best - 1) < TOLERANCE) { sum += Math.log(r) * bins[b]; weight += bins[b]; }
        }
        fifth = weight > 0 ? Math.exp(sum / weight) : best;
    }

    private double countNear(double ratio) {
        int lo = binOf(ratio * (1 - TOLERANCE)), hi = binOf(ratio * (1 + TOLERANCE));
        double n = 0;
        for (int b = Math.max(0, lo); b <= Math.min(BINS - 1, hi); b++) n += bins[b];
        return n;
    }

    /** Revolutions per km/h in fifth, NaN until learnt. */
    public double fifth() { return samples >= MIN_SAMPLES ? fifth : Double.NaN; }

    public int samples() { return samples; }

    /** How many gears have been seen in use, for the learning progress. */
    public int gearsSeen() {
        if (Double.isNaN(fifth()) ) return 0;
        int seen = 0;
        for (double r : RATIOS) if (countNear(fifth * r / RATIOS[4]) >= 20) seen++;
        return seen;
    }

    public static String label(int gear) {
        if (gear == 0) return "—";
        return gear > 0 ? gear + "e" : (-gear) + "L";
    }

    public void reset() {
        java.util.Arrays.fill(bins, 0);
        samples = 0;
        fifth = Double.NaN;
        prevRatio = Double.NaN;
        gear = 0;
    }

    public Map<String, Double> toMap() {
        Map<String, Double> m = new java.util.HashMap<>();
        for (int b = 0; b < BINS; b++) if (bins[b] > 0) m.put("b" + b, (double) bins[b]);
        return m;
    }

    public static GearEstimator fromMap(Map<String, Double> m) {
        GearEstimator g = new GearEstimator();
        for (Map.Entry<String, Double> e : m.entrySet()) {
            if (!e.getKey().startsWith("b")) continue;
            try {
                int b = Integer.parseInt(e.getKey().substring(1));
                if (b >= 0 && b < BINS) {
                    g.bins[b] = (int) Math.round(e.getValue());
                    g.samples += g.bins[b];
                }
            } catch (NumberFormatException ignored) { }
        }
        g.fit();
        return g;
    }
}
