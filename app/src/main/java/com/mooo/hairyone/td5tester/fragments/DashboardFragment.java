package com.mooo.hairyone.td5tester.fragments;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.mooo.hairyone.td5tester.Conversions;
import com.mooo.hairyone.td5tester.FuelEconomy;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.RunningStats;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.GaugeInfoSheet;
import com.mooo.hairyone.td5tester.views.GaugeView;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class DashboardFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    GaugeView gRPM, gVOLT, gTURBO, gCOOLANT, gAIRFLOW, gFUEL;
    TextView tvWarmup;

    // Below this the engine is still coming up to temperature; the strip is
    // hidden once there, so a clean screen means nothing needs saying.

    private static final long WARM_CONFIRMATION_MS = 5000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean warmAnnounced = false;

    // Boost is the rise above ambient, so the last ambient reading is kept.
    private float ambientPressureKpa = Float.NaN;
    private float manifoldPressureKpa = Float.NaN;

    // Mean battery voltage since the connection, shown under the reading.
    // Process-wide, so a split-screen rebuild does not start it over.
    private static final RunningStats battery = new RunningStats();

    // Fuel economy comes from the injected quantity, the engine speed and the
    // road speed, all three read on every pass.
    private float rpm = Float.NaN;
    private float kmh = Float.NaN;
    private static final FuelEconomy trip = new FuelEconomy();

    public DashboardFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float value = (float) event.value;
        switch (event.data_type) {
            case RPM:
                gRPM.setValue(value);
                rpm = value;
                break;
            case INJECTION_QUANTITY:
                updateFuel(value);
                break;
            case BATTERY_VOLTAGE:
                gVOLT.setValue(value);
                battery.add(value);
                if (!battery.isEmpty()) {
                    gVOLT.setFootnote(String.format("moy. %.1f", battery.mean()));
                }
                break;
            case COOLANT_TEMP:
                gCOOLANT.setValue(value);
                updateWarmup(value);
                break;
            case VEHICLE_SPEED:
                gRPM.setSecondary(String.format("%.0f km/h", value));
                kmh = value;
                break;
            case AIR_FLOW: {
                double flow = Conversions.airFlowOrNoReading(value);
                if (Conversions.isNoReading(flow)) gAIRFLOW.clearValue();
                else gAIRFLOW.setValue((float) flow);
                break;
            }
            case AMBIENT_PRESSURE:
                ambientPressureKpa = value;
                updateBoost();
                break;
            case MANIFOLD_AIR_PRESSURE:
                manifoldPressureKpa = value;
                updateBoost();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectedEvent(ConnectedEvent event) {
        // A new connection is a new trip, so the session peaks start over.
        gRPM.resetPeak();
        gTURBO.resetPeak();
        gCOOLANT.resetPeak();
        battery.reset();
        gVOLT.setFootnote(null);
        trip.reset();
        showTripAverage();
        warmAnnounced = false;
    }

    /**
     * L/100 km while moving; stopped, a distance figure would be meaningless,
     * so the gauge shows L/h instead and says so in its unit.
     */
    private void updateFuel(float mgPerStroke) {
        double perHour = Conversions.fuelLitresPerHour(mgPerStroke, rpm, s("fuel_correction"));
        trip.update(perHour, kmh, android.os.SystemClock.elapsedRealtime());
        double per100 = Conversions.litresPer100Km(perHour, kmh);
        if (Conversions.isNoReading(per100)) {
            gFUEL.setUnit(getString(R.string.unit_l_per_hour));
            gFUEL.setValue((float) perHour);
        } else {
            gFUEL.setUnit(getString(R.string.unit_l_per_100));
            gFUEL.setValue((float) per100);
        }
        showTripAverage();
    }

    private void showTripAverage() {
        double avg = trip.averagePer100Km();
        // A dash until the first kilometre, so the caption sits at the same
        // height as its neighbours from the start.
        gFUEL.setFootnote(Double.isNaN(avg) ? "moy. —" : String.format("moy. %.1f L/100", avg));
    }

    private void updateWarmup(float celsius) {
        if (tvWarmup == null) return;
        float warmAt = s("warm_c");
        if (celsius < warmAt) {
            boolean freezing = celsius < s("cold_c");
            tvWarmup.setText(getString(freezing ? R.string.warm_cold : R.string.warm_rising,
                    Math.round(celsius)));
            tvWarmup.setTextColor(getResources().getColor(R.color.stateInfo));
            tvWarmup.setVisibility(View.VISIBLE);
            warmAnnounced = false;
            return;
        }
        if (warmAnnounced) return;
        warmAnnounced = true;
        tvWarmup.setText(R.string.warm_ready);
        tvWarmup.setTextColor(getResources().getColor(R.color.stateNormal));
        tvWarmup.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> {
            if (tvWarmup != null) tvWarmup.setVisibility(View.GONE);
        }, WARM_CONFIRMATION_MS);
    }

    @Override public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void applyBands() {
        float none = Float.NEGATIVE_INFINITY;
        gRPM.setBands(none, none, s("rpm_warn"), s("rpm_alert"));
        gVOLT.setBands(s("volt_alert_low"), s("volt_warn_low"), s("volt_warn_high"), s("volt_alert_high"));
        gTURBO.setBands(none, none, s("turbo_warn"), s("turbo_alert"));
        gCOOLANT.setBands(none, none, s("coolant_warn"), s("coolant_alert"));
        gCOOLANT.setColdBelow(s("coolant_cold"));
        gAIRFLOW.setBands(none, none, s("maf_warn"), s("maf_alert"));
        gFUEL.setBands(none, none, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
    }

    private float s(String key) {
        return Settings.get(requireContext(), key);
    }

    @Override public void onResume() {
        super.onResume();
        if (gRPM != null) applyBands();
    }

    private void updateBoost() {
        double bar = Conversions.boostBar(manifoldPressureKpa, ambientPressureKpa);
        if (!Conversions.isNoReading(bar)) gTURBO.setValue((float) bar);
    }

    @Override protected int getFragmentLayout() { return R.layout.dashboard_fragment; }

    @Override protected void bindViews(View view) {
        gRPM = view.findViewById(R.id.gRPM);
        gVOLT = view.findViewById(R.id.gVOLT);
        gTURBO = view.findViewById(R.id.gTURBO);
        gCOOLANT = view.findViewById(R.id.gCOOLANT);
        gAIRFLOW = view.findViewById(R.id.gAIRFLOW);
        gFUEL = view.findViewById(R.id.gFUEL);
        tvWarmup = view.findViewById(R.id.tvWarmup);

        showTripAverage();
        if (!battery.isEmpty()) gVOLT.setFootnote(String.format("moy. %.1f", battery.mean()));

        // Read in turn with the other slow values, not on every pass.
        gCOOLANT.setStaleAfter(GaugeView.STALE_SLOW_MS);
        gVOLT.setStaleAfter(GaugeView.STALE_SLOW_MS);

        gRPM.trackPeak();
        gTURBO.trackPeak();
        gCOOLANT.trackPeak();

        applyBands();

        gRPM.setInfo(getString(R.string.t_rpm), getString(R.string.i_rpm));
        gTURBO.setInfo(getString(R.string.t_turbo), getString(R.string.i_turbo));
        gCOOLANT.setInfo(getString(R.string.t_coolant), getString(R.string.i_coolant));
        gAIRFLOW.setInfo(getString(R.string.t_airflow), getString(R.string.i_airflow));
        gVOLT.setInfo(getString(R.string.t_volt), getString(R.string.i_volt));
        gFUEL.setInfo(getString(R.string.t_fuel_economy), getString(R.string.i_fuel_economy));
        GaugeInfoSheet.attach(gRPM, gTURBO, gCOOLANT, gAIRFLOW, gVOLT, gFUEL);
    }
}
