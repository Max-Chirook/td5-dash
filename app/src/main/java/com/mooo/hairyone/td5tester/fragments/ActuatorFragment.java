package com.mooo.hairyone.td5tester.fragments;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.gridlayout.widget.GridLayout;

import com.mooo.hairyone.td5tester.EcuSession;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Requests;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.NotConnectedEvent;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Actuator tests drive real hardware on the engine, so each one asks first and
 * none of them is reachable until the ECU handshake has succeeded.
 */
public class ActuatorFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    // Grouped by family: the engine, the dashboard and air conditioning, the injectors.
    private static final int[] GROUPS = {R.string.act_group_engine, R.string.act_group_dash, R.string.act_group_injectors};
    private static final Object[][][] TESTS = {
            {
                    {Requests.RequestPidEnum.TEST_FUELPUMP, R.string.act_fuelpump, R.string.act_w_general},
                    {Requests.RequestPidEnum.TEST_GLOW, R.string.act_glow, R.string.act_w_general},
                    {Requests.RequestPidEnum.TEST_PULSEREV, R.string.act_pulserev, R.string.act_w_general},
                    {Requests.RequestPidEnum.TEST_EGRMOD, R.string.act_egr, R.string.act_w_running},
                    {Requests.RequestPidEnum.TEST_TURBOMOD, R.string.act_turbo, R.string.act_w_running},
            }, {
                    {Requests.RequestPidEnum.TEST_MILLAMP, R.string.act_mil, R.string.act_w_general},
                    {Requests.RequestPidEnum.TEST_TEMPGAUGE, R.string.act_tempgauge, R.string.act_w_general},
                    {Requests.RequestPidEnum.TEST_ACCLUTCH, R.string.act_acclutch, R.string.act_w_general},
                    {Requests.RequestPidEnum.TEST_ACFAN, R.string.act_acfan, R.string.act_w_general},
            }, {
                    {Requests.RequestPidEnum.TEST_INJECT1, R.string.act_inj1, R.string.act_w_injector},
                    {Requests.RequestPidEnum.TEST_INJECT2, R.string.act_inj2, R.string.act_w_injector},
                    {Requests.RequestPidEnum.TEST_INJECT3, R.string.act_inj3, R.string.act_w_injector},
                    {Requests.RequestPidEnum.TEST_INJECT4, R.string.act_inj4, R.string.act_w_injector},
                    {Requests.RequestPidEnum.TEST_INJECT5, R.string.act_inj5, R.string.act_w_injector},
            },
    };

    private final java.util.List<View> buttons = new java.util.ArrayList<>();
    private LinearLayout groups;
    private TextView tvStatus;
    private boolean connected = false;

    public ActuatorFragment() { /* Required empty public constructor */ }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectedEvent(ConnectedEvent event) {
        connected = true;
        updateAvailability();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNotConnectedEvent(NotConnectedEvent event) {
        connected = false;
        updateAvailability();
    }

    private void updateAvailability() {
        if (groups == null) return;
        for (View b : buttons) {
            b.setEnabled(connected);
            b.setAlpha(connected ? 1f : 0.4f);
        }
        tvStatus.setText(connected ? R.string.act_ready : R.string.act_need_connection);
        tvStatus.setTextColor(getResources().getColor(
                connected ? R.color.stateNormal : R.color.stateHigh));
    }

    private void confirm(Requests.RequestPidEnum pid, String label, String warning) {
        new AlertDialog.Builder(requireContext(), R.style.InfoDialog)
                .setTitle(label)
                .setMessage(warning)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.act_run,
                        (d, w) -> EcuSession.get(requireContext()).runActuator(pid, label))
                .show();
    }

    @Override public void onStart() {
        super.onStart();
        // The link outlives this screen: ask it rather than wait for an event
        // that may have been sent before the screen existed.
        connected = EcuSession.get(requireContext()).isConnected();
        updateAvailability();
    }

    @Override protected int getFragmentLayout() { return R.layout.actuator_fragment; }

    @Override protected void bindViews(View view) {
        groups = view.findViewById(R.id.llGroups);
        tvStatus = view.findViewById(R.id.tvActuatorStatus);
        buttons.clear();
        float d = getResources().getDisplayMetrics().density;
        for (int g = 0; g < GROUPS.length; g++) {
            TextView title = new TextView(requireContext());
            title.setText(GROUPS[g]);
            title.setTextColor(getResources().getColor(R.color.labelSecondary));
            title.setTextSize(10.5f);
            title.setLetterSpacing(0.24f);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setPadding(0, g == 0 ? 0 : (int) (12 * d), 0, (int) (6 * d));
            groups.addView(title);
            View rule = new View(requireContext());
            rule.setBackgroundResource(R.drawable.hairline_left);
            groups.addView(rule, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) d)));

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (8 * d), 0, 0);
            for (int i = 0; i < 5; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (i < 4) lp.setMarginEnd((int) (12 * d));
                if (i >= TESTS[g].length) {
                    row.addView(new View(requireContext()), lp);
                    continue;
                }
                Object[] test = TESTS[g][i];
                Requests.RequestPidEnum pid = (Requests.RequestPidEnum) test[0];
                String label = getString((Integer) test[1]);
                String warning = getString((Integer) test[2]);
                TextView button = new TextView(requireContext());
                button.setText("\u25CB  " + label);
                button.setGravity(Gravity.CENTER_VERTICAL);
                button.setPadding((int) (16 * d), 0, (int) (8 * d), 0);
                button.setTextSize(14f);
                button.setMaxLines(2);
                button.setTextColor(getResources().getColor(R.color.ink));
                button.setBackgroundResource(R.drawable.test_tile);
                button.setOnClickListener(v -> confirm(pid, label, warning));
                row.addView(button, lp);
                buttons.add(button);
            }
            groups.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        connected = EcuSession.get(requireContext()).isConnected();
        updateAvailability();
    }
}
