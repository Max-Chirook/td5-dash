package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The full fill-up check, and the steady-running filter of the air meter. */
public class FuelCheckTest {

    /** Drives at a fixed flow and speed for that many seconds. */
    private static long drive(FuelTank t, double lph, double kmh, long ms, int seconds) {
        t.update(lph, kmh, ms);
        for (int i = 0; i < seconds; i++) { ms += 1000; t.update(lph, kmh, ms); }
        return ms + 60_000;
    }

    @Test public void firstFillHasNothingToCompare() {
        FuelTank t = new FuelTank();
        FuelTank.Check c = t.fill(72, 50, 0);
        assertEquals(FuelTank.Check.Result.FIRST_FILL, c.result);
        assertEquals(72.0, t.remaining(), 1e-9);
    }

    @Test public void fullToFullAdjusts() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 100, 0, 4 * 3600); // 400 km, 48 L counted
        FuelTank.Check c = t.fill(72, 52.8, ms);
        assertEquals(FuelTank.Check.Result.ADJUSTED, c.result);
        assertEquals(48.0, c.counted, 1e-6);
        assertEquals(52.8, c.pumped, 1e-9);
        assertEquals(400.0, c.km, 1e-6);
        assertEquals(1.10, c.ratio(), 1e-6);
        assertEquals(FuelTank.Check.Result.ADJUSTED, t.lastResult());
    }

    @Test public void partialFillsInBetweenAreCounted() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 100, 0, 2 * 3600); // 24 L
        t.add(20, 72, ms);
        ms = drive(t, 12, 100, ms, 2 * 3600);     // 24 L more
        FuelTank.Check c = t.fill(72, 28, ms);
        assertEquals(48.0, c.counted, 1e-6);
        assertEquals(48.0, c.pumped, 1e-9);
        assertEquals(1.0, c.ratio(), 1e-6);
    }

    @Test public void tooShortSaysSo() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 50, 0, 3600); // 50 km
        assertEquals(FuelTank.Check.Result.TOO_SHORT, t.fill(72, 6, ms).result);
    }

    @Test public void farMorePumpedMeansMissedTrips() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 100, 0, 3 * 3600); // 36 L counted
        FuelTank.Check c = t.fill(72, 60, ms);
        assertEquals(FuelTank.Check.Result.MISSED_TRIPS, c.result);
        assertEquals(FuelTank.Check.Result.MISSED_TRIPS, t.lastResult());
    }

    @Test public void farLessPumpedMeansNotFull() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 100, 0, 3 * 3600);
        assertEquals(FuelTank.Check.Result.NOT_FULL, t.fill(72, 20, ms).result);
    }

    @Test public void noComparisonAfterAPartialStart() {
        FuelTank t = new FuelTank();
        t.add(30, 72, 0); // never a known full tank
        long ms = drive(t, 12, 100, 0, 3 * 3600);
        assertEquals(FuelTank.Check.Result.FIRST_FILL, t.fill(72, 40, ms).result);
    }

    @Test public void plainFillForgetsTheLastCheck() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 100, 0, 3 * 3600);
        t.fill(72, 60, ms);
        t.fill(72, ms);
        assertNull(t.lastResult());
    }

    @Test public void checkStateSurvivesSaveAndLoad() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        long ms = drive(t, 12, 100, 0, 2 * 3600);
        t.add(10, 72, ms);
        FuelTank back = FuelTank.fromMap(t.toMap());
        ms = drive(back, 12, 100, ms + 1_000_000, 2 * 3600);
        FuelTank.Check c = back.fill(72, 40, ms);
        assertEquals(48.0, c.counted, 1e-6);
        assertEquals(50.0, c.pumped, 1e-9);
        assertEquals(FuelTank.Check.Result.ADJUSTED, back.lastResult());
        FuelTank again = FuelTank.fromMap(back.toMap());
        assertEquals(FuelTank.Check.Result.ADJUSTED, again.lastResult());
    }

    @Test public void steadyRunningOnly() {
        assertTrue(MafHealth.isSteady(2400, 2480, 0.80, 0.85));
        assertFalse(MafHealth.isSteady(2400, 2700, 0.80, 0.85));
        assertFalse(MafHealth.isSteady(2400, 2450, 0.60, 0.90));
        assertFalse(MafHealth.isSteady(Double.NaN, 2450, 0.80, 0.85));
    }
}
