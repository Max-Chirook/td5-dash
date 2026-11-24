package com.mooo.hairyone.td5tester.fragments;

import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.Limiters;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.MafHealth;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.TripComputer;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.BarGaugeView;
import com.mooo.hairyone.td5tester.views.GaugeInfoSheet;
import com.mooo.hairyone.td5tester.views.GaugeView;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Mapping values. The fuel figures share one scale, so what holds the fuel
 * back can be seen at a glance, and a verdict says it in words; the air side
 * shows the two air masses and the air meter's long-term health.
 */
public class FuelDemandFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    GaugeView gDRIVER_DEMAND, gMAF_AIR_MASS, gMAP_AIR_MASS, gINJECTION_QUANTITY;
    GaugeView gAF_RATIO, gTORQUE_LIMIT, gSMOKE_LIMIT, gIDLE_DEMAND;
    TextView tvLimiter, tvAir;

    private static final long LIMITER_HOLD_MS = 1500;
    private Limiters.State shown = null;
    private long limitedAt = 0;

    public FuelDemandFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float value = (float) event.value;
        switch (event.data_type) {
            case DRIVER_DEMAND:      gDRIVER_DEMAND.setValue(value); break;
            case MAF_AIR_MASS:       gMAF_AIR_MASS.setValue(value); break;
            case MAP_AIR_MASS:       gMAP_AIR_MASS.setValue(value); showAir(); break;
            case INJECTION_QUANTITY: gINJECTION_QUANTITY.setValue(value); break;
            case AF_RATIO:           gAF_RATIO.setValue(value); break;
            case TORQUE_LIMIT:       gTORQUE_LIMIT.setValue(value); break;
            case SMOKE_LIMIT:
                gSMOKE_LIMIT.setValue(value);
                showLimiter(TripComputer.get(requireContext()).limiter());
                break;
            case IDLE_DEMAND:        gIDLE_DEMAND.setValue(value); break;
        }
    }

    private int color(int res) { return ContextCompat.getColor(requireContext(), res); }

    private void showLimiter(Limiters.State state) {
        long now = SystemClock.elapsedRealtime();
        if (state != Limiters.State.FREE) {
            limitedAt = now;
        } else if (shown != null && shown != Limiters.State.FREE && now - limitedAt < LIMITER_HOLD_MS) {
            return;   // held a moment, so the verdict does not flicker
        }
        if (state == shown) return;
        shown = state;
        switch (state) {
            case SMOKE: Verdict.show(tvLimiter, getString(R.string.lim_smoke), color(R.color.stateHigh)); break;
            case TORQUE: Verdict.show(tvLimiter, getString(R.string.lim_torque), color(R.color.stateAlert)); break;
            default: Verdict.show(tvLimiter, getString(R.string.lim_free), color(R.color.stateNormal)); break;
        }
    }

    /** The air meter's long-term verdict, the same as on the main tab. */
    private void showAir() {
        MafHealth maf = TripComputer.get(requireContext()).maf();
        MafHealth.Status status = maf.status(Settings.get(requireContext(), "maf_health_warn"),
                Settings.get(requireContext(), "maf_health_alert"));
        double d = maf.deviationPercent();
        String pct = Double.isNaN(d) ? "" : DashboardSheets.signedPercent(d);
        switch (status) {
            case GOOD: Verdict.show(tvAir, getString(R.string.air_ok, pct), color(R.color.stateNormal)); break;
            case WATCH: Verdict.show(tvAir, getString(R.string.air_watch, pct), color(R.color.stateHigh)); break;
            case REPLACE: Verdict.show(tvAir, getString(R.string.air_replace, pct), color(R.color.stateAlert)); break;
            default: Verdict.show(tvAir, getString(R.string.air_measuring), color(R.color.labelSecondary)); break;
        }
    }

    @Override protected int getFragmentLayout() { return R.layout.fuel_demand_fragment; }

    @Override protected void bindViews(View view) {
        gDRIVER_DEMAND = view.findViewById(R.id.gDRIVER_DEMAND);
        gMAF_AIR_MASS = view.findViewById(R.id.gMAF_AIR_MASS);
        gMAP_AIR_MASS = view.findViewById(R.id.gMAP_AIR_MASS);
        gINJECTION_QUANTITY = view.findViewById(R.id.gINJECTION_QUANTITY);
        gAF_RATIO = view.findViewById(R.id.gAF_RATIO);
        gTORQUE_LIMIT = view.findViewById(R.id.gTORQUE_LIMIT);
        gSMOKE_LIMIT = view.findViewById(R.id.gSMOKE_LIMIT);
        gIDLE_DEMAND = view.findViewById(R.id.gIDLE_DEMAND);
        tvLimiter = view.findViewById(R.id.tvLimiter);
        tvAir = view.findViewById(R.id.tvAir);

        // The demand in grey and the injected quantity in white; the two
        // limits are marks on the same scale.
        ((BarGaugeView) gDRIVER_DEMAND).setFillColor(color(R.color.tick));
        ((BarGaugeView) gTORQUE_LIMIT).setFillColor(color(R.color.stateAlert));
        ((BarGaugeView) gSMOKE_LIMIT).setFillColor(color(R.color.stateHigh));
        ((BarGaugeView) gINJECTION_QUANTITY).setFillColor(color(R.color.inkStrong));
        ((BarGaugeView) gMAF_AIR_MASS).setFillColor(color(R.color.inkStrong));
        ((BarGaugeView) gMAP_AIR_MASS).setFillColor(color(R.color.tick));

        // Idle demand is the gap to the target idle, so it reads from zero.
        gIDLE_DEMAND.setOrigin(0f);
        // Under 15 parts of air to one of fuel, a diesel starts to smoke.
        gAF_RATIO.setBands(0f, 15f, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        gAF_RATIO.setFootnote(getString(R.string.afr_hint));
        gIDLE_DEMAND.setFootnote(getString(R.string.idle_hint));

        Verdict.show(tvLimiter, getString(R.string.lim_wait), color(R.color.labelSecondary));
        showAir();

        gDRIVER_DEMAND.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gTORQUE_LIMIT.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gSMOKE_LIMIT.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gINJECTION_QUANTITY.setInfo(getString(R.string.t_mapping), getString(R.string.i_mapping));
        gIDLE_DEMAND.setInfo(getString(R.string.t_idle), getString(R.string.i_idle));
        gMAF_AIR_MASS.setInfo(getString(R.string.t_maf), getString(R.string.i_maf));
        gMAP_AIR_MASS.setInfo(getString(R.string.t_map), getString(R.string.i_map));
        gAF_RATIO.setInfo(getString(R.string.t_afr), getString(R.string.i_afr));
        GaugeInfoSheet.attach(gDRIVER_DEMAND, gTORQUE_LIMIT, gSMOKE_LIMIT, gINJECTION_QUANTITY, gIDLE_DEMAND, gMAF_AIR_MASS, gMAP_AIR_MASS, gAF_RATIO);
        tvAir.setOnClickListener(v -> DashboardSheets.showMaf(requireContext()));
    }
}
