package com.mooo.hairyone.td5tester.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.Conversions;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.RunningStats;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.SignalHistory;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.LineChartView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One signal at a time, charted live, with its minimum, maximum and mean.
 * Every signal is recorded in the background, so switching to another one
 * shows its recent curve and statistics straight away.
 */
public class GraphFragment extends BaseFragment {

    private static final String PREFS = "graph";
    private static final long[] WINDOWS_MS = {30_000, 120_000, 300_000};
    private static final String[] WINDOW_LABELS = {"30 s", "2 min", "5 min"};
    // Five minutes at the demo rate of four readings a second, with room to spare.
    private static final int CAPACITY = 1600;

    /** What the list offers. Derived signals have no event of their own. */
    private enum Sig {
        RPM("Moteur", "Régime moteur", "TR/MIN", 0),
        SPEED("Moteur", "Vitesse", "KM/H", 0),
        COOLANT("Moteur", "T° moteur", "°C", 1),
        BATTERY("Moteur", "Batterie", "VOLT", 2),

        BOOST("Air et turbo", "Turbo", "BAR", 2),
        AIR_FLOW("Air et turbo", "Débitmètre", "KG/H", 0),
        MANIFOLD("Air et turbo", "Pression collecteur", "KPA", 1),
        AMBIENT("Air et turbo", "Pression ambiante", "KPA", 1),
        INLET("Air et turbo", "T° admission", "°C", 1),
        OUTSIDE("Air et turbo", "T° extérieure", "°C", 1),

        PEDAL("Pédale", "Enfoncement", "%", 0),
        TRACK1("Pédale", "Voie 1", "V", 2),
        TRACK2("Pédale", "Voie 2", "V", 2),
        TRACK3("Pédale", "Voie 3", "V", 2),

        DEMAND("Injection", "Demande pédale", "MG", 1),
        INJECTED("Injection", "Qté injectée", "MG", 1),
        TORQUE("Injection", "Limite couple", "MG", 1),
        SMOKE("Injection", "Limite fumée", "MG", 1),
        MAF_MASS("Injection", "Masse d'air MAF", "MG", 0),
        MAP_MASS("Injection", "Masse d'air MAP", "MG", 0),
        AFR("Injection", "Rapport A/F", "AFR", 1),
        IDLE("Injection", "Écart de ralenti", "TR/MIN", 0),
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
    }

    // Process-wide, so the curves survive the activity being rebuilt when the
    // radio switches between full and split screen.
    private static final Map<Sig, SignalHistory> histories = new EnumMap<>(Sig.class);
    static {
        for (Sig s : Sig.values()) histories.put(s, new SignalHistory(CAPACITY));
    }
    private final Map<Sig, TextView> rowValues = new EnumMap<>(Sig.class);
    private final Map<Sig, View> rows = new EnumMap<>(Sig.class);
    private final List<TextView> windowButtons = new ArrayList<>();

    private Sig selected = Sig.RPM;
    private int windowIndex = 1;

    private float ambientKpa = Float.NaN;
    private float manifoldKpa = Float.NaN;

    private TextView tvTitle, tvValue, tvUnit;
    private TextView tvMin, tvMax, tvMean;
    private LineChartView chart;

    public GraphFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float v = (float) event.value;
        switch (event.data_type) {
            case RPM:                   record(Sig.RPM, v); break;
            case VEHICLE_SPEED:         record(Sig.SPEED, v); break;
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
                record(Sig.PEDAL, (float) Conversions.pedalPercentFromDemand(v, pedalFullMg()));
                break;
            case INJECTION_QUANTITY:    record(Sig.INJECTED, v); break;
            case TORQUE_LIMIT:          record(Sig.TORQUE, v); break;
            case SMOKE_LIMIT:           record(Sig.SMOKE, v); break;
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
        ambientKpa = Float.NaN;
        manifoldKpa = Float.NaN;
        for (Sig s : Sig.values()) updateRow(s);
        showSelected();
    }

    private float pedalFullMg() {
        Context context = getContext();
        return context == null ? 50f : Settings.get(context, "pedal_full_mg");
    }

    private void record(Sig sig, float value) {
        if (Float.isNaN(value)) return;
        histories.get(sig).add(SystemClock.elapsedRealtime(), value);
        updateRow(sig);
        if (sig == selected) showValues();
    }

    private void updateRow(Sig sig) {
        TextView tv = rowValues.get(sig);
        if (tv == null) return;
        float last = histories.get(sig).last();
        tv.setText(Float.isNaN(last) ? "—" : format(last, sig.decimals));
    }

    private void select(Sig sig) {
        selected = sig;
        Context context = getContext();
        if (context != null) prefs(context).edit().putString("signal", sig.name()).apply();
        showSelected();
    }

