package com.mooo.hairyone.td5tester;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Every threshold the gauges colour by, with the factory value taken from the
 * notes published with the original app and an override the user can change.
 */
public final class Settings {

    private static final String PREFS = "thresholds";

    public static class Entry {
        public final String key;
        public final String section;
        public final String label;
        public final String unit;
        public final float factory;

        Entry(String key, String section, String label, String unit, float factory) {
            this.key = key;
            this.section = section;
            this.label = label;
            this.unit = unit;
            this.factory = factory;
        }
    }

    private static final String ENGINE = "Moteur";
    private static final String ELEC = "Électricité";
    private static final String AIR = "Air et turbo";
    private static final String INJ = "Injecteurs";
    private static final String PEDAL = "Pédale";
    private static final String COND = "Conditions de mesure";
    private static final String SPEED = "Vitesse";
    private static final String FUEL = "Consommation";

    public static final List<Entry> ENTRIES = new ArrayList<>();

    private static void add(String key, String section, String label, String unit, float factory) {
        ENTRIES.add(new Entry(key, section, label, unit, factory));
    }

    static {
        add("rpm_warn", ENGINE, "Régime — seuil orange", "tr/min", 4200);
        add("rpm_alert", ENGINE, "Régime — seuil rouge", "tr/min", 4800);
        add("coolant_cold", ENGINE, "T° moteur — encore froid sous", "°C", 70);
        add("coolant_warn", ENGINE, "T° moteur — seuil orange", "°C", 96);
        add("coolant_alert", ENGINE, "T° moteur — seuil rouge", "°C", 105);
        add("fuel_warn", ENGINE, "Gasoil — seuil orange", "°C", 80);
        add("fuel_alert", ENGINE, "Gasoil — seuil rouge", "°C", 95);

        add("volt_alert_low", ELEC, "Batterie — rouge sous", "V", 11.2f);
        add("volt_warn_low", ELEC, "Batterie — orange sous", "V", 13.7f);
        add("volt_warn_high", ELEC, "Batterie — orange au-dessus de", "V", 14.8f);
        add("volt_alert_high", ELEC, "Batterie — rouge au-dessus de", "V", 15.2f);

        add("turbo_warn", AIR, "Turbo — seuil orange", "bar", 1.25f);
        add("turbo_alert", AIR, "Turbo — seuil rouge", "bar", 1.42f);
        add("maf_warn", AIR, "Débitmètre — seuil orange", "kg/h", 700);
        add("maf_alert", AIR, "Débitmètre — seuil rouge", "kg/h", 760);
        add("inlet_warn", AIR, "Admission — seuil orange", "°C", 70);
        add("inlet_alert", AIR, "Admission — seuil rouge", "°C", 90);

        add("inj_alert_low", INJ, "Balance — rouge sous", "", -15);
        add("inj_warn_low", INJ, "Balance — orange sous", "", -4);
        add("inj_warn_high", INJ, "Balance — orange au-dessus de", "", 4);
        add("inj_alert_high", INJ, "Balance — rouge au-dessus de", "", 15);

        add("track_alert_low", PEDAL, "Voie — rouge sous", "V", 0.5f);
        add("track_warn_low", PEDAL, "Voie — orange sous", "V", 0.7f);
        add("track_warn_high", PEDAL, "Voie — orange au-dessus de", "V", 4.3f);
        add("track_alert_high", PEDAL, "Voie — rouge au-dessus de", "V", 4.6f);
        add("pedal_full_mg", PEDAL, "Pédale au plancher — demande Expert", "mg", 50);

        // Two readings taken together, the same as a GPS check: what the
        // speedometer shows, and the true speed at that moment.
        add("speed_read", SPEED, "Vitesse lue au compteur", "km/h", 100);
        add("speed_real", SPEED, "Vitesse réelle (GPS) au même moment", "km/h", 100);

        add("fuel_correction", FUEL, "Correction de la consommation", "%", 100);

        add("warm_c", COND, "Moteur considéré chaud à", "°C", 80);
        add("cold_c", COND, "Moteur considéré froid sous", "°C", 40);
        add("idle_ceiling", COND, "Ralenti jusqu'à", "tr/min", 900);
    }

    private Settings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static float get(Context context, String key) {
        for (Entry entry : ENTRIES) {
            if (entry.key.equals(key)) {
                return prefs(context).getFloat(key, entry.factory);
            }
        }
        throw new IllegalArgumentException("Unknown threshold: " + key);
    }

    public static void set(Context context, String key, float value) {
        prefs(context).edit().putFloat(key, value).apply();
    }

    public static boolean isCustom(Context context, String key) {
        for (Entry entry : ENTRIES) {
            if (entry.key.equals(key)) {
                return prefs(context).contains(key)
                        && prefs(context).getFloat(key, entry.factory) != entry.factory;
            }
        }
        return false;
    }

    public static void resetAll(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static List<String> sections() {
        List<String> sections = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (!sections.contains(entry.section)) sections.add(entry.section);
        }
        return sections;
    }
}
