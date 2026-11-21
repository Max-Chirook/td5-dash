package com.mooo.hairyone.td5tester.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.mooo.hairyone.td5tester.Consts;
import com.mooo.hairyone.td5tester.EcuSession;
import com.mooo.hairyone.td5tester.Faults;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Util;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.ConnectingEvent;
import com.mooo.hairyone.td5tester.events.FaultsEvent;
import com.mooo.hairyone.td5tester.events.MessageEvent;
import com.mooo.hairyone.td5tester.events.NotConnectedEvent;
import com.mooo.hairyone.td5tester.events.ToggleUIEvent;
import com.mooo.hairyone.td5tester.events.VehicleInfoEvent;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * The connection screen. It only shows the state of the link and forwards the
 * buttons: the link itself is {@link EcuSession}, which outlives this screen.
 */
public class ConnectFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    TextView tvInfo;
    ImageButton btConnect;
    TextView tvStatus;
    TextView tvVin;
    TextView tvFaults;

    public ConnectFragment() { /* Required empty public constructor */ }

    private EcuSession session() { return EcuSession.get(requireContext()); }

    @Override public void onResume() {
        super.onResume();
        showState();
        showJournal();
        session().autoConnect();
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onFaultsEvent(FaultsEvent event) {
        if (tvFaults == null) return;
        if (event.faults == null) {
            tvFaults.setText(R.string.faults_unknown);
            tvFaults.setTextColor(getResources().getColor(R.color.labelSecondary));
            return;
        }
        if (event.faults.isEmpty()) {
            tvFaults.setText(R.string.faults_none);
            tvFaults.setTextColor(getResources().getColor(R.color.stateNormal));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Faults.Fault fault : event.faults) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(fault.code()).append("  ").append(fault.label);
        }
        tvFaults.setText(sb.toString());
        tvFaults.setTextColor(getResources().getColor(R.color.stateAlert));
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    public void onVehicleInfoEvent(VehicleInfoEvent event) {
        if (tvVin == null) return;
        StringBuilder sb = new StringBuilder();
        if (!event.vin.isEmpty()) sb.append("VIN  ").append(event.vin);
        if (!event.ecuType.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("ECU  ").append(event.ecuType);
        if (!event.mapVariant.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("CARTO  ").append(event.mapVariant);
        tvVin.setText(sb.length() > 0 ? sb.toString() : getString(R.string.dash_placeholder));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(MessageEvent event) {
        log.info(event.message);
        showJournal();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectingEvent(ConnectingEvent event) { showState(); }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectedEvent(ConnectedEvent event) { showState(); }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNotConnectedEvent(NotConnectedEvent event) { showState(); }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onToggleUIEvent(ToggleUIEvent event) { showState(); }

    private void showState() {
        if (tvStatus == null || getContext() == null) return;
        EcuSession s = session();
        switch (s.state()) {
            case CONNECTING:
                tvStatus.setText(R.string.status_connecting);
                break;
            case CONNECTED:
                tvStatus.setText(Consts.DEBUG_UI ? R.string.status_connected_demo : R.string.status_connected);
                break;
            default:
                tvStatus.setText(Consts.DEBUG_UI ? R.string.status_demo : R.string.status_disconnected);
                break;
        }
        boolean enabled = !s.isBusy() && s.state() != EcuSession.State.CONNECTING;
        btConnect.setEnabled(enabled);
        btConnect.setAlpha(enabled ? 1f : 0.4f);
    }

    private void showJournal() {
        if (tvInfo == null) return;
        tvInfo.setText(TextUtils.join("\n", Util.journal()));
        // Keep the newest line in view.
        tvInfo.post(() -> {
            if (tvInfo == null || tvInfo.getLayout() == null) return;
            int scroll = tvInfo.getLayout().getLineTop(tvInfo.getLineCount()) - tvInfo.getHeight();
            tvInfo.scrollTo(0, Math.max(0, scroll));
        });
    }

    @Override protected int getFragmentLayout() { return R.layout.connect_fragment; }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.connect_fragment, container, false);
        bindViews(view);
        tvInfo.setMovementMethod(new ScrollingMovementMethod());
        return view;
    }

    @Override public void onDestroyView() {
        tvInfo = null;
        tvStatus = null;
        tvVin = null;
        tvFaults = null;
        super.onDestroyView();
    }

    private void confirmClearFaults() {
        new android.app.AlertDialog.Builder(requireContext(), R.style.InfoDialog)
                .setTitle(R.string.clear_faults_title)
                .setMessage(R.string.clear_faults_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_faults_confirm, (d, w) -> session().clearFaults())
                .show();
    }

    @Override protected void bindViews(View view) {
        tvInfo = view.findViewById(R.id.tvInfo);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvVin = view.findViewById(R.id.tvVin);
        tvFaults = view.findViewById(R.id.tvFaults);
        btConnect = view.findViewById(R.id.btConnect);
        btConnect.setOnClickListener(v -> session().toggle());
        // Demo mode is a long press, and only while nothing is connected.
        btConnect.setOnLongClickListener(v -> {
            // Consumed either way, so a long press never falls through to a
            // tap and disconnects by surprise.
            if (session().setDemo(!Consts.DEBUG_UI)) showState();
            return true;
        });
        view.findViewById(R.id.btReadFaults).setOnClickListener(v -> session().readFaults());
        view.findViewById(R.id.btClearFaults).setOnClickListener(v -> confirmClearFaults());
        showState();
        showJournal();
    }
}
