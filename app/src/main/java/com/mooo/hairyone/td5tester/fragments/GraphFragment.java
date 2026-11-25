package com.mooo.hairyone.td5tester.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.BoostMap;
import com.mooo.hairyone.td5tester.Conversions;
import com.mooo.hairyone.td5tester.FuelBreakdown;
import com.mooo.hairyone.td5tester.GearEstimator;
import com.mooo.hairyone.td5tester.HealthLog;
import com.mooo.hairyone.td5tester.Limiters;
import com.mooo.hairyone.td5tester.MafHealth;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.RunningStats;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.SignalHistory;
import com.mooo.hairyone.td5tester.Totals;
import com.mooo.hairyone.td5tester.TripComputer;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.BarChartView;
import com.mooo.hairyone.td5tester.views.LimiterChartView;
import com.mooo.hairyone.td5tester.views.LineChartView;
import com.mooo.hairyone.td5tester.views.TrendView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two modes. "En direct": one signal charted live with its minimum, maximum
 * and mean, and a second signal in a strip under it to explain the first.
 * "Bilans": what the trip or the long-term total says about the driving and
 * the engine, each with its conclusion in the list.
 */
public class GraphFragment extends BaseFragment {

    private static final String PREFS = "graph";
    private static final long[] WINDOWS_MS = {30_000, 120_000, 300_000};
    private static final String[] WINDOW_LABELS = {"30 S", "2 MIN", "5 MIN"};
    // Five minutes at the demo rate of four readings a second, with room to spare.
    private static final int CAPACITY = 1600;

    /** What the list offers. Derived signals have no event of their own. */
    private enum Sig {
        RPM("Moteur", "Régime", "tr/min", 0),
        SPEED("Moteur", "Vitesse", "km/h", 0),
        GEAR("Moteur", "Rapport engagé", "", 0),
        COOLANT("Moteur", "T° moteur", "°C", 1),
        BATTERY("Moteur", "Batterie", "V", 2),

        FUEL_INSTANT("Consommation", "Instantanée", "L/100 km", 1),
        FUEL_FLOW("Consommation", "Débit gasoil", "L/h", 1),
        FUEL_TRIP("Consommation", "Moyenne trajet", "L/100 km", 1),

        BOOST("Air et turbo", "Turbo", "bar", 2),
        AIR_FLOW("Air et turbo", "Débitmètre", "kg/h", 0),
        MANIFOLD("Air et turbo", "Pression collecteur", "kPa", 1),
        AMBIENT("Air et turbo", "Pression ambiante", "kPa", 1),
        INLET("Air et turbo", "T° admission", "°C", 1),
        OUTSIDE("Air et turbo", "T° extérieure", "°C", 1),

        PEDAL("Pédale", "Enfoncement", "%", 0),
        TRACK1("Pédale", "Voie 1", "V", 2),
        TRACK2("Pédale", "Voie 2", "V", 2),
        TRACK3("Pédale", "Voie 3", "V", 2),

        DEMAND("Injection", "Demande pédale", "mg", 1),
        INJECTED("Injection", "Qté injectée", "mg", 1),
        TORQUE("Injection", "Limite couple", "mg", 1),
        SMOKE("Injection", "Limite fumée", "mg", 1),
        MAF_MASS("Injection", "Masse d'air MAF", "mg", 0),
        MAP_MASS("Injection", "Masse d'air MAP", "mg", 0),
        AFR("Injection", "Rapport A/F", ": 1", 1),
        IDLE("Injection", "Écart de ralenti", "tr/min", 0),
        FUEL_TEMP("Injection", "T° gasoil", "°C", 1),

        INJ1("Injecteurs", "Injecteur 1", "", 2),
        INJ2("Injecteurs", "Injecteur 2", "", 2),
        INJ3("Injecteurs", "Injecteur 3", "", 2),
        INJ4("Injecteurs", "Injecteur 4", "", 2),
        INJ5("Injecteurs", "Injecteur 5", "", 2);

        final String group, label, unit;
        final int decimals;

        Sig(String group, String label, String unit, int decimals) {
            this.group = group;
            this.label = label;
            this.unit = unit;
            this.decimals = decimals;
        }

        /** The signal that best explains this one, for the strip under the chart. */
        Sig partner() {
            switch (this) {
                case RPM: case GEAR: case COOLANT: case FUEL_INSTANT: case FUEL_FLOW: case FUEL_TRIP:
                case OUTSIDE: case AMBIENT: case PEDAL: return SPEED;
                case SPEED: case BATTERY: case BOOST: case MANIFOLD: case IDLE:
                case INJ1: case INJ2: case INJ3: case INJ4: case INJ5: return RPM;
                case AIR_FLOW: case INLET: return BOOST;
                case TRACK1: case TRACK2: case TRACK3: return PEDAL;
                case DEMAND: case TORQUE: case SMOKE: case AFR: return INJECTED;
                case INJECTED: return DEMAND;
                case MAF_MASS: return MAP_MASS;
                case MAP_MASS: return MAF_MASS;
                case FUEL_TEMP: return COOLANT;
                default: return SPEED;
            }
        }
    }

    private enum Bilan {
        SPEED("Conso par vitesse"), GEAR("Conso par rapport"), TURBO("Turbo par régime"),
        LIMITERS("Limiteurs"), HEALTH("Santé par trajet");
        final String label;
        Bilan(String label) { this.label = label; }
    }

