package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

public class BilansTest {

    // ---- Gears ----

    private static final double FIFTH = 23.5;   // rpm per km/h in fifth

    private static double rpmFor(int gear, double kmh, boolean low) {
        double r = FIFTH * GearEstimator.RATIOS[gear - 1] / GearEstimator.RATIOS[4];
        return kmh * r * (low ? GearEstimator.LOW_FACTOR : 1);
    }

    /** Drives steadily in one gear across a speed range. */
    private static void drive(GearEstimator g, int gear, double from, double to, int steps) {
        for (int i = 0; i < steps; i++) {
            double kmh = from + (to - from) * i / steps;
            g.update(rpmFor(gear, kmh, false), kmh);
        }
    }

    @Test public void learnsTheGearsFromMixedDriving() {
        GearEstimator g = new GearEstimator();
        drive(g, 2, 12, 28, 80);
        drive(g, 3, 25, 45, 80);
        drive(g, 4, 40, 70, 100);
        drive(g, 5, 60, 100, 200);
        assertEquals(FIFTH, g.fifth(), FIFTH * 0.02);
        // The first reading after a change is not steady yet: no gear.
        assertEquals(0, g.update(rpmFor(3, 40, false), 40));
        assertEquals(3, g.update(rpmFor(3, 40.5, false), 40.5));
        g.update(rpmFor(5, 90, false), 90);
        assertEquals(5, g.update(rpmFor(5, 91, false), 91));
        g.update(rpmFor(1, 12, false), 12);
        assertEquals(1, g.update(rpmFor(1, 12.2, false), 12.2));
    }

    @Test public void fifthAloneIsReadAsFifth() {
        GearEstimator g = new GearEstimator();
        drive(g, 5, 70, 110, 400);
        assertEquals(FIFTH, g.fifth(), FIFTH * 0.03);
    }

    @Test public void recognisesLowRange() {
        GearEstimator g = new GearEstimator();
        drive(g, 3, 25, 45, 100);
        drive(g, 4, 40, 70, 100);
        drive(g, 5, 60, 100, 200);
        g.update(rpmFor(2, 10, true), 10);
        assertEquals(-2, g.update(rpmFor(2, 10.1, true), 10.1));
    }

    @Test public void unknownUntilLearntOrWhenNotSteady() {
        GearEstimator g = new GearEstimator();
        assertEquals(0, g.update(2000, 80));
        assertEquals(0, g.update(2000, 80));          // steady but not learnt
        drive(g, 5, 60, 100, 300);
        assertEquals(0, g.update(800, 50));           // clutch down: ratio jumped
        assertEquals(0, g.update(1500, 3));           // too slow to tell
        assertEquals("4e", GearEstimator.label(4));
        assertEquals("2L", GearEstimator.label(-2));
        assertEquals("—", GearEstimator.label(0));
    }

    @Test public void gearsSurviveSaving() {
        GearEstimator g = new GearEstimator();
        drive(g, 4, 40, 70, 100);
        drive(g, 5, 60, 100, 200);
        GearEstimator back = GearEstimator.fromMap(g.toMap());
        assertEquals(g.fifth(), back.fifth(), 1e-9);
        assertEquals(g.samples(), back.samples());
    }

    // ---- Limiters ----

    @Test public void limiterStates() {
        assertEquals(Limiters.State.FREE, Limiters.stateOf(30, 30, 40, 48));
        assertEquals(Limiters.State.SMOKE, Limiters.stateOf(52, 40, 40, 48));
        assertEquals(Limiters.State.TORQUE, Limiters.stateOf(52, 46, 60, 46));
        // Demand over the limit but the injected quantity not riding it.
        assertEquals(Limiters.State.FREE, Limiters.stateOf(52, 30, 40, 48));
        assertEquals(Limiters.State.FREE, Limiters.stateOf(Double.NaN, 30, 40, 48));
    }

