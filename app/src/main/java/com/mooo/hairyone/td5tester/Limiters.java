package com.mooo.hairyone.td5tester;

import java.util.HashMap;
import java.util.Map;

/**
 * Whether the ECU is holding the fuel back, and for how long.
 *
 * The driver asks for a quantity with the pedal; the ECU injects the lowest
 * of that demand, the smoke limit (from the air coming in) and the torque
 * limit. When the demand sits above a limit and the injected quantity rides
 * on that limit, the engine is held back by it.
 */
public final class Limiters {

    public enum State { FREE, SMOKE, TORQUE }

    /** How far the demand must exceed a limit before it counts as held back. */
    static final double MARGIN_MG = 1.5;
    /** How close the injected quantity must ride to the limit. */
    static final double RIDE_MG = 2.5;
    /** Gaps longer than this between two readings are not counted. */
    static final long MAX_STEP_MS = 3000;

    private long floorMs = 0, smokeMs = 0, torqueMs = 0;
    private long lastMs = -1;

    public static State stateOf(double demand, double injected, double smoke, double torque) {
        if (Double.isNaN(demand) || Double.isNaN(injected) || Double.isNaN(smoke) || Double.isNaN(torque)) return State.FREE;
        double limit = Math.min(smoke, torque);
        if (demand <= limit + MARGIN_MG) return State.FREE;
        if (Math.abs(injected - limit) > RIDE_MG) return State.FREE;
        return smoke <= torque ? State.SMOKE : State.TORQUE;
    }

    /** Adds the time since the previous reading to the matching counters. */
    public void add(State state, boolean floored, long nowMs) {
        if (lastMs >= 0) {
            long step = nowMs - lastMs;
            if (step > 0 && step <= MAX_STEP_MS) {
                if (floored) floorMs += step;
                if (state == State.SMOKE) smokeMs += step;
                if (state == State.TORQUE) torqueMs += step;
            }
        }
        lastMs = nowMs;
    }

    public long floorMs() { return floorMs; }
    public long smokeMs() { return smokeMs; }
    public long torqueMs() { return torqueMs; }

    public void reset() { floorMs = smokeMs = torqueMs = 0; lastMs = -1; }

    public Map<String, Double> toMap(String prefix) {
        Map<String, Double> m = new HashMap<>();
        m.put(prefix + "floor", (double) floorMs);
        m.put(prefix + "smoke", (double) smokeMs);
        m.put(prefix + "torque", (double) torqueMs);
        return m;
    }

    public void restore(Map<String, Double> m, String prefix) {
        floorMs = Math.round(get(m, prefix + "floor"));
        smokeMs = Math.round(get(m, prefix + "smoke"));
        torqueMs = Math.round(get(m, prefix + "torque"));
        lastMs = -1;
    }

    static double get(Map<String, Double> m, String key) {
        Double v = m.get(key);
        return v == null || v.isNaN() ? 0 : v;
    }

    /** "3 min 10 s", "41 s". */
    public static String duration(long ms) {
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + " s";
        long min = s / 60;
        if (min < 60) return min + " min " + (s % 60) + " s";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
