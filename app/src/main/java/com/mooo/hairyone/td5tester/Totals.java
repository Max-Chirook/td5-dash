package com.mooo.hairyone.td5tester;

import java.util.HashMap;
import java.util.Map;

/**
 * Peaks, means, fuel and distance gathered over any length of time: one set
 * for the current trip, one kept for good until the driver resets it. Plain
 * Java, so it can be tested and saved as simple numbers.
 */
public final class Totals {

    private float rpmMax = Float.NEGATIVE_INFINITY;
    private float boostMax = Float.NEGATIVE_INFINITY;
    private float coolantMax = Float.NEGATIVE_INFINITY;
    private float airflowMax = Float.NEGATIVE_INFINITY;
    private double batterySum = 0;
    private long batteryCount = 0;
    private final FuelEconomy fuel = new FuelEconomy();
    private long sinceMs;

    public Totals(long sinceMs) { this.sinceMs = sinceMs; }

    public void addRpm(float v) { if (ok(v) && v > rpmMax) rpmMax = v; }
    public void addBoost(float v) { if (ok(v) && v > boostMax) boostMax = v; }
    public void addCoolant(float v) { if (ok(v) && v > coolantMax) coolantMax = v; }
    public void addAirflow(float v) { if (ok(v) && v > airflowMax) airflowMax = v; }

    public void addBattery(float v) {
        if (!ok(v)) return;
        batterySum += v;
        batteryCount++;
    }

    public void addFuel(double litresPerHour, double kmh, long nowMs) {
        fuel.update(litresPerHour, kmh, nowMs);
    }

    public void reset(long nowMs) {
        rpmMax = boostMax = coolantMax = airflowMax = Float.NEGATIVE_INFINITY;
        batterySum = 0;
        batteryCount = 0;
        fuel.reset();
        sinceMs = nowMs;
    }

    private static boolean ok(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }

    private static float orNaN(float v) { return v == Float.NEGATIVE_INFINITY ? Float.NaN : v; }

    /** NaN until a reading has come in. */
    public float rpmMax() { return orNaN(rpmMax); }
    public float boostMax() { return orNaN(boostMax); }
    public float coolantMax() { return orNaN(coolantMax); }
    public float airflowMax() { return orNaN(airflowMax); }
    public float batteryMean() { return batteryCount == 0 ? Float.NaN : (float) (batterySum / batteryCount); }
    public double km() { return fuel.km(); }
    public double litres() { return fuel.litres(); }
    public double fuelAverage() { return fuel.averagePer100Km(); }
    public long sinceMs() { return sinceMs; }

    /** Everything as numbers, for saving. */
    public Map<String, Double> toMap() {
        Map<String, Double> m = new HashMap<>();
        m.put("rpm_max", (double) rpmMax);
        m.put("boost_max", (double) boostMax);
        m.put("coolant_max", (double) coolantMax);
        m.put("airflow_max", (double) airflowMax);
        m.put("battery_sum", batterySum);
        m.put("battery_count", (double) batteryCount);
        m.put("litres", fuel.litres());
        m.put("km", fuel.km());
        return m;
    }

    /** Rebuilds a saved set; anything missing starts empty. */
    public static Totals fromMap(Map<String, Double> m, long sinceMs) {
        Totals t = new Totals(sinceMs);
        t.rpmMax = (float) get(m, "rpm_max", Double.NEGATIVE_INFINITY);
        t.boostMax = (float) get(m, "boost_max", Double.NEGATIVE_INFINITY);
        t.coolantMax = (float) get(m, "coolant_max", Double.NEGATIVE_INFINITY);
        t.airflowMax = (float) get(m, "airflow_max", Double.NEGATIVE_INFINITY);
        t.batterySum = get(m, "battery_sum", 0);
        t.batteryCount = (long) get(m, "battery_count", 0);
        t.fuel.restore(get(m, "litres", 0), get(m, "km", 0));
        return t;
    }

    private static double get(Map<String, Double> m, String key, double fallback) {
        Double v = m.get(key);
        return v == null || Double.isNaN(v) ? fallback : v;
    }
}
