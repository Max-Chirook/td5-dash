package com.mooo.hairyone.td5tester;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How far the air meter has drifted from the ECU's own estimate made from the
 * manifold pressure. The two only agree when the EGR valve is shut, so a
 * sample counts only with the engine warm and the turbo pushing. Each trip
 * gives one figure; the last ten are kept, so a slow drift shows over weeks.
 *
 * Two ways to judge it. Against the factory estimate, the gap itself is the
 * verdict. On a remapped engine that estimate, or the meter's range, may have
 * been changed, so a gap says little: the first trips then become this
 * vehicle's own reference, and only a drift from it is judged.
 *
 * The gap is also kept by turbo pressure. A gap that grows with the pressure
 * points to a meter at the end of its range or a changed map, not to wear.
 */
public final class MafHealth {

    public enum Status { MEASURING, GOOD, WATCH, REPLACE }

    /** Boost above which the TD5 keeps its EGR valve shut. */
    public static final double MIN_BOOST_BAR = 0.4;
    /** Samples a trip needs before its figure counts. */
    public static final int MIN_SAMPLES = 40;
    public static final int HISTORY = 10;
    /** Trips that make up a learnt reference. */
    public static final int REFERENCE_TRIPS = 3;

    /** Turbo pressure bands the gap is kept by, lower edges in bar. */
    public static final double[] BAND_EDGES = {0.4, 0.7, 1.0};
    public static final String[] BAND_LABELS = {"0,4 – 0,7 bar", "0,7 – 1,0 bar", "plus de 1,0 bar"};
    /** Samples a pressure band needs before its gap is shown. */
    public static final int MIN_BAND_SAMPLES = 20;

    private double tripSum = 0;
    private int tripCount = 0;
    private final List<Double> history = new ArrayList<>();
    private boolean learnt = false;
    private double refSum = 0;
    private int refTrips = 0;
    private final double[] bandSum = new double[BAND_EDGES.length];
    private final int[] bandCount = new int[BAND_EDGES.length];

    /** Whether the conditions hold for a fair comparison. */
    public static boolean conditionsMet(double coolantC, double warmC, double boostBar) {
        return coolantC >= warmC && boostBar >= MIN_BOOST_BAR;
    }

    /**
     * Largest change from one reading to the next that still counts as
     * steady. Under a sharp change the meter and the manifold estimate do
     * not react at the same speed, and the comparison would be unfair.
     */
    public static final double MAX_RPM_STEP = 150;
    public static final double MAX_BOOST_STEP = 0.08;

    /** Whether engine speed and boost held still since the previous reading. */
    public static boolean isSteady(double prevRpm, double rpm, double prevBar, double bar) {
        if (Double.isNaN(prevRpm) || Double.isNaN(rpm) || Double.isNaN(prevBar) || Double.isNaN(bar)) {
            return false;
        }
        return Math.abs(rpm - prevRpm) <= MAX_RPM_STEP && Math.abs(bar - prevBar) <= MAX_BOOST_STEP;
    }

    public static int bandOf(double boostBar) {
        if (Double.isNaN(boostBar) || boostBar < BAND_EDGES[0]) return -1;
        for (int b = BAND_EDGES.length - 1; b >= 0; b--) if (boostBar >= BAND_EDGES[b]) return b;
        return -1;
    }

    /** One pair of air masses read together, in the same units. */
    public void add(double mafMass, double mapMass) { add(mafMass, mapMass, Double.NaN); }

    /** The same, with the turbo pressure they were read at. */
    public void add(double mafMass, double mapMass, double boostBar) {
        if (!(mafMass > 0) || !(mapMass > 0)) return;
        double gap = (mafMass - mapMass) / mapMass * 100.0;
        tripSum += gap;
        tripCount++;
        int b = bandOf(boostBar);
        if (b >= 0) { bandSum[b] += gap; bandCount[b]++; }
    }

    public int tripSamples() { return tripCount; }

    /** Closes the trip: its figure joins the history if it had enough samples. */
    public void finishTrip() {
        if (tripCount >= MIN_SAMPLES) {
            double figure = tripSum / tripCount;
            history.add(figure);
            while (history.size() > HISTORY) history.remove(0);
            if (refTrips < REFERENCE_TRIPS) { refSum += figure; refTrips++; }
        }
        tripSum = 0;
        tripCount = 0;
    }

    // ---- How it is judged. ----

    /** Whether the gap is judged against this vehicle's own first trips. */
    public boolean isLearnt() { return learnt; }

    public void setLearnt(boolean learnt) { this.learnt = learnt; }

    /** Trips counted into the reference so far, up to {@link #REFERENCE_TRIPS}. */
    public int referenceTrips() { return refTrips; }