    private void showSelected() {
        if (chart == null) return;
        for (Map.Entry<Sig, View> e : rows.entrySet()) {
            e.getValue().setBackgroundResource(e.getKey() == selected ? R.drawable.segmented_selected : 0);
        }
        tvTitle.setText(selected.label.toUpperCase());
        tvUnit.setText(selected.unit);
        chart.setSource(histories.get(selected), selected.decimals);
        showValues();
    }

    private void showValues() {
        if (chart == null) return;
        SignalHistory h = histories.get(selected);
        float last = h.last();
        tvValue.setText(Float.isNaN(last) ? "—" : format(last, selected.decimals));
        RunningStats stats = h.stats();
        tvMin.setText(statText(stats.min()));
        tvMax.setText(statText(stats.max()));
        tvMean.setText(statText(stats.mean()));
        chart.invalidate();
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
        if (chart != null) chart.setWindowMs(WINDOWS_MS[index]);
        for (int i = 0; i < windowButtons.size(); i++) {
            TextView b = windowButtons.get(i);
            boolean on = i == index;
            b.setBackgroundResource(on ? R.drawable.segmented_selected : 0);
            b.setTextColor(ContextCompat.getColor(b.getContext(), on ? R.color.label : R.color.labelSecondary));
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override protected int getFragmentLayout() { return R.layout.graph_fragment; }

    @Override protected void bindViews(View view) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;

        tvTitle = view.findViewById(R.id.tvGraphTitle);
        tvValue = view.findViewById(R.id.tvGraphValue);
        tvUnit = view.findViewById(R.id.tvGraphUnit);
        chart = view.findViewById(R.id.chart);
        chart.setEmptyText(getString(R.string.graph_no_data));

        tvMin = bindStat(view.findViewById(R.id.statMin), R.string.graph_min);
        tvMax = bindStat(view.findViewById(R.id.statMax), R.string.graph_max);
        tvMean = bindStat(view.findViewById(R.id.statMean), R.string.graph_mean);

        view.findViewById(R.id.btGraphReset).setOnClickListener(v -> {
            histories.get(selected).resetStats();
            showValues();
        });

        // Signal list, grouped.
        LinearLayout list = view.findViewById(R.id.llSignals);
        rows.clear();
        rowValues.clear();
        String group = null;
        for (Sig sig : Sig.values()) {
            if (!sig.group.equals(group)) {
                group = sig.group;
                TextView header = new TextView(context);
                header.setText(group.toUpperCase());
                header.setTextColor(ContextCompat.getColor(context, R.color.labelTertiary));
                header.setTextSize(10.5f);
                header.setLetterSpacing(0.10f);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                header.setPadding((int) (16 * density), (int) (12 * density), (int) (12 * density), (int) (4 * density));
                list.addView(header);
            }
            list.addView(buildRow(context, density, sig));
        }

        // Time window selector.
        LinearLayout windows = view.findViewById(R.id.llWindows);
        windowButtons.clear();
        for (int i = 0; i < WINDOW_LABELS.length; i++) {
            TextView b = new TextView(context);
            b.setText(WINDOW_LABELS[i]);
            b.setTextSize(13f);
            b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding((int) (16 * density), (int) (9 * density), (int) (16 * density), (int) (9 * density));
            final int index = i;
            b.setOnClickListener(v -> setWindow(index));
            windows.addView(b);
            windowButtons.add(b);
        }

        SharedPreferences p = prefs(context);
        try {
            selected = Sig.valueOf(p.getString("signal", Sig.RPM.name()));
        } catch (IllegalArgumentException ex) {
            selected = Sig.RPM;
        }
        int savedWindow = p.getInt("window", 1);
        setWindow(savedWindow >= 0 && savedWindow < WINDOWS_MS.length ? savedWindow : 1);
        for (Sig s : Sig.values()) updateRow(s);
        showSelected();
    }

    private TextView bindStat(View stat, int labelRes) {
        ((TextView) stat.findViewById(R.id.tvStatLabel)).setText(labelRes);
        return stat.findViewById(R.id.tvStatValue);
    }

    private View buildRow(Context context, float density, Sig sig) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (12 * density), (int) (9 * density), (int) (12 * density), (int) (9 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) (6 * density), 0, (int) (6 * density), 0);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> select(sig));

        TextView name = new TextView(context);
        name.setText(sig.label);
        name.setTextColor(ContextCompat.getColor(context, R.color.label));
        name.setTextSize(14f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView value = new TextView(context);
        value.setTextColor(ContextCompat.getColor(context, R.color.labelSecondary));
        value.setTextSize(13f);
        value.setFontFeatureSettings("tnum");
        value.setText("—");
        row.addView(value);

        rows.put(sig, row);
        rowValues.put(sig, value);
        return row;
    }
}
