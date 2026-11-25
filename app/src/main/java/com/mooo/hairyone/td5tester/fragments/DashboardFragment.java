package com.mooo.hairyone.td5tester.fragments;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import com.mooo.hairyone.td5tester.Conversions;
import com.mooo.hairyone.td5tester.FuelTank;
import com.mooo.hairyone.td5tester.GearEstimator;
import com.mooo.hairyone.td5tester.Limiters;
import com.mooo.hairyone.td5tester.MafHealth;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.Totals;
import com.mooo.hairyone.td5tester.TripComputer;
import com.mooo.hairyone.td5tester.Vehicle;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.BarGaugeView;
import com.mooo.hairyone.td5tester.views.GaugeInfoSheet;
import com.mooo.hairyone.td5tester.views.GaugeView;
import com.mooo.hairyone.td5tester.views.ReadoutView;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DashboardFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    GaugeView gRPM, gVOLT, gTURBO, gCOOLANT, gAIRFLOW, gFUEL;
    TextView tvWarmup;
    // The trip / total switch; only the wide layout has it.
    TextView tvTrip, tvTotal;
    // The range pill; only the wide layout has it.
    TextView tvRange;

    // Below this the engine is still coming up to temperature; the strip is
    // hidden once there, so a clean screen means nothing needs saying.

    private static final long WARM_CONFIRMATION_MS = 5000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean warmAnnounced = false;

    // Boost is the rise above ambient, so the last ambient reading is kept.
    private float ambientPressureKpa = Float.NaN;
    private float manifoldPressureKpa = Float.NaN;

    // Fuel economy comes from the injected quantity, the engine speed and the
    // road speed, all three read on every pass.
    private float rpm = Float.NaN;
    private float kmh = Float.NaN;

    public DashboardFragment() { /* Required empty public constructor */ }

    private TripComputer computer() { return TripComputer.get(requireContext()); }

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
                break;
            case COOLANT_TEMP:
                gCOOLANT.setValue(value);
                updateWarmup(value);
                break;
            case VEHICLE_SPEED:
                gRPM.setSecondary(String.format("%.0f km/h", value));
                kmh = value;
                if (gFUEL instanceof ReadoutView) {
                    ((ReadoutView) gFUEL).setGear(GearEstimator.label(computer().gear()));
                }
                break;
            case SMOKE_LIMIT:
                // The last word of the fuelling frame: the limiter state is known.
                updateLimiter(computer().limiter());
                return;
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
            default:
                return;
        }
        scheduleFeet();
    }

    // The lines under the readings change slowly; redrawing them on every
    // reading (a dozen a second) only costs battery, so at most twice a second.
    private static final long FEET_EVERY_MS = 500;
    private long feetShownAt = 0;
    private boolean feetPending = false;
    private final Runnable feetRunnable = () -> {
        feetPending = false;
        feetShownAt = android.os.SystemClock.elapsedRealtime();
        showFeet();
    };

    private void scheduleFeet() {
        if (feetPending) return;
        long wait = FEET_EVERY_MS - (android.os.SystemClock.elapsedRealtime() - feetShownAt);
        if (wait <= 0) {
            feetRunnable.run();
        } else {
            feetPending = true;
            handler.postDelayed(feetRunnable, wait);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectedEvent(ConnectedEvent event) {
        // The trip computer has started a new trip; show it from zero.
        warmAnnounced = false;
        showFeet();
    }

    /**
     * L/100 km while moving; stopped, a distance figure would be meaningless,
     * so the gauge shows L/h instead and says so in its unit.
     */
    private void updateFuel(float mgPerStroke) {
        double perHour = Conversions.fuelLitresPerHour(mgPerStroke, rpm, s("fuel_correction"));
        double per100 = Conversions.litresPer100Km(perHour, kmh);
        if (Conversions.isNoReading(per100)) {
            gFUEL.setUnit(getString(R.string.unit_l_per_hour));
            gFUEL.setValue((float) perHour);
        } else {
            gFUEL.setUnit(getString(R.string.unit_l_per_100));
            gFUEL.setValue((float) per100);
        }
    }

    /**
     * The grey lines under the readings: this trip, or the long-term total in
     * violet, so the two are never mistaken for one another.
     */
    private void showFeet() {
        if (gRPM == null || getContext() == null) return;
        boolean total = TripComputer.showTotal;
        Totals t = total ? computer().total() : computer().trip();
        int color = total ? getResources().getColor(R.color.stateTotal) : 0;

        foot(gRPM, "max %.0f", t.rpmMax(), color);
        foot(gTURBO, "max %.2f", t.boostMax(), color);
        foot(gCOOLANT, "max %.0f", t.coolantMax(), color);
        foot(gAIRFLOW, "max %.0f", t.airflowMax(), color);
        foot(gVOLT, "moy. %.1f", t.batteryMean(), color);

        // A dash until the first kilometre, so the line is there from the start.
        double avg = t.fuelAverage();
        gFUEL.setFootnote(Double.isNaN(avg) ? "moy. —" : String.format("moy. %.1f", avg));
        gFUEL.setFootColor(color);
        if (gFUEL instanceof ReadoutView) {
            ((ReadoutView) gFUEL).setExtra(distance(t, total));
            ((ReadoutView) gFUEL).setAverage((float) avg);
        }

        showRange();
        if (gAIRFLOW instanceof BarGaugeView) {
            MafHealth.Status status = computer().maf().status(s("maf_health_warn"), s("maf_health_alert"));
            String verdict = DashboardSheets.mafStatusText(computer().maf(), status);
            ((BarGaugeView) gAIRFLOW).setStatus(verdict, DashboardSheets.mafColor(requireContext(), status));
        }

        if (tvTrip != null) {
            tvTrip.setBackgroundResource(total ? 0 : R.drawable.underline_on);
            tvTrip.setTextColor(getResources().getColor(total ? R.color.labelSecondary : R.color.ink));
            tvTotal.setBackgroundResource(total ? R.drawable.underline_on : 0);
            tvTotal.setTextColor(getResources().getColor(total ? R.color.stateTotal : R.color.labelSecondary));
        }
    }

    /**
     * "Autonomie  412 km · 38 L": the figure in white, or orange then red as
     * the tank runs low, the rest in grey. Only the wide layout has it.
     */
    private void showRange() {
        if (tvRange == null) return;
        Context c = requireContext();
        SpannableStringBuilder sb = new SpannableStringBuilder(getString(R.string.range_label).toUpperCase(Locale.FRANCE));
        sb.setSpan(new RelativeSizeSpan(0.82f), 0, sb.length(), 0);
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
        sb.append("   ");
        FuelTank tank = computer().tank();
        if (Vehicle.model(c) < 0 || !tank.isKnown()) {
            sb.append(getString(R.string.range_set));
        } else {
            double km = computer().rangeKm();
            int start = sb.length();
            sb.append(String.format(Locale.FRANCE, "%.0f km", km));
            int kmColor = DashboardSheets.rangeColor(c, km);
            sb.setSpan(new ForegroundColorSpan(kmColor), start, sb.length(), 0);
            sb.setSpan(new RelativeSizeSpan(1.3f), start, sb.length(), 0);
            sb.append(String.format(Locale.FRANCE, "    %.0f L", tank.remaining()));
        }
        if (!TextUtils.equals(tvRange.getText(), sb)) tvRange.setText(sb);
    }

    private static void foot(GaugeView gauge, String format, float value, int color) {
        String dash = format.substring(0, format.indexOf('%')) + "—";
        gauge.setFootnote(Float.isNaN(value) ? dash : String.format(format, value));
        gauge.setFootColor(color);
    }

    private static String distance(Totals t, boolean total) {
        double km = t.km();
        String figure = km < 10
                ? String.format(Locale.ROOT, "%.1f km", km)
                : String.format(Locale.FRANCE, "%,.0f km", km);
        if (!total) return figure;
        String since = new SimpleDateFormat("dd/MM/yy", Locale.FRANCE).format(new Date(t.sinceMs()));
        return figure + " depuis le " + since;
    }

    private void setShowTotal(boolean total) {
        TripComputer.showTotal = total;
        showFeet();
    }

    private void confirmResetTotal() {
        new android.app.AlertDialog.Builder(requireContext(), R.style.InfoDialog)
                .setTitle(R.string.total_reset_title)
                .setMessage(R.string.total_reset_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.total_reset_confirm, (d, w) -> {
                    computer().resetTotal();
                    showFeet();
                })
                .show();
    }

    // The centre of the top line shows "BRIDÉ" while a limiter holds the fuel
    // back, held a moment so it does not flicker, and the warm-up note otherwise.
    private static final long LIMITER_HOLD_MS = 1500;
    private Limiters.State shownLimiter = Limiters.State.FREE;
    private long limiterSeenMs = 0;
    private CharSequence warmText = null;
    private int warmColor = 0;
    private boolean warmVisible = false;

    private void updateLimiter(Limiters.State state) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (state != Limiters.State.FREE) {
            shownLimiter = state;
            limiterSeenMs = now;
        } else if (now - limiterSeenMs > LIMITER_HOLD_MS) {
            shownLimiter = Limiters.State.FREE;
        }
        showBanner();
    }

    private void showBanner() {
        if (tvWarmup == null) return;
        if (shownLimiter != Limiters.State.FREE) {
            tvWarmup.setText(shownLimiter == Limiters.State.SMOKE ? R.string.limited_smoke : R.string.limited_torque);
            tvWarmup.setTextColor(getResources().getColor(R.color.stateHigh));
            tvWarmup.setBackgroundResource(R.drawable.pill_warn);
            tvWarmup.setVisibility(View.VISIBLE);
            return;
        }
        if (!warmVisible) {
            tvWarmup.setVisibility(View.GONE);
            return;
        }
        tvWarmup.setText(warmText);
        tvWarmup.setTextColor(warmColor);
        tvWarmup.setBackgroundResource(warmColor == getResources().getColor(R.color.stateNormal) ? R.drawable.pill_good : R.drawable.pill_info);
        tvWarmup.setVisibility(View.VISIBLE);
    }

    private void showWarm(CharSequence text, int colorRes) {
        warmText = text;
        warmColor = getResources().getColor(colorRes);
        warmVisible = true;
        showBanner();
    }

    private void updateWarmup(float celsius) {
        if (tvWarmup == null) return;
        float warmAt = s("warm_c");
        if (celsius < warmAt) {
            boolean freezing = celsius < s("cold_c");
            showWarm(getString(freezing ? R.string.warm_cold : R.string.warm_rising,
                    Math.round(celsius)), R.color.stateInfo);
            warmAnnounced = false;
            return;
        }
        if (warmAnnounced) return;
        warmAnnounced = true;
        showWarm(getString(R.string.warm_ready), R.color.stateNormal);
        handler.postDelayed(() -> {
            warmVisible = false;
            showBanner();
        }, WARM_CONFIRMATION_MS);
    }

    @Override public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        feetPending = false;
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
        gFUEL.setBands(none, none, 20f, Float.POSITIVE_INFINITY);
    }

    private float s(String key) {
        return Settings.get(requireContext(), key);
    }

    @Override public void onResume() {
        super.onResume();
        if (gRPM != null) {
            applyBands();
            showFeet();
        }
    }

    @Override public void onPause() {
        // The total is also saved every 30 s while reading; this covers the
        // app being closed in between.
        computer().save();
        super.onPause();
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
        tvTrip = view.findViewById(R.id.tvTrip);
        tvTotal = view.findViewById(R.id.tvTotal);
        tvRange = view.findViewById(R.id.tvRange);

        if (tvTrip != null) {
            tvTrip.setOnClickListener(v -> setShowTotal(false));
            tvTotal.setOnClickListener(v -> setShowTotal(true));
            // Resetting the total needs a long press and a confirmation, so
            // months of figures are never lost to a stray touch.
            tvTotal.setOnLongClickListener(v -> {
                setShowTotal(true);
                confirmResetTotal();
                return true;
            });
        }

        // Read in turn with the other slow values, not on every pass.
        gCOOLANT.setStaleAfter(GaugeView.STALE_SLOW_MS);
        gVOLT.setStaleAfter(GaugeView.STALE_SLOW_MS);

        applyBands();
        showFeet();
        // The gear badge is there from the start, so the centre does not jump.
        if (gFUEL instanceof ReadoutView) ((ReadoutView) gFUEL).setGear(GearEstimator.label(computer().gear()));

        gRPM.setInfo(getString(R.string.t_rpm), getString(R.string.i_rpm));
        gTURBO.setInfo(getString(R.string.t_turbo), getString(R.string.i_turbo));
        gCOOLANT.setInfo(getString(R.string.t_coolant), getString(R.string.i_coolant));
        gAIRFLOW.setInfo(getString(R.string.t_airflow), getString(R.string.i_airflow));
        gVOLT.setInfo(getString(R.string.t_volt), getString(R.string.i_volt));
        gFUEL.setInfo(getString(R.string.t_fuel_economy), getString(R.string.i_fuel_economy));
        GaugeInfoSheet.attach(gRPM, gTURBO, gCOOLANT, gAIRFLOW, gVOLT, gFUEL);
        // On the cluster the air meter opens its health screen, which carries
        // the same note at its foot.
        if (gAIRFLOW instanceof BarGaugeView) {
            gAIRFLOW.setOnClickListener(v -> DashboardSheets.showMaf(requireContext()));
        }
        if (tvRange != null) {
            tvRange.setOnClickListener(v -> DashboardSheets.showRange(requireContext(), this::showFeet));
        }
    }
}