    /** The vehicle's own gap, once learnt; NaN before. */
    public double reference() { return refTrips < REFERENCE_TRIPS ? Double.NaN : refSum / refTrips; }

    /** The raw gap per trip, oldest first, the current one last once it counts. */
    public List<Double> rawTrips() {
        List<Double> all = new ArrayList<>(history);
        if (tripCount >= MIN_SAMPLES) all.add(tripSum / tripCount);
        return all;
    }

    /** The mean raw gap in percent, minus when the meter reads low; NaN until measured. */
    public double rawPercent() {
        List<Double> all = rawTrips();
        if (all.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double d : all) sum += d;
        return sum / all.size();
    }

    /** What the verdict is drawn from: the raw gap, or the drift from the reference. */
    private double offset() { return learnt ? reference() : 0; }

    /** The figure per trip the verdict looks at. */
    public List<Double> trips() {
        List<Double> all = rawTrips();
        double off = offset();
        if (Double.isNaN(off)) return all;
        for (int i = 0; i < all.size(); i++) all.set(i, all.get(i) - off);
        return all;
    }

    /** The figure the verdict is drawn from, in percent; NaN while measuring or learning. */
    public double deviationPercent() { return rawPercent() - offset(); }

    public Status status(double warnPercent, double alertPercent) {
        double d = deviationPercent();
        if (Double.isNaN(d)) return Status.MEASURING;
        double a = Math.abs(d);
        if (a > alertPercent) return Status.REPLACE;
        if (a > warnPercent) return Status.WATCH;
        return Status.GOOD;
    }

    // ---- By turbo pressure. ----

    /** The raw gap in one pressure band since the last reset; NaN until enough samples. */
    public double bandPercent(int b) {
        return bandCount[b] < MIN_BAND_SAMPLES ? Double.NaN : bandSum[b] / bandCount[b];
    }

    /** How much the gap changes from the lowest to the highest band measured; NaN with fewer than two. */
    public double bandSpread() {
        double lo = Double.NaN, hi = Double.NaN;
        for (int b = 0; b < BAND_EDGES.length; b++) {
            double p = bandPercent(b);
            if (Double.isNaN(p)) continue;
            if (Double.isNaN(lo)) lo = p;
            hi = p;
        }
        int seen = 0;
        for (int b = 0; b < BAND_EDGES.length; b++) if (!Double.isNaN(bandPercent(b))) seen++;
        return seen < 2 ? Double.NaN : hi - lo;
    }

    /** Forgets everything measured, the learnt reference too, but keeps the way it is judged. */
    public void reset() {
        history.clear();
        tripSum = 0;
        tripCount = 0;
        refSum = 0;
        refTrips = 0;
        java.util.Arrays.fill(bandSum, 0);
        java.util.Arrays.fill(bandCount, 0);
    }

    public Map<String, Double> toMap() {
        Map<String, Double> m = new HashMap<>();
        m.put("trip_sum", tripSum);
        m.put("trip_count", (double) tripCount);
        m.put("n", (double) history.size());
        for (int i = 0; i < history.size(); i++) m.put("h" + i, history.get(i));
        m.put("learnt", learnt ? 1.0 : 0.0);
        m.put("ref_sum", refSum);
        m.put("ref_n", (double) refTrips);
        for (int b = 0; b < BAND_EDGES.length; b++) {
            m.put("bs" + b, bandSum[b]);
            m.put("bc" + b, (double) bandCount[b]);
        }
        return m;
    }

    /**
     * Rebuilds a saved state. A trip left open when the app was closed is
     * finished then, so its figure is not lost. A state saved before the
     * reference existed takes it from its oldest trips.
     */
    public static MafHealth fromMap(Map<String, Double> m) {
        MafHealth h = new MafHealth();
        int n = (int) get(m, "n");
        for (int i = 0; i < n && i < HISTORY; i++) {
            Double v = m.get("h" + i);
            if (v != null && !Double.isNaN(v)) h.history.add(v);
        }
        h.learnt = get(m, "learnt") > 0.5;
        if (m.containsKey("ref_n")) {
            h.refSum = get(m, "ref_sum");
            h.refTrips = (int) get(m, "ref_n");
        } else {
            for (int i = 0; i < h.history.size() && h.refTrips < REFERENCE_TRIPS; i++) {
                h.refSum += h.history.get(i);
                h.refTrips++;
            }
        }
        for (int b = 0; b < BAND_EDGES.length; b++) {
            h.bandSum[b] = get(m, "bs" + b);
            h.bandCount[b] = (int) get(m, "bc" + b);
        }
        h.tripSum = get(m, "trip_sum");
        h.tripCount = (int) get(m, "trip_count");
        h.finishTrip();
        return h;
    }

    private static double get(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v == null || Double.isNaN(v) ? 0 : v;
    }
}
