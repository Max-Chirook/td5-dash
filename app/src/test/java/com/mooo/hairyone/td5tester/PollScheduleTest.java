package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

/** The live loop must still read everything, only not everything every time. */
public class PollScheduleTest {

    private static final Requests.RequestPidEnum[] FAST = {
            Requests.RequestPidEnum.ENGINE_RPM, Requests.RequestPidEnum.MAP_MAF,
            Requests.RequestPidEnum.THROTTLE_POSITION, Requests.RequestPidEnum.GET_FUEL_DEMAND,
            Requests.RequestPidEnum.VEHICLE_SPEED};

    @Test public void fastReadingsOnEveryPass() {
        for (long pass = 0; pass < 100; pass++) {
            Set<Requests.RequestPidEnum> due = PollSchedule.forPass(pass, true);
            for (Requests.RequestPidEnum pid : FAST) assertTrue(due.contains(pid));
        }
    }

    @Test public void everySlowReadingComesWithinTheRotation() {
        Set<Requests.RequestPidEnum> seen = EnumSet.noneOf(Requests.RequestPidEnum.class);
        for (long pass = 0; pass < PollSchedule.rotationLength(); pass++) {
            seen.addAll(PollSchedule.forPass(pass, true));
        }
        assertTrue(seen.contains(Requests.RequestPidEnum.TEMPERATURES));
        assertTrue(seen.contains(Requests.RequestPidEnum.BATTERY_VOLTAGE));
        assertTrue(seen.contains(Requests.RequestPidEnum.POWER_BALANCE));
    }

    @Test public void onlyOneSlowReadingPerPass() {
        for (long pass = 1; pass < 60; pass++) {
            Set<Requests.RequestPidEnum> due = PollSchedule.forPass(pass, true);
            int slow = 0;
            if (due.contains(Requests.RequestPidEnum.TEMPERATURES)) slow++;
            if (due.contains(Requests.RequestPidEnum.BATTERY_VOLTAGE)) slow++;
            if (due.contains(Requests.RequestPidEnum.POWER_BALANCE)) slow++;
            assertTrue(slow == 1);
        }
    }

    @Test public void ambientRetriedUntilKnown() {
        for (long pass = 1; pass < 10; pass++) {
            assertTrue(PollSchedule.forPass(pass, false).contains(Requests.RequestPidEnum.AMBIENT_PRESSURE));
        }
    }

    @Test public void ambientThenOnlyNowAndThen() {
        assertTrue(PollSchedule.forPass(0, true).contains(Requests.RequestPidEnum.AMBIENT_PRESSURE));
        assertFalse(PollSchedule.forPass(1, true).contains(Requests.RequestPidEnum.AMBIENT_PRESSURE));
        assertTrue(PollSchedule.forPass(PollSchedule.AMBIENT_EVERY, true).contains(Requests.RequestPidEnum.AMBIENT_PRESSURE));
    }

    @Test public void aPassIsShorterThanBefore() {
        // Nine requests per pass before; at most seven now.
        for (long pass = 0; pass < 60; pass++) {
            assertTrue(PollSchedule.forPass(pass, false).size() <= 7);
        }
    }
}