    @Test public void limiterTimes() {
        Limiters l = new Limiters();
        long t = 0;
        l.add(Limiters.State.FREE, true, t);
        for (int i = 0; i < 10; i++) l.add(Limiters.State.SMOKE, true, t += 1000);
        l.add(Limiters.State.TORQUE, false, t += 10_000);      // a gap: not counted
        l.add(Limiters.State.TORQUE, false, t += 1000);
        assertEquals(10_000, l.smokeMs());
        assertEquals(1_000, l.torqueMs());
        assertEquals(10_000, l.floorMs());
        Limiters back = new Limiters();
        back.restore(l.toMap("x_"), "x_");
        assertEquals(10_000, back.smokeMs());
        assertEquals("41 s", Limiters.duration(41_000));
        assertEquals("3 min 10 s", Limiters.duration(190_000));
    }

    // ---- Fuel breakdown ----

    @Test public void fuelBySpeedBand() {
        assertEquals(-1, FuelBreakdown.speedSlot(3));
        assertEquals(0, FuelBreakdown.speedSlot(20));
        assertEquals(2, FuelBreakdown.speedSlot(60));
        assertEquals(5, FuelBreakdown.speedSlot(130));
        FuelBreakdown f = new FuelBreakdown(6);
        long t = 0;
        f.add(2, 6, 60, t);
        for (int i = 0; i < 3600; i++) f.add(2, 6, 60, t += 1000);   // one hour at 60 km/h, 6 L/h
        f.add(4, 12, 100, t += 1000);
        for (int i = 0; i < 1800; i++) f.add(4, 12, 100, t += 1000); // half an hour at 100, 12 L/h
        assertEquals(10.0, f.average(2), 0.05);
        assertEquals(12.0, f.average(4), 0.05);
        assertEquals(2, f.best());
        assertEquals(60.0 / 110.0, f.share(2), 0.01);
        assertTrue(Double.isNaN(f.average(0)));
        FuelBreakdown back = new FuelBreakdown(6);
        back.restore(f.toMap("s_"), "s_");
        assertEquals(f.average(4), back.average(4), 1e-9);
    }

    // ---- Turbo map ----

    @Test public void boostReferenceAndDrift() {
        assertEquals(3, BoostMap.bandOf(2520));
        assertEquals(-1, BoostMap.bandOf(600));
        BoostMap m = new BoostMap();
        for (int i = 0; i < BoostMap.REFERENCE_SAMPLES; i++) m.add(2500, 1.0);
        assertEquals(1.0, m.reference(3), 1e-9);
        assertEquals(0.0, m.deviationPercent(), 1e-9);
        BoostMap trip = new BoostMap();
        for (int i = 0; i < 50; i++) trip.add(2500, 0.9);
        assertEquals(-10.0, trip.deviationFrom(m), 1e-6);
        m.reset();                                   // the reference stays
        assertEquals(1.0, m.reference(3), 1e-9);
        assertTrue(Double.isNaN(m.average(3)));
        BoostMap back = new BoostMap();
        back.restore(m.toMap("b_"), "b_");
        assertEquals(1.0, back.reference(3), 1e-9);
    }

    // ---- Health per trip ----

    @Test public void healthPerTrip() {
        HealthLog h = new HealthLog();
        for (int i = 0; i < 30; i++) for (int j = 0; j < 5; j++) h.addInjector(j, j == 2 ? -2.5 : 1.0);
        h.addBoostAt2500(1.30);
        h.addBoostAt2500(1.36);
        h.addStartVoltage(10.2);
        h.addStartVoltage(12.4);
        h.finishTrip();
        assertEquals(Arrays.asList(2.5), h.history(HealthLog.Metric.INJECTORS));
        assertEquals(Arrays.asList(1.36), h.history(HealthLog.Metric.BOOST));
        assertEquals(Arrays.asList(10.2), h.history(HealthLog.Metric.BATTERY));
        h.finishTrip();                              // nothing measured: nothing added
        assertEquals(1, h.history(HealthLog.Metric.BOOST).size());
        Map<String, Double> saved = h.toMap();
        assertEquals(h.history(HealthLog.Metric.BATTERY), HealthLog.fromMap(saved).history(HealthLog.Metric.BATTERY));
        assertEquals(-0.5, HealthLog.slope(Arrays.asList(10.0, 9.5, 9.0, 8.5)), 1e-9);
        assertTrue(Double.isNaN(HealthLog.slope(Arrays.asList(1.0, 2.0))));
    }
}
