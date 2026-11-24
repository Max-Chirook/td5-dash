package com.mooo.hairyone.td5tester;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One figure per trip for what wears slowly: the injectors' balance at warm
 * idle, the turbo's pressure at 2500 rpm under full load, and the battery's
 * lowest voltage as the trip starts. A trip that did not give a fair figure
 * adds nothing.
 */
public final class HealthLog {

    public static final int HISTORY = 15;

    public enum Metric { INJECTORS, BOOST, BATTERY }

    /** Readings a trip needs before its injector figure counts. */
    static final int MIN_IDLE_SAMPLES = 20;
    /** The battery figure only looks at the start of the trip. */
    public static final long BATTERY_WINDOW_MS = 60_000;

    private final double[] injSum = new double[5];
    private final int[] injCount = new int[5];
    private double boostMax = Double.NaN;
    private double batteryMin = Double.NaN;

    private final Map<Metric, List<Double>> history = new HashMap<>();

    public HealthLog() {
        for (Metric m : Metric.values()) history.put(m, new ArrayList<>());
    }

    /** An injector's balance, read at warm idle. */
    public void addInjector(int index, double balance) {
        if (index < 0 || index >= 5 || Double.isNaN(balance)) return;
        injSum[index] += balance;
        injCount[index]++;
    }

    public void addBoostAt2500(double bar) {
        if (!Double.isNaN(bar) && (Double.isNaN(boostMax) || bar > boostMax)) boostMax = bar;
    }

    public void addStartVoltage(double volts) {
        if (!Double.isNaN(volts) && volts > 5 && (Double.isNaN(batteryMin) || volts < batteryMin)) batteryMin = volts;
    }

    /** The largest injector balance this trip, from their means; NaN without enough idle. */
    public double tripInjectors() {
        double worst = Double.NaN;
        for (int i = 0; i < 5; i++) {
            if (injCount[i] < MIN_IDLE_SAMPLES) return Double.NaN;
            double m = Math.abs(injSum[i] / injCount[i]);
            if (Double.isNaN(worst) || m > worst) worst = m;
        }
        return worst;
    }

    public void finishTrip() {
        push(Metric.INJECTORS, tripInjectors());
        push(Metric.BOOST, boostMax);
        push(Metric.BATTERY, batteryMin);
        java.util.Arrays.fill(injSum, 0);
        java.util.Arrays.fill(injCount, 0);
        boostMax = Double.NaN;
        batteryMin = Double.NaN;
    }

    private void push(Metric m, double v) {
        if (Double.isNaN(v)) return;
        List<Double> h = history.get(m);
        h.add(v);
        while (h.size() > HISTORY) h.remove(0);
    }

    public List<Double> history(Metric m) { return new ArrayList<>(history.get(m)); }

    /** Change per trip from a straight-line fit, NaN under four trips. */
    public static double slope(List<Double> values) {
        int n = values.size();
        if (n < 4) return Double.NaN;
        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            double y = values.get(i);
            sx += i; sy += y; sxy += i * y; sxx += (double) i * i;
        }
        double d = n * sxx - sx * sx;
        return d == 0 ? Double.NaN : (n * sxy - sx * sy) / d;
    }

    public void reset() {
        for (List<Double> h : history.values()) h.clear();
    }

    public Map<String, Double> toMap() {
        Map<String, Double> m = new HashMap<>();
        for (Metric metric : Metric.values()) {
            List<Double> h = history.get(metric);
            for (int i = 0; i < h.size(); i++) m.put(metric.name() + "_" + i, h.get(i));
        }
        return m;
    }

    public static HealthLog fromMap(Map<String, Double> m) {
        HealthLog log = new HealthLog();
        for (Metric metric : Metric.values()) {
            for (int i = 0; i < HISTORY; i++) {
                Double v = m.get(metric.name() + "_" + i);
                if (v == null) break;
                log.history.get(metric).add(v);
            }
        }
        return log;
    }
}
