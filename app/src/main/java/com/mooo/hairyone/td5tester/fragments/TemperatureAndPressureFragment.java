package com.mooo.hairyone.td5tester.fragments;

import android.view.View;

import com.mooo.hairyone.td5tester.Conversions;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.TripComputer;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.GaugeInfoSheet;
import com.mooo.hairyone.td5tester.views.GaugeView;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class TemperatureAndPressureFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    GaugeView gACC_TRACK_1, gACC_TRACK_2, gACC_TRACK_3, gACC_SUPPLY;
    GaugeView gAMBIENT_PRESSURE, gFUEL_TEMP, gINLET_TEMP, gEXTERNAL_TEMP;
    android.widget.TextView tvTracks;
    private final float[] tracks = {Float.NaN, Float.NaN, Float.NaN};

    public TemperatureAndPressureFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float value = (float) event.value;
        switch (event.data_type) {
            case ACC_TRACK_1:       gACC_TRACK_1.setValue(value); tracks[0] = value; break;
            case ACC_TRACK_2:       gACC_TRACK_2.setValue(value); tracks[1] = value; break;
            case ACC_TRACK_3:       gACC_TRACK_3.setValue(value); tracks[2] = value; showTracks(); break;
            // The supply channel holds still on the vehicle; the driver demand
            // from the fuelling frame is what follows the pedal.
            case DRIVER_DEMAND: {
                double percent = Conversions.pedalPercentFromDemand(value, TripComputer.get(requireContext()).pedalFull());
                if (!Conversions.isNoReading(percent)) gACC_SUPPLY.setValue((float) percent);
                break;
            }
            case AMBIENT_PRESSURE:
                gAMBIENT_PRESSURE.setValue(value);
                gAMBIENT_PRESSURE.setFootnote(getString(R.string.altitude, Math.round(altitudeMetres(value) / 10f) * 10));
                break;
            case FUEL_TEMP:         gFUEL_TEMP.setValue(value); tempFoot(gFUEL_TEMP, value, s("fuel_warn"), s("fuel_alert")); break;
            case INLET_TEMP:        gINLET_TEMP.setValue(value); tempFoot(gINLET_TEMP, value, s("inlet_warn"), s("inlet_alert")); break;
            case EXTERNAL_TEMP: {
                double outside = Conversions.externalTemperatureOrNoReading(value);
                if (Conversions.isNoReading(outside)) gEXTERNAL_TEMP.clearValue();
                else gEXTERNAL_TEMP.setValue((float) outside);
                break;
            }
        }
    }

    /**
     * The three pedal tracks should read the same, give or take a little
     * noise; a spread beyond 0.15 V means one of them is going.
     */
    private void showTracks() {
        if (tvTracks == null) return;
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        for (float t : tracks) {
            if (Float.isNaN(t)) return;
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        if (hi - lo <= 0.15f) {
            Verdict.show(tvTracks, getString(R.string.tracks_ok), getResources().getColor(R.color.stateNormal));
        } else {
            Verdict.show(tvTracks, getString(R.string.tracks_bad, hi - lo), getResources().getColor(R.color.stateHigh));
        }
    }

    private void tempFoot(GaugeView gauge, float value, float warn, float alert) {
        int text = value >= alert ? R.string.temp_very_hot : value >= warn ? R.string.temp_hot : R.string.temp_normal;
        int color = value >= alert ? R.color.stateAlert : value >= warn ? R.color.stateHigh : R.color.stateNormal;
        gauge.setFootnote(getString(text));
        gauge.setFootColor(getResources().getColor(color));
    }

    /** Standard atmosphere: the height that gives this pressure. */
    static float altitudeMetres(float kpa) {
        return (float) (44330.0 * (1 - Math.pow(kpa / 101.325, 1 / 5.255)));
    }

    private void applyBands() {
        float none = Float.NEGATIVE_INFINITY;
        float lo = s("track_alert_low"), wl = s("track_warn_low");
        float wh = s("track_warn_high"), hi = s("track_alert_high");
        gACC_TRACK_1.setBands(lo, wl, wh, hi);
        gACC_TRACK_2.setBands(lo, wl, wh, hi);
        gACC_TRACK_3.setBands(lo, wl, wh, hi);
        gFUEL_TEMP.setBands(none, none, s("fuel_warn"), s("fuel_alert"));
        gINLET_TEMP.setBands(none, none, s("inlet_warn"), s("inlet_alert"));
    }

    private float s(String key) {
        return Settings.get(requireContext(), key);
    }

    @Override public void onResume() {
        super.onResume();
        if (gACC_TRACK_1 != null) applyBands();
    }

    @Override protected int getFragmentLayout() { return R.layout.temperature_and_pressure_fragment; }

    @Override protected void bindViews(View view) {
        gACC_TRACK_1 = view.findViewById(R.id.gACC_TRACK_1);
        gACC_TRACK_2 = view.findViewById(R.id.gACC_TRACK_2);
        gACC_TRACK_3 = view.findViewById(R.id.gACC_TRACK_3);
        gACC_SUPPLY = view.findViewById(R.id.gACC_SUPPLY);
        gAMBIENT_PRESSURE = view.findViewById(R.id.gAMBIENT_PRESSURE);
        gFUEL_TEMP = view.findViewById(R.id.gFUEL_TEMP);
        gINLET_TEMP = view.findViewById(R.id.gINLET_TEMP);
        gEXTERNAL_TEMP = view.findViewById(R.id.gEXTERNAL_TEMP);
        tvTracks = view.findViewById(R.id.tvTracks);
        Verdict.show(tvTracks, getString(R.string.lim_wait), getResources().getColor(R.color.labelSecondary));

        applyBands();

        // Temperatures are read in turn; ambient pressure only now and then.
        gFUEL_TEMP.setStaleAfter(GaugeView.STALE_SLOW_MS);
        gINLET_TEMP.setStaleAfter(GaugeView.STALE_SLOW_MS);
        gEXTERNAL_TEMP.setStaleAfter(GaugeView.STALE_SLOW_MS);
        gAMBIENT_PRESSURE.setStaleAfter(GaugeView.STALE_RARE_MS);

        gACC_TRACK_1.setInfo(getString(R.string.t_track), getString(R.string.i_track));
        gACC_TRACK_2.setInfo(getString(R.string.t_track), getString(R.string.i_track));
        gACC_TRACK_3.setInfo(getString(R.string.t_track), getString(R.string.i_track));
        gACC_SUPPLY.setInfo(getString(R.string.t_pedal), getString(R.string.i_pedal));
        gAMBIENT_PRESSURE.setInfo(getString(R.string.t_ambient), getString(R.string.i_ambient));
        gFUEL_TEMP.setInfo(getString(R.string.t_fuel), getString(R.string.i_fuel));
        gINLET_TEMP.setInfo(getString(R.string.t_inlet), getString(R.string.i_inlet));
        gEXTERNAL_TEMP.setInfo(getString(R.string.t_outside), getString(R.string.i_outside));
        GaugeInfoSheet.attach(gACC_TRACK_1, gACC_TRACK_2, gACC_TRACK_3, gACC_SUPPLY, gAMBIENT_PRESSURE, gFUEL_TEMP, gINLET_TEMP, gEXTERNAL_TEMP);
    }
}
