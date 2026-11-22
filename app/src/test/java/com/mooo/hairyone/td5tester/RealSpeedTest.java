package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RealSpeedTest {

    @Test public void biggerTyresReadLow() {
        // 120 on the speedometer is 130 by GPS.
        assertEquals(130.0, Conversions.realSpeed(120, 120, 130), 1e-9);
        assertEquals(65.0, Conversions.realSpeed(60, 120, 130), 1e-9);
    }

    @Test public void factorySettingChangesNothing() {
        assertEquals(87.0, Conversions.realSpeed(87, 100, 100), 1e-9);
    }

    @Test public void stoppedStaysStopped() {
        assertEquals(0.0, Conversions.realSpeed(0, 120, 130), 0);
    }

    @Test public void badSettingsAreIgnored() {
        assertEquals(87.0, Conversions.realSpeed(87, 0, 130), 0);
        assertEquals(87.0, Conversions.realSpeed(87, 120, 0), 0);
        assertEquals(87.0, Conversions.realSpeed(87, -5, 130), 0);
        assertEquals(87.0, Conversions.realSpeed(87, Double.NaN, 130), 0);
    }

    @Test public void noReadingStaysNoReading() {
        assertTrue(Double.isNaN(Conversions.realSpeed(Double.NaN, 120, 130)));
    }
}