    // Process-wide, so the curves survive the activity being rebuilt when the
    // radio switches between full and split screen.
    private static final Map<Sig, SignalHistory> histories = new EnumMap<>(Sig.class);
    private static final SignalHistory limiterStates = new SignalHistory(CAPACITY);
    private static final Map<Sig, Sig> partners = new EnumMap<>(Sig.class);
    static {
        for (Sig s : Sig.values()) histories.put(s, new SignalHistory(CAPACITY));
    }
    private final Map<Sig, TextView> rowValues = new EnumMap<>(Sig.class);
    private final Map<Sig, View> rows = new EnumMap<>(Sig.class);
    private final Map<Bilan, View> bilanRows = new EnumMap<>(Bilan.class);
    private final Map<Bilan, TextView> bilanValues = new EnumMap<>(Bilan.class);
    private final List<TextView> windowButtons = new ArrayList<>();

    private Sig selected = Sig.RPM;
    private int windowIndex = 1;
    private boolean bilans = false;
    private Bilan bilan = Bilan.SPEED;
    private boolean bilanTotal = false;
    private int limiterWindow = 0;

    private float ambientKpa = Float.NaN;
    private float manifoldKpa = Float.NaN;
    private float rpm = Float.NaN;
    private float kmh = Float.NaN;

