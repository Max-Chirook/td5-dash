package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Statistics shown under the battery gauge and on the chart tab. */
public class StatsTest {

    @Test public void emptyStatsReadAsNoValue() {
        RunningStats s = new RunningStats();
        assertTrue(s.isEmpty());
        assertTrue(Float.isNaN(s.min()));
        assertTrue(Float.isNaN(s.max()));
        assertTrue(Float.isNaN(s.mean()));
    }

    @Test public void minMaxMean() {
        RunningStats s = new RunningStats();
        s.add(13.8f); s.add(14.2f); s.add(14.6f);
        assertEquals(13.8f, s.min(), 1e-4);
        assertEquals(14.6f, s.max(), 1e-4);
        assertEquals(14.2f, s.mean(), 1e-4);
    }

    @Test public void missingReadingsAreSkipped() {
        RunningStats s = new RunningStats();
        s.add(Float.NaN); s.add(10f); s.add(Float.POSITIVE_INFINITY);
        assertEquals(1, s.count());
        assertEquals(10f, s.mean(), 1e-6);
    }

    @Test public void resetStartsOver() {
        RunningStats s = new RunningStats();
        s.add(5f);
        s.reset();
        assertTrue(s.isEmpty());
    }

    @Test public void historyKeepsTheNewestInOrder() {
        SignalHistory h = new SignalHistory(3);
        for (int i = 1; i <= 5; i++) h.add(i * 100, i);
        assertEquals(3, h.size());
        assertEquals(3f, h.valueAt(0), 0);
        assertEquals(5f, h.valueAt(2), 0);
        assertEquals(500, h.timeAt(2));
        assertEquals(5f, h.last(), 0);
    }

    @Test public void historyStatsCoverReadingsThatLeftTheRing() {
        SignalHistory h = new SignalHistory(2);
        h.add(1, 1); h.add(2, 2); h.add(3, 3);
        assertEquals(1f, h.stats().min(), 0);
        assertEquals(2f, h.stats().mean(), 1e-6);
    }

    @Test public void resetStatsKeepsTheCurve() {
        SignalHistory h = new SignalHistory(4);
        h.add(1, 7); h.add(2, 9);
        h.resetStats();
        assertEquals(2, h.size());
        assertTrue(h.stats().isEmpty());
    }

    @Test public void pedalPercentFollowsDemand() {
        assertEquals(0.0, Conversions.pedalPercentFromDemand(0, 50), 1e-9);
        assertEquals(50.0, Conversions.pedalPercentFromDemand(25, 50), 1e-9);
        assertEquals(100.0, Conversions.pedalPercentFromDemand(50, 50), 1e-9);
    }

    @Test public void pedalPercentIsClamped() {
        assertEquals(100.0, Conversions.pedalPercentFromDemand(62, 50), 1e-9);
        assertEquals(0.0, Conversions.pedalPercentFromDemand(-3, 50), 1e-9);
    }

    @Test public void pedalPercentWithoutReading() {
        assertTrue(Double.isNaN(Conversions.pedalPercentFromDemand(Double.NaN, 50)));
        assertTrue(Double.isNaN(Conversions.pedalPercentFromDemand(20, 0)));
    }
}
