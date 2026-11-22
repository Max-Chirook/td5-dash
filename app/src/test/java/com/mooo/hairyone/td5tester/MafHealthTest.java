package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MafHealthTest {

    private static void trip(MafHealth h, double maf, double map, int samples) {
        for (int i = 0; i < samples; i++) h.add(maf, map);
        h.finishTrip();
    }

    @Test public void onlyWarmAndUnderBoost() {
        assertTrue(MafHealth.conditionsMet(85, 80, 0.6));
        assertFalse(MafHealth.conditionsMet(70, 80, 0.6));
        assertFalse(MafHealth.conditionsMet(85, 80, 0.2));
        assertFalse(MafHealth.conditionsMet(Double.NaN, 80, 0.6));
        assertFalse(MafHealth.conditionsMet(85, 80, Double.NaN));
    }

    @Test public void measuringUntilATripHasEnoughSamples() {
        MafHealth h = new MafHealth();
        for (int i = 0; i < MafHealth.MIN_SAMPLES - 1; i++) h.add(97, 100);
        assertEquals(MafHealth.Status.MEASURING, h.status(8, 15));
        h.add(97, 100);
        assertEquals(-3.0, h.deviationPercent(), 1e-9);
        assertEquals(MafHealth.Status.GOOD, h.status(8, 15));
    }

    @Test public void shortTripsAreDropped() {
        MafHealth h = new MafHealth();
        trip(h, 80, 100, 10);
        assertTrue(h.trips().isEmpty());
        assertEquals(MafHealth.Status.MEASURING, h.status(8, 15));
    }

    @Test public void verdictFollowsTheThresholds() {
        MafHealth h = new MafHealth();
        trip(h, 90, 100, 50);
        assertEquals(MafHealth.Status.WATCH, h.status(8, 15));
        MafHealth r = new MafHealth();
        trip(r, 80, 100, 50);
        assertEquals(MafHealth.Status.REPLACE, r.status(8, 15));
        MafHealth up = new MafHealth();
        trip(up, 120, 100, 50);
        assertEquals(MafHealth.Status.REPLACE, up.status(8, 15));
    }

    @Test public void keepsTheLastTenTrips() {
        MafHealth h = new MafHealth();
        for (int i = 0; i < 12; i++) trip(h, 100 - i, 100, 50);
        assertEquals(MafHealth.HISTORY, h.trips().size());
        assertEquals(-2.0, h.trips().get(0), 1e-9);
        assertEquals(-11.0, h.trips().get(9), 1e-9);
        assertEquals(-6.5, h.deviationPercent(), 1e-9);
    }

    @Test public void ignoresBadReadings() {
        MafHealth h = new MafHealth();
        h.add(0, 100);
        h.add(100, 0);
        h.add(Double.NaN, 100);
        assertEquals(0, h.tripSamples());
    }

    @Test public void anOpenTripIsKeptWhenTheAppCloses() {
        MafHealth h = new MafHealth();
        trip(h, 97, 100, 50);
        for (int i = 0; i < 50; i++) h.add(95, 100);
        MafHealth back = MafHealth.fromMap(h.toMap());
        assertEquals(2, back.trips().size());
        assertEquals(-4.0, back.deviationPercent(), 1e-9);
        assertEquals(0, back.tripSamples());
    }

    @Test public void resetForgetsEverything() {
        MafHealth h = new MafHealth();
        trip(h, 80, 100, 50);
        h.reset();
        assertEquals(MafHealth.Status.MEASURING, h.status(8, 15));
    }
}
