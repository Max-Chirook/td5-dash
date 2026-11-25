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

    // ---- Remapped engines: a learnt reference ----

    @Test public void learntReferenceJudgesOnlyTheDrift() {
        MafHealth h = new MafHealth();
        h.setLearnt(true);
        trip(h, 76, 100, 50);                                   // −24 %
        trip(h, 76, 100, 50);
        assertEquals(MafHealth.Status.MEASURING, h.status(8, 15));
        assertEquals(2, h.referenceTrips());
        trip(h, 76, 100, 50);
        assertEquals(-24.0, h.reference(), 1e-9);
        assertEquals(0.0, h.deviationPercent(), 1e-9);
        assertEquals(MafHealth.Status.GOOD, h.status(8, 15));
        assertEquals(-24.0, h.rawPercent(), 1e-9);
        trip(h, 60, 100, 50);                                   // −40 %: the mean drifts by −4
        assertEquals(-4.0, h.deviationPercent(), 1e-9);
        assertEquals(-16.0, h.trips().get(3), 1e-9);
        h.setLearnt(false);                                     // back to the factory view
        assertEquals(MafHealth.Status.REPLACE, h.status(8, 15));
    }

    @Test public void referenceSurvivesSavingAndResets() {
        MafHealth h = new MafHealth();
        h.setLearnt(true);
        for (int i = 0; i < 3; i++) trip(h, 80, 100, 50);
        MafHealth back = MafHealth.fromMap(h.toMap());
        assertTrue(back.isLearnt());
        assertEquals(-20.0, back.reference(), 1e-9);
        back.reset();
        assertTrue(back.isLearnt());
        assertTrue(Double.isNaN(back.reference()));
    }

    @Test public void olderSavesTakeTheReferenceFromTheirOldestTrips() {
        MafHealth h = new MafHealth();
        for (int i = 0; i < 4; i++) trip(h, 80 - i, 100, 50);  // −20, −21, −22, −23
        java.util.Map<String, Double> m = h.toMap();
        m.remove("ref_n");
        m.remove("ref_sum");
        MafHealth back = MafHealth.fromMap(m);
        assertEquals(-21.0, back.reference(), 1e-9);
        assertFalse(back.isLearnt());
    }

    @Test public void gapByTurboPressure() {
        assertEquals(-1, MafHealth.bandOf(0.3));
        assertEquals(0, MafHealth.bandOf(0.5));
        assertEquals(2, MafHealth.bandOf(1.3));
        MafHealth h = new MafHealth();
        for (int i = 0; i < 30; i++) h.add(90, 100, 0.5);
        assertTrue(Double.isNaN(h.bandSpread()));                // one band only
        for (int i = 0; i < 30; i++) h.add(70, 100, 1.2);
        assertEquals(-10.0, h.bandPercent(0), 1e-9);
        assertTrue(Double.isNaN(h.bandPercent(1)));
        assertEquals(-30.0, h.bandPercent(2), 1e-9);
        assertEquals(-20.0, h.bandSpread(), 1e-9);
        assertEquals(-30.0, MafHealth.fromMap(h.toMap()).bandPercent(2), 1e-9);
    }
}
