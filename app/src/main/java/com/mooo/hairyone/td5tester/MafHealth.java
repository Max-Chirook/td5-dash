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
 */
public final class MafHealth {

    public enum Status { MEASURING, GOOD, WATCH, REPLACE }

    /** Boost above which the TD5 keeps its EGR valve shut. */
    public static final double MIN_BOOST_BAR = 0.4;
    /** Samples a trip needs before its figure counts. */
    public static final int MIN_SAMPLES = 40;
    public static final int HISTORY = 10;

    private double tripSum = 0;
    private int tripCount = 0;
    private final List<Double> history = new ArrayList<>();

    /** Whether the conditions hold for a fair comparison. */
    public static boolean conditionsMet(double coolantC, double warmC, double boostBar) {
        return coolantC >= warmC && boostBar >= MIN_BOOST_BAR;
    }

    /** One pair of air masses read together, in the same units. */
    public void add(double mafMass, double mapMass) {
        if (!(mafMass > 0) || !(mapMass > 0)) return;
        tripSum += (mafMass - mapMass) / mapMass * 100.0;
        tripCount++;
    }

    public int tripSamples() { return tripCount; }

    /** Closes the trip: its figure joins the history if it had enough samples. */
    public void finishTrip() {
        if (tripCount >= MIN_SAMPLES) {
            history.add(tripSum / tripCount);
            while (history.size() > HISTORY) history.remove(0);
        }
        tripSum = 0;
        tripCount = 0;
    }

    /** One figure per trip, oldest first, the current one last once it counts. */
    public List<Double> trips() {
        List<Double> all = new ArrayList<>(history);
        if (tripCount >= MIN_SAMPLES) all.add(tripSum / tripCount);
        return all;
    }

    /** Mean drift in percent, minus when the meter reads low; NaN until measured. */
    public double deviationPercent() {
        List<Double> all = trips();
        if (all.isEmpty()) return Double.NaN;
        double sum = 0;
        for (double d : all) sum += d;
        return sum / all.size();
    }

    public Status status(double warnPercent, double alertPercent) {
        double d = deviationPercent();
        if (Double.isNaN(d)) return Status.MEASURING;
        double a = Math.abs(d);
        if (a > alertPercent) return Status.REPLACE;
        if (a > warnPercent) return Status.WATCH;
        return Status.GOOD;
    }

    public void reset() {
        history.clear();
        tripSum = 0;
        tripCount = 0;
    }

    public Map<String, Double> toMap() {
        Map<String, Double> m = new HashMap<>();
        m.put("trip_sum", tripSum);
        m.put("trip_count", (double) tripCount);
        m.put("n", (double) history.size());
        for (int i = 0; i < history.size(); i++) m.put("h" + i, history.get(i));
        return m;
    }

    /**
     * Rebuilds a saved state. A trip left open when the app was closed is
     * finished then, so its figure is not lost.
     */
    public static MafHealth fromMap(Map<String, Double> m) {
        MafHealth h = new MafHealth();
        int n = (int) get(m, "n");
        for (int i = 0; i < n && i < HISTORY; i++) {
            Double v = m.get("h" + i);
            if (v != null && !Double.isNaN(v)) h.history.add(v);
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
