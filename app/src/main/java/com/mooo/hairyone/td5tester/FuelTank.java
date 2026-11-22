package com.mooo.hairyone.td5tester;

import java.util.HashMap;
import java.util.Map;

/**
 * What is left in the tank, worked out from the fuel used since the last
 * visit to the pump: the ECU knows nothing of the fuel gauge. Unknown until
 * the driver says the tank was filled.
 *
 * Between two full fill-ups it also keeps what was counted and what was
 * pumped, so each full fill-up can check the fuel figures: the litres the
 * pump put in are the litres actually burnt since the previous full tank.
 * Plain Java, saved as simple numbers.
 */
public final class FuelTank {

    /** Used for the range until a better average is known. */
    public static final double DEFAULT_PER_100 = 12.0;
    /** Distance after the fill-up before its own average is trusted. */
    public static final double OWN_AVERAGE_FROM_KM = 20.0;

    /** Below these the comparison says too little to adjust anything. */
    public static final double CHECK_MIN_LITRES = 10.0;
    public static final double CHECK_MIN_KM = 80.0;
    /** Outside this band the gap is not the estimate's error but something else. */
    public static final double CHECK_MIN_RATIO = 0.80;
    public static final double CHECK_MAX_RATIO = 1.25;

    private double levelAtFill = Double.NaN;
    private final FuelEconomy since = new FuelEconomy();
    private long fillMs = 0;

    // Since the last full tank: counted by the app, and added at the pump
    // by partial fill-ups in between.
    private boolean fullKnown = false;
    private final FuelEconomy sinceFull = new FuelEconomy();
    private double addedSinceFull = 0;
    // What the last full fill-up found, kept to warn about missed trips.
    private Check.Result lastResult = null;

    /** What a full fill-up says about the fuel figures. */
    public static final class Check {
        public enum Result { FIRST_FILL, TOO_SHORT, ADJUSTED, MISSED_TRIPS, NOT_FULL }

        public final Result result;
        public final double counted;
        public final double pumped;
        public final double km;

        Check(Result result, double counted, double pumped, double km) {
            this.result = result;
            this.counted = counted;
            this.pumped = pumped;
            this.km = km;
        }

        /** Pumped over counted: by how much the fuel correction should be multiplied. */
        public double ratio() { return counted > 0 ? pumped / counted : Double.NaN; }
    }

    /** A full tank, without saying how much went in. */
    public void fill(double capacity, long nowMs) {
        lastResult = null;
        levelAtFill = capacity;
        since.reset();
        fillMs = nowMs;
        fullKnown = true;
        sinceFull.reset();
        addedSinceFull = 0;
    }

    /**
     * A full tank, with the litres the pump put in. When the previous full
     * tank is known and enough was driven since, the comparison is returned
     * for the fuel correction to be adjusted.
     */
    public Check fill(double capacity, double pumped, long nowMs) {
        Check check = check(pumped);
        fill(capacity, nowMs);
        lastResult = check.result;
        return check;
    }

    private Check check(double pumped) {
        double counted = sinceFull.litres();
        double total = addedSinceFull + pumped;
        double km = sinceFull.km();
        if (!fullKnown || !(pumped > 0)) return new Check(Check.Result.FIRST_FILL, counted, total, km);
        if (counted < CHECK_MIN_LITRES || km < CHECK_MIN_KM) {
            return new Check(Check.Result.TOO_SHORT, counted, total, km);
        }
        double ratio = total / counted;
        // Far more pumped than counted: fuel was burnt with no tablet to see it.
        if (ratio > CHECK_MAX_RATIO) return new Check(Check.Result.MISSED_TRIPS, counted, total, km);
        // Far less: this fill-up, or the last, stopped short of full.
        if (ratio < CHECK_MIN_RATIO) return new Check(Check.Result.NOT_FULL, counted, total, km);
        return new Check(Check.Result.ADJUSTED, counted, total, km);
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
        addedSinceFull += litres;
    }

    public void update(double litresPerHour, double kmh, long nowMs) {
        since.update(litresPerHour, kmh, nowMs);
        sinceFull.update(litresPerHour, kmh, nowMs);
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

    /** What the last full fill-up found, or null when it gave no litres. */
    public Check.Result lastResult() { return lastResult; }

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
        m.put("full_known", fullKnown ? 1.0 : 0.0);
        m.put("full_used", sinceFull.litres());
        m.put("full_km", sinceFull.km());
        m.put("full_added", addedSinceFull);
        m.put("last_result", lastResult == null ? -1.0 : lastResult.ordinal());
        return m;
    }

    public static FuelTank fromMap(Map<String, Double> m) {
        FuelTank t = new FuelTank();
        Double level = m.get("level_at_fill");
        t.levelAtFill = level == null ? Double.NaN : level;
        t.since.restore(get(m, "used"), get(m, "km"));
        t.fillMs = (long) get(m, "fill_ms");
        t.fullKnown = get(m, "full_known") > 0;
        t.sinceFull.restore(get(m, "full_used"), get(m, "full_km"));
        t.addedSinceFull = get(m, "full_added");
        Double last = m.get("last_result");
        if (last != null && last >= 0 && last < Check.Result.values().length) {
            t.lastResult = Check.Result.values()[(int) (double) last];
        }
        return t;
    }

    private static double get(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v == null || Double.isNaN(v) ? 0 : v;
    }
}
