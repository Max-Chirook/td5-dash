package com.mooo.hairyone.td5tester.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.FuelTank;
import com.mooo.hairyone.td5tester.MafHealth;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.TripComputer;
import com.mooo.hairyone.td5tester.Vehicle;
import com.mooo.hairyone.td5tester.views.MafChartView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The detail screens behind the range pill and the air meter's verdict. */
final class DashboardSheets {

    private DashboardSheets() { }

    // ---- Shared bits. ----

    static int color(Context c, int res) { return ContextCompat.getColor(c, res); }

    private static TextView text(Context c, String s, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color(c, colorRes));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        float d = c.getResources().getDisplayMetrics().density;
        l.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        return l;
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    private static void gap(LinearLayout l, int dp) {
        View v = new View(l.getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(l.getContext(), dp)));
        l.addView(v);
    }

    /** A thin bar filled to fraction, the same as the strip at the foot of the cluster. */
    private static View bar(Context c, double fraction, int fillColor) {
        LinearLayout track = new LinearLayout(c);
        track.setBackgroundResource(R.drawable.bar_track);
        track.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6)));
        View fill = new View(c);
        fill.setBackgroundResource(R.drawable.bar_fill);
        fill.getBackground().mutate().setTint(fillColor);
        float f = (float) Math.max(0, Math.min(1, fraction));
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f));
        View rest = new View(c);
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1 - f));
        track.addView(fill);
        track.addView(rest);
        return track;
    }

    private static View figure(Context c, String label, String value) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(c, label, 12, R.color.labelSecondary));
        l.addView(text(c, value, 17, R.color.label));
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return l;
    }

    private static String litres(double l) {
        return String.format(Locale.FRANCE, "%.1f L", l);
    }

    // ---- Range. ----

    /** The colour of the range figure: orange below 80 km, red below 40. */
    static int rangeColor(Context c, double km) {
        if (km < 40) return color(c, R.color.stateAlert);
        if (km < 80) return color(c, R.color.stateHigh);
        return color(c, R.color.label);
    }

    static void showRange(Context c, Runnable refresh) {
        if (Vehicle.model(c) < 0) {
            // The tank size comes with the model, so that is asked first.
            Vehicle.choose(c, () -> showRange(c, refresh));
            return;
        }
        TripComputer tc = TripComputer.get(c);
        FuelTank tank = tc.tank();
        double capacity = Vehicle.tankLitres(c);
        LinearLayout l = column(c);

        if (tank.isKnown()) {
            double km = tc.rangeKm();
            double left = tank.remaining();
            double avg = tank.averageFor(tc.total().fuelAverage());
            TextView big = text(c, String.format(Locale.FRANCE, "%.0f km restants", km), 30, R.color.label);
            big.setTextColor(rangeColor(c, km));
            l.addView(big);
            l.addView(text(c, String.format(Locale.FRANCE, "%.0f L sur %.0f L, à %.1f L/100 de moyenne",
                    left, capacity, avg), 13, R.color.labelSecondary));
            gap(l, 10);
            l.addView(bar(c, left / capacity, rangeColor(c, km)));
            gap(l, 14);
            LinearLayout row = new LinearLayout(c);
            row.addView(figure(c, "Depuis le plein", String.format(Locale.FRANCE, "%.0f km", tank.kmSinceFill())));
            row.addView(figure(c, "Consommé", litres(tank.usedSinceFill())));
            row.addView(figure(c, "Plein fait le",
                    new SimpleDateFormat("dd/MM", Locale.FRANCE).format(new Date(tank.fillMs()))));
            l.addView(row);
        } else {
            l.addView(text(c, c.getString(R.string.range_unknown), 14, R.color.label));
        }
        gap(l, 14);
        l.addView(text(c, Vehicle.name(c) + " · réservoir de " + Math.round(capacity)
                + " L utiles, modifiable dans Réglages.", 12, R.color.labelTertiary));

        new AlertDialog.Builder(c, R.style.InfoDialog)
                .setTitle(R.string.range_title)
                .setView(l)
                .setPositiveButton(R.string.range_fill, (d, w) -> {
                    tc.fillTank();
                    refresh.run();
                })
                .setNeutralButton(R.string.range_add, (d, w) -> askLitres(c, refresh))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private static void askLitres(Context c, Runnable refresh) {
        EditText field = new EditText(c);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setHint("40");
        field.setTextColor(color(c, R.color.label));
        field.setHintTextColor(color(c, R.color.labelTertiary));
        field.setGravity(Gravity.CENTER);
        LinearLayout l = column(c);
        l.addView(field);
        new AlertDialog.Builder(c, R.style.InfoDialog)
                .setTitle(R.string.range_add_title)
                .setView(l)
                .setPositiveButton(R.string.range_add_confirm, (d, w) -> {
                    try {
                        double litres = Double.parseDouble(field.getText().toString().replace(',', '.').trim());
                        if (litres > 0) TripComputer.get(c).addFuel(litres);
                    } catch (NumberFormatException ignored) { }
                    refresh.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Air meter health. ----

    static String mafVerdict(MafHealth.Status s) {
        switch (s) {
            case GOOD: return "bon état";
            case WATCH: return "à surveiller";
            case REPLACE: return "à remplacer";
            default: return "mesure en cours";
        }
    }

    static int mafColor(Context c, MafHealth.Status s) {
        switch (s) {
            case GOOD: return color(c, R.color.stateNormal);
            case WATCH: return color(c, R.color.stateHigh);
            case REPLACE: return color(c, R.color.stateAlert);
            default: return color(c, R.color.labelTertiary);
        }
    }

    static String signedPercent(double d) {
        long r = Math.round(d);
        return (r > 0 ? "+" : r < 0 ? "−" : "") + Math.abs(r) + " %";
    }

    static void showMaf(Context c) {
        MafHealth maf = TripComputer.get(c).maf();
        float warn = Settings.get(c, "maf_health_warn");
        float alert = Settings.get(c, "maf_health_alert");
        MafHealth.Status status = maf.status(warn, alert);
        double d = maf.deviationPercent();

        LinearLayout l = column(c);
        String verdict = mafVerdict(status);
        TextView big = text(c, Character.toUpperCase(verdict.charAt(0)) + verdict.substring(1)
                + (Double.isNaN(d) ? "" : "   " + signedPercent(d)), 26, R.color.label);
        big.setTextColor(mafColor(c, status));
        l.addView(big);
        if (Double.isNaN(d)) {
            l.addView(text(c, String.format(Locale.FRANCE,
                    "Pas encore assez de mesures : %d sur %d pour ce trajet.",
                    maf.tripSamples(), MafHealth.MIN_SAMPLES), 13, R.color.labelSecondary));
        } else {
            l.addView(text(c, String.format(Locale.FRANCE,
                    "Le débitmètre mesure %d %% d'air %s que le calcul par le capteur du collecteur.",
                    Math.abs(Math.round(d)), d < 0 ? "en moins" : "en plus"), 13, R.color.labelSecondary));
        }
        gap(l, 10);
        MafChartView chart = new MafChartView(c);
        chart.set(d, maf.trips(), warn, alert);
        l.addView(chart);
        l.addView(text(c, "Dernier point à droite : trajet le plus récent.", 11, R.color.labelTertiary));
        gap(l, 12);
        l.addView(text(c, c.getString(R.string.maf_note), 12, R.color.labelTertiary));
        gap(l, 12);
        l.addView(text(c, c.getString(R.string.i_airflow), 12, R.color.labelSecondary));

        ScrollView scroll = new ScrollView(c);
        scroll.addView(l);
        new AlertDialog.Builder(c, R.style.InfoDialog)
                .setTitle(R.string.maf_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.maf_reset, (dlg, w) -> new AlertDialog.Builder(c, R.style.InfoDialog)
                        .setTitle(R.string.maf_reset_title)
                        .setMessage(R.string.maf_reset_body)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.maf_reset, (d2, w2) -> {
                            TripComputer tc = TripComputer.get(c);
                            synchronized (tc) {
                                tc.maf().reset();
                                tc.save();
                            }
                        })
                        .show())
                .show();
    }
}
