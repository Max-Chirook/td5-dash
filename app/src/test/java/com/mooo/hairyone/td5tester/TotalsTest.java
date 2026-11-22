package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TotalsTest {

    @Test public void emptyUntilReadingsArrive() {
        Totals t = new Totals(0);
        assertTrue(Float.isNaN(t.rpmMax()));
        assertTrue(Float.isNaN(t.boostMax()));
        assertTrue(Float.isNaN(t.batteryMean()));
        assertTrue(Double.isNaN(t.fuelAverage()));
        assertEquals(0.0, t.km(), 0);
    }

    @Test public void keepsPeaksAndMeans() {
        Totals t = new Totals(0);
        t.addRpm(2000); t.addRpm(4100); t.addRpm(900);
        t.addBoost(0.8f); t.addBoost(1.21f);
        t.addCoolant(88); t.addCoolant(94);
        t.addAirflow(500); t.addAirflow(671);
        t.addBattery(14.0f); t.addBattery(14.4f);
        assertEquals(4100, t.rpmMax(), 0);
        assertEquals(1.21f, t.boostMax(), 1e-6);
        assertEquals(94, t.coolantMax(), 0);
        assertEquals(671, t.airflowMax(), 0);
        assertEquals(14.2f, t.batteryMean(), 1e-5);
    }

    @Test public void ignoresMissingReadings() {
        Totals t = new Totals(0);
        t.addRpm(Float.NaN);
        t.addBattery(Float.NaN);
        t.addBattery(Float.POSITIVE_INFINITY);
        assertTrue(Float.isNaN(t.rpmMax()));
        assertTrue(Float.isNaN(t.batteryMean()));
    }

    @Test public void survivesSaveAndLoad() {
        Totals t = new Totals(1234);
        t.addRpm(4310);
        t.addBoost(1.21f);
        t.addBattery(14.1f);
        long ms = 0;
        t.addFuel(10, 100, ms);
        for (int i = 0; i < 3600; i++) { ms += 1000; t.addFuel(10, 100, ms); }

        Totals back = Totals.fromMap(t.toMap(), t.sinceMs());
        assertEquals(4310, back.rpmMax(), 0);
        assertEquals(1.21f, back.boostMax(), 1e-6);
        assertEquals(14.1f, back.batteryMean(), 1e-5);
        assertEquals(100.0, back.km(), 1e-6);
        assertEquals(10.0, back.litres(), 1e-6);
        assertEquals(1234, back.sinceMs());
        assertTrue(Float.isNaN(back.coolantMax()));
    }

    @Test public void keepsAddingAfterLoading() {
        Totals t = new Totals(0);
        long ms = 0;
        for (int i = 0; i <= 3600; i++) { t.addFuel(10, 100, ms); ms += 1000; }
        Totals back = Totals.fromMap(t.toMap(), 0);
        // The first reading after loading only sets the clock.
        ms = 10_000_000;
        for (int i = 0; i <= 3600; i++) { back.addFuel(20, 100, ms); ms += 1000; }
        assertEquals(200.0, back.km(), 1e-6);
        assertEquals(30.0, back.litres(), 1e-6);
        assertEquals(15.0, back.fuelAverage(), 1e-6);
    }

    @Test public void loadsAnOlderSaveWithMissingKeys() {
        Map<String, Double> m = new HashMap<>();
        m.put("rpm_max", 3000.0);
        Totals t = Totals.fromMap(m, 5);
        assertEquals(3000, t.rpmMax(), 0);
        assertTrue(Float.isNaN(t.batteryMean()));
        assertEquals(0.0, t.km(), 0);
    }

    @Test public void resetStartsAgainFromNow() {
        Totals t = new Totals(0);
        t.addRpm(4000);
        t.addBattery(14);
        t.reset(999);
        assertTrue(Float.isNaN(t.rpmMax()));
        assertTrue(Float.isNaN(t.batteryMean()));
        assertEquals(999, t.sinceMs());
    }

    @Test public void longSumsKeepTheirPrecision() {
        Totals t = new Totals(0);
        // Two million readings: over a thousand hours of driving.
        for (int i = 0; i < 2_000_000; i++) t.addBattery(14.1f);
        Totals back = Totals.fromMap(t.toMap(), 0);
        assertEquals(14.1f, back.batteryMean(), 1e-4);
    }
}
