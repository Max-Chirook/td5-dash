package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FuelEconomyTest {

    @Test public void idleFlowIsAboutOneLitrePerHour() {
        // 8 mg per stroke at 740 rpm: 8 × 740 × 2.5 × 60 = 888 g/h of diesel.
        assertEquals(1.063, Conversions.fuelLitresPerHour(8, 740, 100), 0.001);
    }

    @Test public void correctionScalesTheFlow() {
        double base = Conversions.fuelLitresPerHour(20, 2000, 100);
        assertEquals(base * 1.08, Conversions.fuelLitresPerHour(20, 2000, 108), 1e-9);
    }

    @Test public void noFlowWithoutEngineSpeedOrInjection() {
        assertEquals(0.0, Conversions.fuelLitresPerHour(20, 0, 100), 0);
        assertEquals(0.0, Conversions.fuelLitresPerHour(0, 2000, 100), 0);
        assertEquals(0.0, Conversions.fuelLitresPerHour(Double.NaN, 2000, 100), 0);
        assertEquals(0.0, Conversions.fuelLitresPerHour(20, Double.NaN, 100), 0);
    }

    @Test public void per100KmOnlyWhileMoving() {
        assertEquals(10.0, Conversions.litresPer100Km(9, 90), 1e-9);
        assertTrue(Double.isNaN(Conversions.litresPer100Km(1, 0)));
        assertTrue(Double.isNaN(Conversions.litresPer100Km(1, 4)));
        assertTrue(Double.isNaN(Conversions.litresPer100Km(1, Double.NaN)));
    }

    @Test public void tripAverageIsFuelOverDistance() {
        FuelEconomy trip = new FuelEconomy();
        long t = 0;
        trip.update(10, 100, t);
        // One hour in one-second steps at 10 L/h and 100 km/h.
        for (int i = 0; i < 3600; i++) { t += 1000; trip.update(10, 100, t); }
        assertEquals(100.0, trip.km(), 1e-6);
        assertEquals(10.0, trip.litres(), 1e-6);
        assertEquals(10.0, trip.averagePer100Km(), 1e-6);
    }

    @Test public void idlingCountsFuelButNoDistance() {
        FuelEconomy trip = new FuelEconomy();
        long t = 0;
        for (int i = 0; i <= 600; i++) { trip.update(60, 60, t); t += 1000; } // 10 km, 10 L
        t = 600_000;
        for (int i = 0; i < 360; i++) { t += 1000; trip.update(1, 0, t); }    // 0.1 L stopped
        assertEquals(10.0, trip.km(), 1e-6);
        assertEquals(10.1, trip.litres(), 1e-6);
    }

    @Test public void noAverageBeforeTheFirstKilometre() {
        FuelEconomy trip = new FuelEconomy();
        trip.update(10, 50, 0);
        trip.update(10, 50, 1000);
        assertTrue(Double.isNaN(trip.averagePer100Km()));
    }

    @Test public void gapsAreSkippedNotGuessed() {
        FuelEconomy trip = new FuelEconomy();
        trip.update(10, 100, 0);
        trip.update(10, 100, 60_000); // a minute without readings
        assertEquals(0.0, trip.km(), 0);
        trip.update(10, 100, 61_000);
        assertEquals(100.0 / 3600, trip.km(), 1e-9);
    }

    @Test public void resetStartsOver() {
        FuelEconomy trip = new FuelEconomy();
        trip.update(10, 100, 0);
        trip.update(10, 100, 2000);
        trip.reset();
        assertEquals(0.0, trip.km(), 0);
        trip.update(10, 100, 5000);
        assertEquals(0.0, trip.km(), 0);
    }
}
