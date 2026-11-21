package com.mooo.hairyone.td5tester;

/**
 * Fuel used and distance covered since the connection, for the trip average.
 * Each update adds the flow and speed over the time since the previous one;
 * a gap longer than MAX_STEP_MS (a lost reading, the screen asleep) is not
 * counted rather than guessed.
 */
public final class FuelEconomy {

    public static final long MAX_STEP_MS = 3000;
    /** No average is shown before this distance: the first metres say nothing. */
    public static final double MIN_DISTANCE_KM = 1.0;

    private double litres = 0;
    private double km = 0;
    private long lastMs = -1;

    public void update(double litresPerHour, double kmh, long nowMs) {
        if (lastMs >= 0) {
            long step = nowMs - lastMs;
            if (step > 0 && step <= MAX_STEP_MS) {
                double hours = step / 3_600_000.0;
                if (!Double.isNaN(litresPerHour) && litresPerHour > 0) litres += litresPerHour * hours;
                if (!Double.isNaN(kmh) && kmh > 0) km += kmh * hours;
            }
        }
        lastMs = nowMs;
    }

    public void reset() {
        litres = 0;
        km = 0;
        lastMs = -1;
    }

    public double litres() { return litres; }

    public double km() { return km; }

    /** Trip average in L/100 km, or NaN until MIN_DISTANCE_KM is covered. */
    public double averagePer100Km() {
        return km < MIN_DISTANCE_KM ? Double.NaN : litres / km * 100.0;
    }
}
