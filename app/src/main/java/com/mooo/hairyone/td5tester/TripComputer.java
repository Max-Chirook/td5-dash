package com.mooo.hairyone.td5tester;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.events.NotConnectedEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.HashMap;
import java.util.Map;

/**
 * The trip computer: it follows every reading for the life of the process,
 * whichever screen is showing, and keeps two sets of figures. The trip set
 * starts over at each connection; the total set is saved and kept until the
 * driver resets it. The demo has its own total, so simulated values never
 * end up in the real one.
 */
public final class TripComputer {

    private static final String PREFS_REAL = "totals";
    private static final String PREFS_DEMO = "totals_demo";
    private static final long SAVE_EVERY_MS = 30_000;

    private static TripComputer instance;

    /** Trip or total in the grey lines. Back to trip each time the app starts. */
    public static volatile boolean showTotal = false;

    public static synchronized TripComputer get(Context context) {
        if (instance == null) instance = new TripComputer(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private Totals trip = new Totals(System.currentTimeMillis());
    private Totals total;
    private boolean totalIsDemo;

    private float rpm = Float.NaN;
    private float kmh = Float.NaN;
    private float ambient = Float.NaN;
    private float manifold = Float.NaN;
    private long lastSave = 0;

    private TripComputer(Context app) {
        this.app = app;
        totalIsDemo = Consts.DEBUG_UI;
        total = load(totalIsDemo);
        EventBus.getDefault().register(this);
    }

    private SharedPreferences prefs(boolean demo) {
        return app.getSharedPreferences(demo ? PREFS_DEMO : PREFS_REAL, Context.MODE_PRIVATE);
    }

    private Totals load(boolean demo) {
        SharedPreferences p = prefs(demo);
        long since = p.getLong("since", 0);
        if (since == 0) {
            since = System.currentTimeMillis();
            p.edit().putLong("since", since).apply();
        }
        Map<String, Double> m = new HashMap<>();
        for (String key : new Totals(0).toMap().keySet()) {
            if (p.contains(key)) m.put(key, Double.longBitsToDouble(p.getLong(key, 0)));
        }
        return Totals.fromMap(m, since);
    }

    public synchronized void save() {
        SharedPreferences.Editor e = prefs(totalIsDemo).edit();
        for (Map.Entry<String, Double> entry : total.toMap().entrySet()) {
            e.putLong(entry.getKey(), Double.doubleToRawLongBits(entry.getValue()));
        }
        e.putLong("since", total.sinceMs());
        e.apply();
        lastSave = SystemClock.elapsedRealtime();
    }

    /** Starts the long-term total over from now. */
    public synchronized void resetTotal() {
        total.reset(System.currentTimeMillis());
        save();
    }

    public synchronized Totals trip() { return trip; }

    public synchronized Totals total() { return total; }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onConnectedEvent(ConnectedEvent event) {
        synchronized (this) {
            // A new connection is a new trip. Switching between the demo and
            // the vehicle also switches which total is being added to.
            trip = new Totals(System.currentTimeMillis());
            if (Consts.DEBUG_UI != totalIsDemo) {
                save();
                totalIsDemo = Consts.DEBUG_UI;
                total = load(totalIsDemo);
            }
            rpm = kmh = ambient = manifold = Float.NaN;
        }
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onNotConnectedEvent(NotConnectedEvent event) { save(); }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onDashboardEvent(DashboardEvent event) {
        float v = (float) event.value;
        synchronized (this) {
            switch (event.data_type) {
                case RPM:
                    rpm = v;
                    trip.addRpm(v);
                    total.addRpm(v);
                    break;
                case VEHICLE_SPEED:
                    kmh = v;
                    break;
                case BATTERY_VOLTAGE:
                    trip.addBattery(v);
                    total.addBattery(v);
                    break;
                case COOLANT_TEMP:
                    trip.addCoolant(v);
                    total.addCoolant(v);
                    break;
                case AIR_FLOW: {
                    double flow = Conversions.airFlowOrNoReading(v);
                    if (!Conversions.isNoReading(flow)) {
                        trip.addAirflow((float) flow);
                        total.addAirflow((float) flow);
                    }
                    break;
                }
                case AMBIENT_PRESSURE:
                    ambient = v;
                    break;
                case MANIFOLD_AIR_PRESSURE: {
                    manifold = v;
                    double bar = Conversions.boostBar(manifold, ambient);
                    if (!Conversions.isNoReading(bar)) {
                        trip.addBoost((float) bar);
                        total.addBoost((float) bar);
                    }
                    break;
                }
                case INJECTION_QUANTITY: {
                    double perHour = Conversions.fuelLitresPerHour(v, rpm, Settings.get(app, "fuel_correction"));
                    long now = SystemClock.elapsedRealtime();
                    trip.addFuel(perHour, kmh, now);
                    total.addFuel(perHour, kmh, now);
                    break;
                }
                default:
                    return;
            }
            if (SystemClock.elapsedRealtime() - lastSave > SAVE_EVERY_MS) save();
        }
    }
}
