package com.mooo.hairyone.td5tester;

import java.util.HashMap;
import java.util.Map;

/**
 * Fuel and distance split into slots: speed bands, or gears. Each slot keeps
 * its own litres and kilometres, so its average says what driving that way
 * costs.
 */
public final class FuelBreakdown {

    /** Speed band edges in km/h: 5–30, 30–50, 50–70, 70–90, 90–110, 110+. */
    public static final double[] SPEED_EDGES = {5, 30, 50, 70, 90, 110};
    public static final String[] SPEED_LABELS = {"0–30", "30–50", "50–70", "70–90", "90–110", "110+"};
    /** A slot needs this distance before its average is shown. */
    public static final double MIN_KM = 0.5;

    private final double[] litres;
    private final double[] km;
    private long lastMs = -1;

    public FuelBreakdown(int slots) {
        litres = new double[slots];
        km = new double[slots];
    }

    public int slots() { return litres.length; }

    public static int speedSlot(double kmh) {
        if (Double.isNaN(kmh) || kmh < SPEED_EDGES[0]) return -1;
        for (int i = SPEED_EDGES.length - 1; i >= 0; i--) if (kmh >= SPEED_EDGES[i]) return i;
        return -1;
    }

    /** Adds the fuel and distance since the previous reading to one slot (-1: none). */
    public void add(int slot, double litresPerHour, double kmh, long nowMs) {
        if (lastMs >= 0 && slot >= 0 && slot < litres.length) {
            long step = nowMs - lastMs;
            if (step > 0 && step <= FuelEconomy.MAX_STEP_MS) {
                double hours = step / 3_600_000.0;
                if (litresPerHour > 0) litres[slot] += litresPerHour * hours;
                if (kmh > 0) km[slot] += kmh * hours;
            }
        }
        lastMs = nowMs;
    }

    public double litres(int slot) { return litres[slot]; }
    public double km(int slot) { return km[slot]; }

    public double totalKm() { double s = 0; for (double k : km) s += k; return s; }
    public double totalLitres() { double s = 0; for (double l : litres) s += l; return s; }

    /** L/100 km in a slot, NaN until it has enough distance. */
    public double average(int slot) {
        return km[slot] < MIN_KM ? Double.NaN : litres[slot] / km[slot] * 100.0;
    }

    /** Share of the distance driven in a slot, 0 to 1. */
    public double share(int slot) {
        double t = totalKm();
        return t <= 0 ? 0 : km[slot] / t;
    }

    /** The slot with the lowest average, or -1. */
    public int best() {
        int best = -1;
        for (int i = 0; i < litres.length; i++) {
            double a = average(i);
            if (!Double.isNaN(a) && (best < 0 || a < average(best))) best = i;
        }
        return best;
    }

    public void reset() {
        java.util.Arrays.fill(litres, 0);
        java.util.Arrays.fill(km, 0);
        lastMs = -1;
    }

    public Map<String, Double> toMap(String prefix) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < litres.length; i++) {
            m.put(prefix + "l" + i, litres[i]);
            m.put(prefix + "k" + i, km[i]);
        }
        return m;
    }

    public void restore(Map<String, Double> m, String prefix) {
        for (int i = 0; i < litres.length; i++) {
            litres[i] = Limiters.get(m, prefix + "l" + i);
            km[i] = Limiters.get(m, prefix + "k" + i);
        }
        lastMs = -1;
    }
}
