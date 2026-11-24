package com.mooo.hairyone.td5tester.fragments;

import android.view.View;
import android.widget.TextView;

import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.views.GaugeInfoSheet;
import com.mooo.hairyone.td5tester.views.GaugeView;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class InjectorFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    private final GaugeView[] balance = new GaugeView[5];
    private TextView tvConditions, tvWorstName, tvWorstValue;
    private final float[] last = {Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN};

    // The notes are explicit: these readings only mean something with the
    // engine warm and idling, so the tab says when that is not the case.

    private float rpm = Float.NaN;
    private float coolant = Float.NaN;

    public InjectorFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDashboardEvent(DashboardEvent event) {
        float value = (float) event.value;
        switch (event.data_type) {
            case POWER_BALANCE_1: show(0, value); break;
            case POWER_BALANCE_2: show(1, value); break;
            case POWER_BALANCE_3: show(2, value); break;
            case POWER_BALANCE_4: show(3, value); break;
            case POWER_BALANCE_5: show(4, value); break;
            case RPM:             rpm = value; updateConditions(); break;
            case COOLANT_TEMP:    coolant = value; updateConditions(); break;
        }
    }

    private void show(int i, float value) {
        balance[i].setValue(value);
        last[i] = value;
        if (tvWorstName == null) return;
        int worst = -1;
        for (int k = 0; k < 5; k++) {
            if (!Float.isNaN(last[k]) && (worst < 0 || Math.abs(last[k]) > Math.abs(last[worst]))) worst = k;
        }
        if (worst < 0) return;
        tvWorstName.setText(getString(R.string.inj_name, worst + 1));
        tvWorstValue.setText(String.format("%+.1f", last[worst]).replace('-', '\u2212'));
    }

    private void updateConditions() {
        if (tvConditions == null) return;
        if (Float.isNaN(rpm) || Float.isNaN(coolant)) {
            conditions(getString(R.string.inj_waiting), R.color.labelTertiary, 0);
            return;
        }
        boolean warm = coolant >= s("warm_c");
        boolean idling = rpm <= s("idle_ceiling");
        if (warm && idling) {
            conditions(getString(R.string.inj_ready), R.color.stateNormal, R.drawable.note_good);
        } else if (!warm) {
            conditions(getString(R.string.inj_cold, Math.round(coolant), Math.round(s("warm_c"))), R.color.stateHigh, R.drawable.note_warn);
        } else {
            conditions(getString(R.string.inj_not_idle), R.color.stateHigh, R.drawable.note_warn);
        }
    }

    private void conditions(String text, int colorRes, int background) {
        if (!text.contentEquals(tvConditions.getText())) tvConditions.setText(text);
        tvConditions.setTextColor(getResources().getColor(colorRes));
        tvConditions.setBackgroundResource(background);
    }

    private float s(String key) {
        return Settings.get(requireContext(), key);
    }

    @Override public void onResume() {
        super.onResume();
        if (balance[0] != null) {
            for (GaugeView gauge : balance) {
                gauge.setBands(s("inj_alert_low"), s("inj_warn_low"), s("inj_warn_high"), s("inj_alert_high"));
            }
        }
    }

    @Override protected int getFragmentLayout() { return R.layout.injector_fragment; }

    @Override protected void bindViews(View view) {
        balance[0] = view.findViewById(R.id.gPOWER_BALANCE_1);
        balance[1] = view.findViewById(R.id.gPOWER_BALANCE_2);
        balance[2] = view.findViewById(R.id.gPOWER_BALANCE_3);
        balance[3] = view.findViewById(R.id.gPOWER_BALANCE_4);
        balance[4] = view.findViewById(R.id.gPOWER_BALANCE_5);
        tvConditions = view.findViewById(R.id.tvConditions);
        tvWorstName = view.findViewById(R.id.tvWorstName);
        tvWorstValue = view.findViewById(R.id.tvWorstValue);
        updateConditions();

        // Ideal is -4 to +4 warm at idle, tolerance runs to -15 / +15.
        for (GaugeView gauge : balance) {
            gauge.setOrigin(0f);
            gauge.setStaleAfter(GaugeView.STALE_SLOW_MS);
            gauge.setBands(s("inj_alert_low"), s("inj_warn_low"), s("inj_warn_high"), s("inj_alert_high"));
            gauge.setInfo(getString(R.string.t_inj), getString(R.string.i_inj));
        }
        GaugeInfoSheet.attach(balance);
    }
}
