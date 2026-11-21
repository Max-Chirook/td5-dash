package com.mooo.hairyone.td5tester;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which requests the live loop sends on a given pass.
 *
 * The K-line allows one request every 55 ms at best, so every request spent on
 * a value that barely moves delays the ones that do. What follows the driver
 * (engine speed, air, pedal, fuelling, speed) is read on every pass; the slow
 * readings take turns, one per pass; ambient pressure, which only changes with
 * altitude, is read until known and then now and then.
 */
public final class PollSchedule {

    private static final Set<Requests.RequestPidEnum> FAST = EnumSet.of(
            Requests.RequestPidEnum.ENGINE_RPM,
            Requests.RequestPidEnum.MAP_MAF,
            Requests.RequestPidEnum.THROTTLE_POSITION,
            Requests.RequestPidEnum.GET_FUEL_DEMAND,
            Requests.RequestPidEnum.VEHICLE_SPEED);

    private static final Requests.RequestPidEnum[] ROTATION = {
            Requests.RequestPidEnum.TEMPERATURES,
            Requests.RequestPidEnum.BATTERY_VOLTAGE,
            Requests.RequestPidEnum.POWER_BALANCE,
    };

    /** Passes between two ambient pressure reads once it is known. */
    public static final int AMBIENT_EVERY = 20;

    private PollSchedule() { }

    public static Set<Requests.RequestPidEnum> forPass(long pass, boolean ambientKnown) {
        Set<Requests.RequestPidEnum> due = EnumSet.copyOf(FAST);
        due.add(ROTATION[(int) (pass % ROTATION.length)]);
        if (!ambientKnown || pass % AMBIENT_EVERY == 0) {
            due.add(Requests.RequestPidEnum.AMBIENT_PRESSURE);
        }
        return due;
    }

    /** How many passes a slow reading waits between two reads. */
    public static int rotationLength() { return ROTATION.length; }
}
