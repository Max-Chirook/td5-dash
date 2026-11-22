package com.mooo.hairyone.td5tester;

/**
 * Every raw-to-display conversion the ECU readings go through, kept pure and in
 * one place so the numbers on screen can be tested without a vehicle.
 */
public final class Conversions {

    private Conversions() { }

    /** Returned when a sensor is absent or dead, so the UI can show a dash. */
    public static final double NO_READING = Double.NaN;

    public static boolean isNoReading(double value) {
        return Double.isNaN(value);
    }

    // The ECU reports temperatures in tenths of a kelvin.
    public static double temperatureCelsius(short raw) {
        return (raw - 2732) / 10.0;
    }

    public static double volts(short raw) {
        return raw / 1000.0;
    }

    public static double pressureKpa(short raw) {
        return raw / 100.0;
    }

    public static double airFlowKgH(short raw) {
        return raw / 10.0;
    }

    /** Boost is the rise above the measured ambient pressure, never a fixed atmosphere. */
    public static double boostBar(double manifoldKpa, double ambientKpa) {
        if (Double.isNaN(manifoldKpa) || Double.isNaN(ambientKpa)) return NO_READING;
        double bar = (manifoldKpa - ambientKpa) / 100.0;
        return bar < 0 ? 0 : bar;
    }

    /**
     * The ECU reports pedal travel on this channel as a tenth of a percent,
     * although the original app labelled it volts.
     */
    public static double pedalPercent(double rawVoltsScale) {
        return rawVoltsScale * 10.0;
    }

    /**
     * Pedal travel taken from the driver demand of the fuelling frame, which
     * follows the pedal on the vehicle, where the supply channel stays still.
     * fullDemandMg is the demand read with the pedal on the floor.
     */
    public static double pedalPercentFromDemand(double demandMg, double fullDemandMg) {
        if (isNoReading(demandMg) || fullDemandMg <= 0) return NO_READING;
        double percent = demandMg / fullDemandMg * 100.0;
        return Math.max(0.0, Math.min(100.0, percent));
    }

    /**
     * Road speed corrected for non-standard tyres: readKmh on the speedometer
     * is realKmh by GPS, for instance 120 read for 130 real. Either value
     * missing or not positive leaves the speed as the ECU gives it.
     */
    public static double realSpeed(double ecuKmh, double readKmh, double realKmh) {
        if (isNoReading(ecuKmh) || !(readKmh > 0) || !(realKmh > 0)) return ecuKmh;
        return ecuKmh * realKmh / readKmh;
    }

    /** Diesel density at about 15 °C, in kg/L. */
    public static final double DIESEL_KG_PER_L = 0.835;
    /** Below this the vehicle counts as stopped and the economy is given per hour. */
    public static final double MOVING_KMH = 5.0;

    /**
     * Fuel flow from the injected quantity per stroke. Each of the five
     * cylinders injects once every two turns. correctionPercent scales the
     * result, so it can be matched against a fill-up.
     */
    public static double fuelLitresPerHour(double mgPerStroke, double rpm, double correctionPercent) {
        if (isNoReading(mgPerStroke) || isNoReading(rpm) || mgPerStroke < 0 || rpm <= 0) return 0.0;
        double mgPerHour = mgPerStroke * rpm * 2.5 * 60.0;
        return mgPerHour / 1e6 / DIESEL_KG_PER_L * correctionPercent / 100.0;
    }

    /** Litres per 100 km, or NO_READING when the vehicle is stopped. */
    public static double litresPer100Km(double litresPerHour, double kmh) {
        if (isNoReading(litresPerHour) || isNoReading(kmh) || kmh < MOVING_KMH) return NO_READING;
        return litresPerHour / kmh * 100.0;
    }

    /** 0 and 4.4 kg/h are what a disconnected or dead air meter reports. */
    public static double airFlowOrNoReading(double kgh) {
        if (kgh == 0.0 || Math.abs(kgh - 4.4) < 0.05) return NO_READING;
        return kgh;
    }

    /** The airbox sensor is absent on 10P engines, where it reads far out of range. */
    public static double externalTemperatureOrNoReading(double celsius) {
        if (celsius < -40.0 || celsius > 90.0) return NO_READING;
        return celsius;
    }

    public static double fuelQuantityMg(short raw) {
        return raw / 100.0;
    }

    public static double airMassMg(short raw) {
        return raw / 10.0;
    }

    public static double airFuelRatio(short raw) {
        return raw / 100.0;
    }

    /**
     * Identity fields come back as ASCII padded with control bytes; anything
     * unprintable is dropped, and a response with no text at all becomes hex.
     */
    public static String identityText(byte[] response, int from, int to) {
        if (response == null || from >= to || from < 0 || to > response.length) return "";
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            int c = response[i] & 0xFF;
            if (c >= 0x20 && c <= 0x7E) text.append((char) c);
        }
        String trimmed = text.toString().trim();
        if (!trimmed.isEmpty()) return trimmed;
        StringBuilder hex = new StringBuilder();
        for (int i = from; i < to; i++) hex.append(String.format("%02X", response[i]));
        return hex.toString();
    }

    /** Power balance is signed: a cylinder can pull below the others. */
    public static double powerBalance(short raw) {
        return raw;
    }
}
