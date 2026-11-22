package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FuelTankTest {

    /** One hour at that flow and speed, in one-second steps. */
    private static long drive(FuelTank t, double lph, double kmh, long ms, int seconds) {
        t.update(lph, kmh, ms);
        for (int i = 0; i < seconds; i++) { ms += 1000; t.update(lph, kmh, ms); }
        return ms + 60_000; // a pause, so the next drive starts fresh
    }

    @Test public void unknownUntilFilled() {
        FuelTank t = new FuelTank();
        assertFalse(t.isKnown());
        assertTrue(Double.isNaN(t.remaining()));
        assertTrue(Double.isNaN(t.rangeKm(12)));
    }

    @Test public void fullTankThenDriving() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        drive(t, 12, 100, 0, 3600); // 100 km, 12 L
        assertEquals(60.0, t.remaining(), 1e-6);
        assertEquals(100.0, t.kmSinceFill(), 1e-6);
        assertEquals(12.0, t.usedSinceFill(), 1e-6);
        // Its own average once past 20 km: 60 L at 12 L/100 is 500 km.
        assertEquals(500.0, t.rangeKm(15), 1e-6);
    }

    @Test public void longTermAverageUntilTheTankHasItsOwn() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        assertEquals(600.0, t.rangeKm(12), 1e-6);
        assertEquals(72.0 / FuelTank.DEFAULT_PER_100 * 100, t.rangeKm(Double.NaN), 1e-6);
    }

    @Test public void partialFillAddsToWhatIsLeft() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        drive(t, 12, 100, 0, 3600); // 60 L left
        t.add(10, 72, 5_000_000);
        assertEquals(70.0, t.remaining(), 1e-6);
        assertEquals(0.0, t.kmSinceFill(), 0);
    }

    @Test public void partialFillNeverOverflows() {
        FuelTank t = new FuelTank();
        t.fill(72, 0);
        t.add(40, 72, 1000);
        assertEquals(72.0, t.remaining(), 1e-6);
    }

    @Test public void partialFillOnUnknownTank() {
        FuelTank t = new FuelTank();
        t.add(30, 72, 0);
        assertTrue(t.isKnown());
        assertEquals(30.0, t.remaining(), 1e-6);
        t.add(-5, 72, 0);
        assertEquals(30.0, t.remaining(), 1e-6);
    }

    @Test public void neverBelowEmpty() {
        FuelTank t = new FuelTank();
        t.fill(10, 0);
        drive(t, 12, 100, 0, 3600);
        assertEquals(0.0, t.remaining(), 0);
        assertEquals(0.0, t.rangeKm(12), 0);
    }

    @Test public void survivesSaveAndLoad() {
        FuelTank t = new FuelTank();
        t.fill(72, 1234);
        drive(t, 12, 100, 0, 3600);
        FuelTank back = FuelTank.fromMap(t.toMap());
        assertEquals(60.0, back.remaining(), 1e-6);
        assertEquals(100.0, back.kmSinceFill(), 1e-6);
        assertEquals(1234, back.fillMs());
        assertFalse(FuelTank.fromMap(new FuelTank().toMap()).isKnown());
    }
}