    private TextView tvTitle, tvValue, tvUnit, tvPartnerTitle;
    private TextView tvMin, tvMax, tvMean;
    private TextView tvModeLive, tvModeBilans;
    private LineChartView chart, chartPartner;
    private View liveView, bilanView, svSignals, svBilans;
    private TextView tvBilanTitle, tvBilanValue, tvBilanUnit, tvBilanPill, tvBilanNote;
    private LinearLayout llBilanSeg, llBilanStats, llHealth;
    private BarChartView barChart;
    private LimiterChartView limiterChart;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (bilanView == null) return;
            showBilanConclusions();
            if (bilans) showBilan();
            handler.postDelayed(this, 2000);
        }
    };

    public GraphFragment() { /* Required empty public constructor */ }

    // ------------------------------------------------------------- readings

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float v = (float) event.value;
        switch (event.data_type) {
            case RPM:
                rpm = v;
                record(Sig.RPM, v);
                break;
            case VEHICLE_SPEED: {
                kmh = v;
                record(Sig.SPEED, v);
                int gear = computer().gear();
                if (gear != 0) record(Sig.GEAR, gear);
                break;
            }
            case COOLANT_TEMP:          record(Sig.COOLANT, v); break;
            case BATTERY_VOLTAGE:       record(Sig.BATTERY, v); break;
            case AIR_FLOW:              record(Sig.AIR_FLOW, (float) Conversions.airFlowOrNoReading(v)); break;
            case AMBIENT_PRESSURE:
                ambientKpa = v;
                record(Sig.AMBIENT, v);
                break;
            case MANIFOLD_AIR_PRESSURE:
                manifoldKpa = v;
                record(Sig.MANIFOLD, v);
                record(Sig.BOOST, (float) Conversions.boostBar(manifoldKpa, ambientKpa));
                break;
            case INLET_TEMP:            record(Sig.INLET, v); break;
            case EXTERNAL_TEMP:         record(Sig.OUTSIDE, (float) Conversions.externalTemperatureOrNoReading(v)); break;
            case ACC_TRACK_1:           record(Sig.TRACK1, v); break;
            case ACC_TRACK_2:           record(Sig.TRACK2, v); break;
            case ACC_TRACK_3:           record(Sig.TRACK3, v); break;
            case DRIVER_DEMAND:
                record(Sig.DEMAND, v);
                record(Sig.PEDAL, (float) Conversions.pedalPercentFromDemand(v, TripComputer.get(requireContext()).pedalFull()));
                break;
            case INJECTION_QUANTITY:
                record(Sig.INJECTED, v);
                recordFuel(v);
                break;
            case TORQUE_LIMIT:          record(Sig.TORQUE, v); break;
            case SMOKE_LIMIT: {
                record(Sig.SMOKE, v);
                Limiters.State s = computer().limiter();
                limiterStates.add(SystemClock.elapsedRealtime(), s == Limiters.State.SMOKE ? 1 : s == Limiters.State.TORQUE ? 2 : 0);
                break;
            }
            case MAF_AIR_MASS:          record(Sig.MAF_MASS, v); break;
            case MAP_AIR_MASS:          record(Sig.MAP_MASS, v); break;
            case AF_RATIO:              record(Sig.AFR, v); break;
            case IDLE_DEMAND:           record(Sig.IDLE, v); break;
            case FUEL_TEMP:             record(Sig.FUEL_TEMP, v); break;
            case POWER_BALANCE_1:       record(Sig.INJ1, v); break;
            case POWER_BALANCE_2:       record(Sig.INJ2, v); break;
            case POWER_BALANCE_3:       record(Sig.INJ3, v); break;
            case POWER_BALANCE_4:       record(Sig.INJ4, v); break;
            case POWER_BALANCE_5:       record(Sig.INJ5, v); break;
            default: break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectedEvent(ConnectedEvent event) {
        // A new connection is a new trip: curves and statistics start over.
        for (SignalHistory h : histories.values()) h.clear();
        limiterStates.clear();
        ambientKpa = manifoldKpa = rpm = kmh = Float.NaN;
        for (Sig s : Sig.values()) updateRow(s);
        showSelected();
    }

    private TripComputer computer() { return TripComputer.get(requireContext()); }

    private float setting(String key) {
        Context context = getContext();
        return context == null ? 50f : Settings.get(context, key);
    }

    /**
     * Fuel economy, worked out like the main tab: the flow in L/h always, the
     * L/100 km only when moving, and the trip average from the trip computer
     * once the first kilometre is covered.
     */
    private void recordFuel(float mgPerStroke) {
        Context context = getContext();
        if (context == null || Float.isNaN(rpm)) return;
        double perHour = Conversions.fuelLitresPerHour(mgPerStroke, rpm, Settings.get(context, "fuel_correction"));
        record(Sig.FUEL_FLOW, (float) perHour);
        double per100 = Conversions.litresPer100Km(perHour, kmh);
        if (!Conversions.isNoReading(per100)) record(Sig.FUEL_INSTANT, (float) per100);
        double average = computer().trip().fuelAverage();
        if (!Double.isNaN(average)) record(Sig.FUEL_TRIP, (float) average);
    }

    private void record(Sig sig, float value) {
        if (Float.isNaN(value)) return;
        histories.get(sig).add(SystemClock.elapsedRealtime(), value);
        updateRow(sig);
        if (sig == selected) showValues();
    }

    // ------------------------------------------------------------- live

    private void updateRow(Sig sig) {
        TextView tv = rowValues.get(sig);
        if (tv == null) return;
        float last = histories.get(sig).last();
        tv.setText(Float.isNaN(last) ? "—" : sig == Sig.GEAR ? GearEstimator.label(Math.round(last)) : format(last, sig.decimals));
    }

    private void select(Sig sig) {
        selected = sig;
        Context context = getContext();
        if (context != null) prefs(context).edit().putString("signal", sig.name()).apply();
        showSelected();
    }

    private int color(int res) { return ContextCompat.getColor(requireContext(), res); }

    private void showSelected() {
        if (chart == null) return;
        for (Map.Entry<Sig, View> e : rows.entrySet()) {
            boolean on = e.getKey() == selected;
            e.getValue().setBackgroundResource(on ? R.drawable.list_selected : 0);
            TextView value = rowValues.get(e.getKey());
            if (value != null) value.setTextColor(color(on ? R.color.inkStrong : R.color.labelSecondary));
        }
        tvTitle.setText(selected.label.toUpperCase(Locale.FRENCH));
        tvUnit.setText(selected.unit);
        chart.setSource(histories.get(selected), selected.decimals);
        chart.setColorizer(selected == Sig.FUEL_INSTANT ? this::fuelColor : null);
        Sig partner = partners.containsKey(selected) ? partners.get(selected) : selected.partner();
        tvPartnerTitle.setText(partner.label.toUpperCase(Locale.FRENCH) + (partner.unit.isEmpty() ? "" : "  ·  " + partner.unit));
        chartPartner.setSource(histories.get(partner), partner.decimals);
        showValues();
    }

    /** Fuel economy in colour: green under the trip average, orange above, red when heavy, grey foot off. */
    private int fuelColor(float value) {
        if (value <= 0.05f) return color(R.color.stateIdle);
        double avg = computer().trip().fuelAverage();
        if (Double.isNaN(avg)) avg = 12;
        if (value <= avg * 1.15) return color(R.color.good);
        if (value <= Math.max(18, avg * 1.6)) return color(R.color.stateHigh);
        return color(R.color.stateAlert);
    }

    private void choosePartner() {
        Sig[] all = Sig.values();
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) names[i] = all[i].label;
        new AlertDialog.Builder(requireContext(), R.style.InfoDialog)
                .setTitle(R.string.graph_partner_title)
                .setItems(names, (d, which) -> {
                    partners.put(selected, all[which]);
                    showSelected();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showValues() {
        if (chart == null || bilans) return;
        SignalHistory h = histories.get(selected);
        float last = h.last();
        tvValue.setText(Float.isNaN(last) ? "—" : selected == Sig.GEAR ? GearEstimator.label(Math.round(last)) : format(last, selected.decimals));
        RunningStats stats = h.stats();
        tvMin.setText(statText(stats.min()));
        tvMax.setText(statText(stats.max()));
        tvMean.setText(statText(stats.mean()));
        chart.invalidate();
        chartPartner.invalidate();
    }

    private String statText(float v) {
        return Float.isNaN(v) ? "—" : format(v, selected.decimals);
    }

    private static String format(float v, int decimals) {
        return String.format("%." + decimals + "f", v);
    }

    private void setWindow(int index) {
        windowIndex = index;
        Context context = getContext();
        if (context != null) prefs(context).edit().putInt("window", index).apply();
        if (chart != null) {
            chart.setWindowMs(WINDOWS_MS[index]);
            chartPartner.setWindowMs(WINDOWS_MS[index]);
        }
        toggles(windowButtons, index);
    }

    private void toggles(List<TextView> buttons, int on) {
        for (int i = 0; i < buttons.size(); i++) {
            TextView b = buttons.get(i);
            b.setBackgroundResource(i == on ? R.drawable.underline_on : 0);
            b.setTextColor(color(i == on ? R.color.ink : R.color.labelSecondary));
        }
    }

    private TextView toggle(Context context, String text, float density, View.OnClickListener click) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setTextSize(11f);
        b.setLetterSpacing(0.16f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (2 * density), (int) (4 * density), (int) (2 * density), (int) (5 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (20 * density));
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    // ------------------------------------------------------------- modes

    private void setMode(boolean bilansMode) {
        bilans = bilansMode;
        Context context = getContext();
        if (context != null) prefs(context).edit().putBoolean("bilans", bilansMode).apply();
        if (liveView == null) return;
        liveView.setVisibility(bilansMode ? View.GONE : View.VISIBLE);
        svSignals.setVisibility(bilansMode ? View.GONE : View.VISIBLE);
        bilanView.setVisibility(bilansMode ? View.VISIBLE : View.GONE);
        svBilans.setVisibility(bilansMode ? View.VISIBLE : View.GONE);
        tvModeLive.setBackgroundResource(bilansMode ? 0 : R.drawable.underline_on);
        tvModeLive.setTextColor(color(bilansMode ? R.color.labelSecondary : R.color.ink));
        tvModeBilans.setBackgroundResource(bilansMode ? R.drawable.underline_on : 0);
        tvModeBilans.setTextColor(color(bilansMode ? R.color.ink : R.color.labelSecondary));
        if (bilansMode) showBilan(); else showSelected();
    }

    // ------------------------------------------------------------- bilans

    private void selectBilan(Bilan b) {
        bilan = b;
        Context context = getContext();
        if (context != null) prefs(context).edit().putString("bilan", b.name()).apply();
        showBilan();
    }

    private void setBilanTotal(boolean total) {
        bilanTotal = total;
        showBilan();
    }

    /** The conclusion of each bilan, shown in the list under its name. */
    private void showBilanConclusions() {
        if (bilanValues.isEmpty() || getContext() == null) return;
        for (Bilan b : Bilan.values()) {
            String[] c = conclusion(b, bilanTotal);
            TextView tv = bilanValues.get(b);
            tv.setText(c[0]);
            tv.setTextColor(Integer.parseInt(c[1]));
        }
    }

    /** Text and colour (as an int in a string) of a bilan's conclusion. */
    private String[] conclusion(Bilan b, boolean total) {
        TripComputer tc = computer();
        String none = "—";
        int grey = color(R.color.labelSecondary), good = color(R.color.good);
        int orange = color(R.color.stateHigh), red = color(R.color.stateAlert);
        switch (b) {
            case SPEED: {
                FuelBreakdown f = tc.fuelBySpeed(total);
                int best = f.best();
                return best < 0 ? new String[]{none, "" + grey} : new String[]{FuelBreakdown.SPEED_LABELS[best] + " km/h", "" + good};
            }
            case GEAR: {
                FuelBreakdown f = tc.fuelByGear(total);
                int best = f.best();
                return best < 0 ? new String[]{tc.gears().gearsSeen() == 0 ? "apprentissage" : none, "" + grey}
                        : new String[]{GearEstimator.label(best + 1), "" + good};
            }
            case TURBO: {
                double d = tc.boostDeviation(total);
                if (Double.isNaN(d)) return new String[]{"référence en cours", "" + grey};
                return new String[]{DashboardSheets.signedPercent(d), "" + (d >= -5 ? good : d >= -10 ? orange : red)};
            }
            case LIMITERS: {
                // The limiter screen speaks of the trip, so its conclusion does too.
                long ms = tc.limiters(false).smokeMs() + tc.limiters(false).torqueMs();
                return new String[]{Limiters.duration(ms) + " bridé", "" + (ms > 0 ? orange : good)};
            }
            default: {
                int issues = healthIssues();
                return issues == 0 ? new String[]{"rien à signaler", "" + good}
                        : new String[]{issues + " à surveiller", "" + orange};
            }
        }
    }

    private void showBilan() {
        if (bilanView == null || getContext() == null) return;
        for (Map.Entry<Bilan, View> e : bilanRows.entrySet()) {
            e.getValue().setBackgroundResource(e.getKey() == bilan ? R.drawable.list_selected : 0);
        }
        showBilanConclusions();
        tvBilanTitle.setText(bilan.label.toUpperCase(Locale.FRENCH));
        barChart.setVisibility(bilan == Bilan.LIMITERS || bilan == Bilan.HEALTH ? View.GONE : View.VISIBLE);
        limiterChart.setVisibility(bilan == Bilan.LIMITERS ? View.VISIBLE : View.GONE);
        llHealth.setVisibility(bilan == Bilan.HEALTH ? View.VISIBLE : View.GONE);
        tvBilanPill.setVisibility(bilan == Bilan.LIMITERS ? View.VISIBLE : View.GONE);
        barChart.setMaxes(null);
        barChart.setReference(null);
        barChart.setOutlined(-1);
        barChart.setAverage(Double.NaN);
        barChart.setTitle(null);
        switch (bilan) {
            case SPEED: showSpeed(); break;
            case GEAR: showGear(); break;
            case TURBO: showTurbo(); break;
            case LIMITERS: showLimiters(); break;
            default: showHealth(); break;
        }
    }

    private interface Pick { void accept(int index); }

    private void seg(String[] labels, int on, Pick pick) {
        Context c = requireContext();
        float d = getResources().getDisplayMetrics().density;
        llBilanSeg.removeAllViews();
        List<TextView> buttons = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = toggle(c, labels[i], d, v -> pick.accept(index));
            llBilanSeg.addView(b);
            buttons.add(b);
        }
        toggles(buttons, on);
    }

    private void tripTotalSeg() {
        seg(new String[]{"TRAJET", "TOTAL"}, bilanTotal ? 1 : 0, i -> setBilanTotal(i == 1));
    }

    private void header(String value, int valueColor, String unit) {
        tvBilanValue.setText(value);
        tvBilanValue.setTextColor(valueColor);
        tvBilanUnit.setText(unit);
    }

    private void stats(String[][] items) {
        Context c = requireContext();
        llBilanStats.removeAllViews();
        for (String[] item : items) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(c);
            label.setText(item[0]);
            label.setTextSize(10f);
            label.setLetterSpacing(0.2f);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setTextColor(color(R.color.labelSecondary));
            TextView value = new TextView(c);
            value.setText(item[1]);
            value.setTextSize(21f);
            value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            value.setTextColor(item.length > 2 ? Integer.parseInt(item[2]) : color(R.color.ink));
            value.setSingleLine(true);
            value.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(label);
            cell.addView(value);
            llBilanStats.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static String pct(double share) { return Math.round(share * 100) + " %"; }

    private void showSpeed() {
        tripTotalSeg();
        FuelBreakdown f = computer().fuelBySpeed(bilanTotal);
        Totals t = bilanTotal ? computer().total() : computer().trip();
        int n = f.slots(), best = f.best();
        double[] values = new double[n];
        String[] subs = new String[n];
        int[] colors = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = f.average(i);
            subs[i] = f.km(i) > 0 ? pct(f.share(i)) : "";
            colors[i] = i == best ? color(R.color.good) : withAlpha(color(R.color.dataBlue), 200);
        }
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = FuelBreakdown.SPEED_LABELS[i];
        barChart.setDecimals(1);
        barChart.setEmptyText(getString(R.string.bilan_empty));
        barChart.setData(labels, subs, values, colors);
        // The average of the same driving (moving, from 5 km/h), so the bars
        // compare with it fairly; idling would otherwise lift it.
        double moving = f.totalKm() >= FuelBreakdown.MIN_KM ? f.totalLitres() / f.totalKm() * 100 : Double.NaN;
        barChart.setAverage(moving);
        barChart.setTitle("L/100 KM SELON LA VITESSE (KM/H)");
        if (best < 0) header("—", color(R.color.ink), "pas encore assez de route");
        else header(FuelBreakdown.SPEED_LABELS[best], color(R.color.good), "km/h · LA PLUS SOBRE");
        stats(new String[][]{
                {"MOYENNE EN ROULANT", Double.isNaN(moving) ? "—" : String.format("%.1f L/100", moving)},
                {"DISTANCE", String.format("%.0f km", f.totalKm())},
                {"GASOIL", String.format("%.1f L", f.totalLitres())}});
        tvBilanNote.setText(R.string.bilan_speed_note);
    }

    private void showGear() {
        tripTotalSeg();
        TripComputer tc = computer();
        FuelBreakdown f = tc.fuelByGear(bilanTotal);
        int best = f.best();
        double[] values = new double[5];
        String[] labels = new String[5], subs = new String[5];
        int[] colors = new int[5];
        int current = tc.gear();
        for (int i = 0; i < 5; i++) {
            values[i] = f.average(i);
            labels[i] = GearEstimator.label(i + 1);
            subs[i] = (f.km(i) > 0 ? pct(f.share(i)) : "") + (current == i + 1 ? "  ▲ en cours" : "");
            colors[i] = i == best ? color(R.color.good) : withAlpha(color(R.color.dataBlue), 200);
        }
        barChart.setDecimals(1);
        barChart.setEmptyText(getString(tc.gears().gearsSeen() == 0 ? R.string.bilan_learning : R.string.bilan_empty));
        barChart.setData(labels, subs, values, colors);
        barChart.setOutlined(current > 0 ? current - 1 : -1);
        barChart.setAverage(f.totalKm() >= FuelBreakdown.MIN_KM ? f.totalLitres() / f.totalKm() * 100 : Double.NaN);
        barChart.setTitle("L/100 KM SELON LE RAPPORT ENGAGÉ");
        String now = "EN COURS : " + GearEstimator.label(current);
        if (best < 0) header("—", color(R.color.ink), now);
        else header(GearEstimator.label(best + 1), color(R.color.good), "LE PLUS SOBRE · " + now);
        int seen = tc.gears().gearsSeen();
        stats(new String[][]{
                {"EN COURS", GearEstimator.label(current)},
                {"GAMME", current < 0 ? "Courte" : current > 0 ? "Route" : "—"},
                {"RAPPORTS APPRIS", seen + " / 5", "" + color(seen == 5 ? R.color.good : R.color.ink)}});
        tvBilanNote.setText(R.string.bilan_gear_note);
    }

    private void showTurbo() {
        tripTotalSeg();
        TripComputer tc = computer();
        BoostMap m = tc.boost(bilanTotal);
        BoostMap ref = tc.boost(true);
        int n = m.bands();
        double[] values = new double[n], maxes = new double[n], reference = new double[n];
        String[] labels = new String[n], subs = new String[n];
        int[] colors = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = m.average(i);
            maxes[i] = m.max(i);
            reference[i] = ref.reference(i);
            labels[i] = String.valueOf(BoostMap.CENTRES[i]);
            int missing = tc.boostPullsMissing(bilanTotal, i);
            subs[i] = missing == 0 ? "tr/min"
                    : String.format(Locale.FRANCE, "%d/%d accél.", BoostMap.MIN_SAMPLES - missing, BoostMap.MIN_SAMPLES);
            colors[i] = withAlpha(color(R.color.dataBlue), 200);
        }
        barChart.setDecimals(2);
        barChart.setEmptyText(getString(R.string.bilan_turbo_empty));
        barChart.setData(labels, subs, values, colors);
        barChart.setMaxes(maxes);
        barChart.setReference(reference);
        barChart.setTitle("PRESSION TURBO EN ACCÉLÉRATION FRANCHE (BAR)");
        double d = tc.boostDeviation(bilanTotal);
        int peak = m.peakBand();
        if (Double.isNaN(d)) {
            header("—", color(R.color.ink), "RÉFÉRENCE EN COURS D’APPRENTISSAGE");
        } else {
            int c = d >= -5 ? color(R.color.good) : d >= -10 ? color(R.color.stateHigh) : color(R.color.stateAlert);
            header(DashboardSheets.signedPercent(d), c, "PAR RAPPORT À TA RÉFÉRENCE");
        }
        String state = Double.isNaN(d) ? "—" : d >= -5 ? "Conforme" : d >= -10 ? "À surveiller" : "En baisse";
        int stateColor = Double.isNaN(d) ? color(R.color.ink) : d >= -5 ? color(R.color.good) : d >= -10 ? color(R.color.stateHigh) : color(R.color.stateAlert);
        stats(new String[][]{
                {"PRESSION MAXI À", peak < 0 ? "—" : BoostMap.CENTRES[peak] + " tr/min"},
                {"ÉTAT", state, "" + stateColor},
                {"ACCÉLÉRATIONS", String.format(Locale.FRANCE, "%,d", m.total())}});
        tvBilanNote.setText(getString(R.string.bilan_turbo_note, Math.round(tc.pedalFull() * BoostMap.LOAD_SHARE), Math.round(tc.pedalFull())));
    }

    private void showLimiters() {
        seg(new String[]{"30 S", "2 MIN"}, limiterWindow, i -> { limiterWindow = i; showBilan(); });
        TripComputer tc = computer();
        limiterChart.setSources(histories.get(Sig.DEMAND), histories.get(Sig.INJECTED),
                histories.get(Sig.SMOKE), histories.get(Sig.TORQUE), limiterStates);
        limiterChart.setWindowMs(limiterWindow == 0 ? 30_000 : 120_000);
        Limiters l = tc.limiters(false);
        long held = l.smokeMs() + l.torqueMs();
        header(Limiters.duration(held), color(held > 0 ? R.color.stateHigh : R.color.good),
                held == 0 ? "JAMAIS BRIDÉ SUR CE TRAJET" : l.smokeMs() >= l.torqueMs() ? "BRIDÉ SUR CE TRAJET, SURTOUT PAR LA FUMÉE" : "BRIDÉ SUR CE TRAJET, SURTOUT PAR LE COUPLE");
        Limiters.State now = tc.limiter();
        Verdict.show(tvBilanPill, now == Limiters.State.FREE ? "●  en ce moment : libre"
                        : now == Limiters.State.SMOKE ? "●  en ce moment : bridé par la fumée" : "●  en ce moment : bridé par le couple",
                color(now == Limiters.State.FREE ? R.color.good : R.color.stateHigh));
        stats(new String[][]{
                {"PIED AU PLANCHER", Limiters.duration(l.floorMs())},
                {"BRIDÉ FUMÉE", Limiters.duration(l.smokeMs()), "" + color(R.color.stateHigh)},
                {"BRIDÉ COUPLE", Limiters.duration(l.torqueMs()), "" + color(R.color.stateAlert)}});
        tvBilanNote.setText(R.string.bilan_limiter_note);
    }

    /** How many of the four health figures need watching. */
    private int healthIssues() {
        int n = 0;
        for (int i = 0; i < 4; i++) if (healthLevel(i) > 0) n++;
        return n;
    }

    /** 0 fine or not measured, 1 to watch, 2 to act, for one of the four figures. */
    private int healthLevel(int which) {
        TripComputer tc = computer();
        switch (which) {
            case 0: {
                MafHealth.Status s = tc.maf().status(setting("maf_health_warn"), setting("maf_health_alert"));
                return s == MafHealth.Status.REPLACE ? 2 : s == MafHealth.Status.WATCH ? 1 : 0;
            }
            case 1: {
                List<Double> h = tc.health().history(HealthLog.Metric.INJECTORS);
                if (h.isEmpty()) return 0;
                double last = h.get(h.size() - 1);
                return last >= setting("inj_alert_high") ? 2 : last >= setting("inj_warn_high") ? 1 : 0;
            }
            case 2: {
                List<Double> h = tc.health().history(HealthLog.Metric.BOOST);
                if (h.size() < 3) return 0;
                double first = (h.get(0) + h.get(1)) / 2, last = h.get(h.size() - 1);
                return last < first * 0.85 ? 2 : last < first * 0.92 ? 1 : 0;
            }
            default: {
                List<Double> h = tc.health().history(HealthLog.Metric.BATTERY);
                if (h.isEmpty()) return 0;
                double last = h.get(h.size() - 1);
                double slope = HealthLog.slope(h);
                return last < 9.0 ? 2 : last < 9.6 || (!Double.isNaN(slope) && slope < -0.05) ? 1 : 0;
            }
        }
    }

    private void showHealth() {
        llBilanSeg.removeAllViews();
        TripComputer tc = computer();
        int issues = healthIssues();
        header(String.valueOf(issues), color(issues == 0 ? R.color.good : R.color.stateHigh),
                issues == 0 ? "RIEN À SIGNALER · DERNIERS TRAJETS" : (issues == 1 ? "POINT À SURVEILLER" : "POINTS À SURVEILLER") + " · DERNIERS TRAJETS");
        llHealth.removeAllViews();
        Context c = requireContext();
        float d = getResources().getDisplayMetrics().density;
        int good = color(R.color.good), orange = color(R.color.stateHigh), red = color(R.color.stateAlert), ink = color(R.color.ink);
        String[] names = {"Débitmètre", "Injecteurs", "Turbo à 2500 tr/min", "Batterie au démarrage"};
        String[] units = {"écart %", "écart max", "bar maxi", "volts mini"};
        List<List<Double>> data = new ArrayList<>();
        data.add(tc.maf().trips());
        data.add(tc.health().history(HealthLog.Metric.INJECTORS));
        data.add(tc.health().history(HealthLog.Metric.BOOST));
        data.add(tc.health().history(HealthLog.Metric.BATTERY));
        double[][] ranges = {{-25, 15}, {0, Math.max(6, setting("inj_alert_high") + 1)}, {0.6, 1.6}, {8, 13}};
        double[][] thr = {{-setting("maf_health_warn"), -setting("maf_health_alert")}, {setting("inj_warn_high")}, {}, {9.6}};
        int[][] thrColors = {{orange, red}, {orange}, {}, {orange}};
        String message = null;
        LinearLayout row = null;
        // Two columns on a wide screen, one in split screen.
        boolean narrow = getResources().getConfiguration().screenWidthDp < 700;
        int perRow = narrow ? 1 : 2;
        for (int i = 0; i < 4; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                llHealth.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            List<Double> values = data.get(i);
            int level = healthLevel(i);
            int lc = level == 2 ? red : level == 1 ? orange : values.isEmpty() ? color(R.color.labelSecondary) : good;
            String verdict = values.isEmpty() ? "pas encore mesuré" : level == 2 ? "à contrôler" : level == 1 ? "à surveiller" : "stable";
            if (i == 3 && level == 0 && !values.isEmpty()) {
                double slope = HealthLog.slope(values);
                if (!Double.isNaN(slope) && slope < -0.02) verdict = "baisse lente";
            }
            String last = values.isEmpty() ? "—" : i == 0 ? DashboardSheets.signedPercent(values.get(values.size() - 1))
                    : String.format(i == 3 ? "%.1f V" : i == 2 ? "%.2f" : "%.1f", values.get(values.size() - 1));
            if (level > 0 && message == null) message = names[i] + " : " + verdict + ".";

            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            boolean leftCell = narrow || i % 2 == 0;
            cell.setPadding(leftCell ? 0 : (int) (18 * d), (int) ((narrow ? 2 : 6) * d), leftCell && !narrow ? (int) (18 * d) : 0, (int) ((narrow ? 2 : 6) * d));
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(c);
            name.setText(names[i]);
            name.setTextSize(13.5f);
            name.setTextColor(color(R.color.labelRow));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = new TextView(c);
            v.setText(verdict);
            v.setTextSize(11.5f);
            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            v.setTextColor(lc);
            top.addView(v);
            cell.addView(top);
            LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout fig = new LinearLayout(c);
            fig.setOrientation(LinearLayout.VERTICAL);
            TextView big = new TextView(c);
            big.setText(last);
            big.setTextSize(24f);
            big.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            big.setTextColor(values.isEmpty() ? ink : lc == good ? good : lc);
            TextView unit = new TextView(c);
            unit.setText(units[i]);
            unit.setTextSize(10.5f);
            unit.setTextColor(color(R.color.dim));
            fig.addView(big);
            fig.addView(unit);
            body.addView(fig, new LinearLayout.LayoutParams((int) ((narrow ? 80 : 92) * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            if (narrow) {
                big.setTextSize(19f);
                unit.setVisibility(View.GONE);
            }
            TrendView trend = new TrendView(c);
            trend.setData(values, ranges[i][0], ranges[i][1], thr[i], thrColors[i], lc);
            body.addView(trend, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            cell.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        llBilanStats.removeAllViews();
        TextView msg = new TextView(c);
        msg.setText(message == null ? getString(R.string.health_all_good) : "●  " + message);
        msg.setTextSize(13f);
        msg.setTextColor(message == null ? good : orange);
        llBilanStats.addView(msg);
        tvBilanNote.setText(R.string.bilan_health_note);
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    // ------------------------------------------------------------- set-up

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override protected int getFragmentLayout() { return R.layout.graph_fragment; }

    @Override public void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 2000);
    }

    @Override public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        chart = null;
        bilanView = null;
        super.onDestroyView();
    }

    @Override protected void bindViews(View view) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;

        tvTitle = view.findViewById(R.id.tvGraphTitle);
        tvValue = view.findViewById(R.id.tvGraphValue);
        tvUnit = view.findViewById(R.id.tvGraphUnit);
        tvPartnerTitle = view.findViewById(R.id.tvPartnerTitle);
        chart = view.findViewById(R.id.chart);
        chartPartner = view.findViewById(R.id.chartPartner);
        chart.setEmptyText(getString(R.string.graph_no_data));
        chartPartner.setCompact(true);
        chartPartner.setLineColor(color(R.color.tick));
        liveView = view.findViewById(R.id.liveView);
        bilanView = view.findViewById(R.id.bilanView);
        svSignals = view.findViewById(R.id.svSignals);
        svBilans = view.findViewById(R.id.svBilans);
        tvModeLive = view.findViewById(R.id.tvModeLive);
        tvModeBilans = view.findViewById(R.id.tvModeBilans);
        tvBilanTitle = view.findViewById(R.id.tvBilanTitle);
        tvBilanValue = view.findViewById(R.id.tvBilanValue);
        tvBilanUnit = view.findViewById(R.id.tvBilanUnit);
        tvBilanPill = view.findViewById(R.id.tvBilanPill);
        tvBilanNote = view.findViewById(R.id.tvBilanNote);
        llBilanSeg = view.findViewById(R.id.llBilanSeg);
        llBilanStats = view.findViewById(R.id.llBilanStats);
        llHealth = view.findViewById(R.id.llHealth);
        barChart = view.findViewById(R.id.barChart);
        limiterChart = view.findViewById(R.id.limiterChart);

        // In split screen the list gives the charts more room.
        if (getResources().getConfiguration().screenWidthDp < 700) {
            View panel = (View) svSignals.getParent();
            panel.getLayoutParams().width = (int) (150 * density);
            panel.requestLayout();
        }

        tvMin = bindStat(view.findViewById(R.id.statMin), R.string.graph_min_caps);
        tvMax = bindStat(view.findViewById(R.id.statMax), R.string.graph_max_caps);
        tvMean = bindStat(view.findViewById(R.id.statMean), R.string.graph_mean_caps);

        view.findViewById(R.id.btGraphReset).setOnClickListener(v -> {
            histories.get(selected).resetStats();
            showValues();
        });
        view.findViewById(R.id.tvPartnerChange).setOnClickListener(v -> choosePartner());
        tvModeLive.setOnClickListener(v -> setMode(false));
        tvModeBilans.setOnClickListener(v -> setMode(true));

        // Signal list, grouped.
        LinearLayout list = view.findViewById(R.id.llSignals);
        rows.clear();
        rowValues.clear();
        String group = null;
        for (Sig sig : Sig.values()) {
            if (!sig.group.equals(group)) {
                group = sig.group;
                TextView header = new TextView(context);
                header.setText(group.toUpperCase(Locale.FRENCH));
                header.setTextColor(color(R.color.dim));
                header.setTextSize(10f);
                header.setLetterSpacing(0.2f);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                header.setPadding((int) (14 * density), (int) (12 * density), (int) (12 * density), (int) (4 * density));
                list.addView(header);
            }
            list.addView(buildRow(context, density, sig));
        }

        // Bilan list, each with its conclusion.
        LinearLayout blist = view.findViewById(R.id.llBilans);
        bilanRows.clear();
        bilanValues.clear();
        for (Bilan b : Bilan.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) (14 * density), (int) (10 * density), (int) (10 * density), (int) (10 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (4 * density), 0, (int) (4 * density), (int) (6 * density));
            row.setLayoutParams(lp);
            TextView name = new TextView(context);
            name.setText(b.label);
            name.setTextSize(14f);
            name.setTextColor(color(R.color.ink));
            TextView value = new TextView(context);
            value.setTextSize(16f);
            value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            value.setText("—");
            row.addView(name);
            row.addView(value);
            row.setClickable(true);
            row.setOnClickListener(v -> selectBilan(b));
            blist.addView(row);
            bilanRows.put(b, row);
            bilanValues.put(b, value);
        }

        // Time window selector.
        LinearLayout windows = view.findViewById(R.id.llWindows);
        windowButtons.clear();
        for (int i = 0; i < WINDOW_LABELS.length; i++) {
            final int index = i;
            TextView b = toggle(context, WINDOW_LABELS[i], density, v -> setWindow(index));
            windows.addView(b);
            windowButtons.add(b);
        }

        SharedPreferences p = prefs(context);
        try {
            selected = Sig.valueOf(p.getString("signal", Sig.RPM.name()));
        } catch (IllegalArgumentException ex) {
            selected = Sig.RPM;
        }
        try {
            bilan = Bilan.valueOf(p.getString("bilan", Bilan.SPEED.name()));
        } catch (IllegalArgumentException ex) {
            bilan = Bilan.SPEED;
        }
        int savedWindow = p.getInt("window", 1);
        setWindow(savedWindow >= 0 && savedWindow < WINDOWS_MS.length ? savedWindow : 1);
        for (Sig s : Sig.values()) updateRow(s);
        setMode(p.getBoolean("bilans", false));
    }

    private TextView bindStat(View stat, int labelRes) {
        ((TextView) stat.findViewById(R.id.tvStatLabel)).setText(labelRes);
        return stat.findViewById(R.id.tvStatValue);
    }

    private View buildRow(Context context, float density, Sig sig) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * density), (int) (8 * density), (int) (10 * density), (int) (8 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (4 * density), 0, (int) (4 * density), 0);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> select(sig));

        TextView name = new TextView(context);
        name.setText(sig.label);
        name.setTextColor(color(R.color.ink));
        name.setTextSize(14f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView value = new TextView(context);
        value.setTextColor(color(R.color.labelSecondary));
        value.setTextSize(13f);
        value.setFontFeatureSettings("tnum");
        value.setText("—");
        row.addView(value);

        rows.put(sig, row);
        rowValues.put(sig, value);
        return row;
    }
}
