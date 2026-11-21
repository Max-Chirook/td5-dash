package com.mooo.hairyone.td5tester.fragments;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
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

    private static final Object[][] TESTS = {
            {Requests.RequestPidEnum.TEST_FUELPUMP, R.string.act_fuelpump, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_GLOW, R.string.act_glow, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_MILLAMP, R.string.act_mil, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_TEMPGAUGE, R.string.act_tempgauge, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_PULSEREV, R.string.act_pulserev, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_ACCLUTCH, R.string.act_acclutch, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_ACFAN, R.string.act_acfan, R.string.act_w_general},
            {Requests.RequestPidEnum.TEST_EGRMOD, R.string.act_egr, R.string.act_w_running},
            {Requests.RequestPidEnum.TEST_TURBOMOD, R.string.act_turbo, R.string.act_w_running},
            {Requests.RequestPidEnum.TEST_INJECT1, R.string.act_inj1, R.string.act_w_injector},
            {Requests.RequestPidEnum.TEST_INJECT2, R.string.act_inj2, R.string.act_w_injector},
            {Requests.RequestPidEnum.TEST_INJECT3, R.string.act_inj3, R.string.act_w_injector},
            {Requests.RequestPidEnum.TEST_INJECT4, R.string.act_inj4, R.string.act_w_injector},
            {Requests.RequestPidEnum.TEST_INJECT5, R.string.act_inj5, R.string.act_w_injector},
    };

    private GridLayout grid;
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
        if (grid == null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            grid.getChildAt(i).setEnabled(connected);
            grid.getChildAt(i).setAlpha(connected ? 1f : 0.35f);
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
        grid = view.findViewById(R.id.grid);
        tvStatus = view.findViewById(R.id.tvActuatorStatus);

        int margin = (int) (5 * getResources().getDisplayMetrics().density);
        for (Object[] test : TESTS) {
            Requests.RequestPidEnum pid = (Requests.RequestPidEnum) test[0];
            String label = getString((Integer) test[1]);
            String warning = getString((Integer) test[2]);

            TextView button = new TextView(requireContext());
            button.setText(label);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(13f);
            button.setTextColor(getResources().getColor(R.color.label));
            button.setBackgroundResource(R.drawable.button_card);
            button.setOnClickListener(v -> confirm(pid, label, warning));

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(margin, margin, margin, margin);
            grid.addView(button, lp);
        }
        connected = EcuSession.get(requireContext()).isConnected();
        updateAvailability();
    }
}
