package com.mooo.hairyone.td5tester;

import java.util.HashMap;
import java.util.Map;

/**
 * What is left in the tank, worked out from the fuel used since the last
 * visit to the pump: the ECU knows nothing of the fuel gauge. Unknown until
 * the driver says the tank was filled. Plain Java, saved as simple numbers.
 */
public final class FuelTank {

    /** Used for the range until a better average is known. */
    public static final double DEFAULT_PER_100 = 12.0;
    /** Distance after the fill-up before its own average is trusted. */
    public static final double OWN_AVERAGE_FROM_KM = 20.0;

    private double levelAtFill = Double.NaN;
    private final FuelEconomy since = new FuelEconomy();
    private long fillMs = 0;

    /** A full tank. */
    public void fill(double capacity, long nowMs) {
        levelAtFill = capacity;
        since.reset();
        fillMs = nowMs;
    }

    /**
     * A partial fill-up: what is left plus what went in, never more than the
     * tank holds. With nothing known yet, the litres added are all there is.
     */
    public void add(double litres, double capacity, long nowMs) {
        if (!(litres > 0)) return;
        double left = isKnown() ? remaining() : 0;
        levelAtFill = Math.min(capacity, left + litres);
        since.reset();
        fillMs = nowMs;
    }

    public void update(double litresPerHour, double kmh, long nowMs) {
        since.update(litresPerHour, kmh, nowMs);
    }

    public boolean isKnown() { return !Double.isNaN(levelAtFill); }

    /** Litres left, never below zero; NaN until a fill-up has been entered. */
    public double remaining() {
        if (!isKnown()) return Double.NaN;
        return Math.max(0, levelAtFill - since.litres());
    }

    public double usedSinceFill() { return since.litres(); }

    public double kmSinceFill() { return since.km(); }

    public long fillMs() { return fillMs; }

    /**
     * The average the range is worked out with: this tank's own once it has
     * covered some distance, else the long-term one, else a plain default.
     */
    public double averageFor(double longTermPer100) {
        if (since.km() >= OWN_AVERAGE_FROM_KM) return since.averagePer100Km();
        if (longTermPer100 > 0) return longTermPer100;
        return DEFAULT_PER_100;
    }

    /** Kilometres left at that average; NaN when the level is unknown. */
    public double rangeKm(double longTermPer100) {
        double left = remaining();
        if (Double.isNaN(left)) return Double.NaN;
        return left / averageFor(longTermPer100) * 100.0;
    }

    public Map<String, Double> toMap() {
        Map<String, Double> m = new HashMap<>();
        m.put("level_at_fill", levelAtFill);
        m.put("used", since.litres());
        m.put("km", since.km());
        m.put("fill_ms", (double) fillMs);
        return m;
    }

    public static FuelTank fromMap(Map<String, Double> m) {
        FuelTank t = new FuelTank();
        Double level = m.get("level_at_fill");
        t.levelAtFill = level == null ? Double.NaN : level;
        t.since.restore(get(m, "used"), get(m, "km"));
        t.fillMs = (long) get(m, "fill_ms");
        return t;
    }

    private static double get(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v == null || Double.isNaN(v) ? 0 : v;
    }
}
