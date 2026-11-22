package com.mooo.hairyone.td5tester;

import android.app.AlertDialog;
import android.content.Context;

/**
 * Which TD5 the app is fitted to. Choosing one fills in the usable tank
 * capacity, which can then be changed by hand (an extra tank, a measured
 * figure). The capacities are usable ones, a little under the brochure,
 * since a tank never empties to the last drop.
 */
public final class Vehicle {

    public static final String[] NAMES = {
            "Discovery 2 Td5", "Defender 90 Td5", "Defender 110 Td5", "Defender 130 Td5" };
    public static final float[] TANK_LITRES = { 90, 55, 72, 72 };

    private static final String PREFS = "vehicle";
    private static final String KEY = "model";

    private Vehicle() { }

    /** The model's index in NAMES, or -1 when none has been chosen yet. */
    public static int model(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, -1);
    }

    public static String name(Context context) {
        int m = model(context);
        return m < 0 ? null : NAMES[m];
    }

    /** Sets the model and its usable tank capacity. */
    public static void setModel(Context context, int model) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, model).apply();
        Settings.set(context, "tank_l", TANK_LITRES[model]);
    }

    public static double tankLitres(Context context) {
        return Settings.get(context, "tank_l");
    }

    /** Asks for the model; then runs after, on the main thread. */
    public static void choose(Context context, Runnable after) {
        String[] items = new String[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            items[i] = NAMES[i] + "  —  " + Math.round(TANK_LITRES[i]) + " L";
        }
        new AlertDialog.Builder(context, R.style.InfoDialog)
                .setTitle(R.string.vehicle_choose)
                .setSingleChoiceItems(items, model(context), (d, which) -> {
                    setModel(context, which);
                    d.dismiss();
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
