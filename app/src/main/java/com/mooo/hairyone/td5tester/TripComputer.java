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
import java.util.Set;

/**
 * The trip computer: it follows every reading for the life of the process,
 * whichever screen is showing. It keeps the trip figures, which start over at
 * each connection, and what lasts: the long-term total, the fuel left in the
 * tank and the air meter's health. The demo has its own copy of everything
 * that lasts, so simulated values never end up in the vehicle's.
 */
public final class TripComputer {

    private static final String TOTALS = "totals";
    private static final String TANK = "tank";
    private static final String MAF = "maf_health";
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
    private FuelTank tank;
    private MafHealth maf;
    private boolean demo;

    private float rpm = Float.NaN;
    private float kmh = Float.NaN;
    private float ambient = Float.NaN;
    private float manifold = Float.NaN;
    private float coolant = Float.NaN;
    private float mafMass = Float.NaN;
    private long lastSave = 0;

    private TripComputer(Context app) {
        this.app = app;
        load(Consts.DEBUG_UI);
        EventBus.getDefault().register(this);
    }

    // ---- Saving: each set is a map of doubles, stored bit for bit. ----

    private SharedPreferences prefs(String name) {
        return app.getSharedPreferences(demo ? name + "_demo" : name, Context.MODE_PRIVATE);
    }

    private Map<String, Double> read(String name, Set<String> keys) {
        SharedPreferences p = prefs(name);
        Map<String, Double> m = new HashMap<>();
        for (String key : p.getAll().keySet()) {
            if (keys == null || keys.contains(key)) {
                Object v = p.getAll().get(key);
                if (v instanceof Long) m.put(key, Double.longBitsToDouble((Long) v));
            }
        }
        return m;
    }

    private void write(String name, Map<String, Double> values, long since) {
        SharedPreferences.Editor e = prefs(name).edit().clear();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            e.putLong(entry.getKey(), Double.doubleToRawLongBits(entry.getValue()));
        }
        if (since > 0) e.putString("since", Long.toString(since));
        e.apply();
    }

    private void load(boolean demoMode) {
        demo = demoMode;
        // Saved as text since 1.5; the first test builds saved it as a number.
        Object since = prefs(TOTALS).getAll().get("since");
        long sinceMs = System.currentTimeMillis();
        if (since instanceof String) sinceMs = Long.parseLong((String) since);
        else if (since instanceof Long) sinceMs = (Long) since;
        total = Totals.fromMap(read(TOTALS, new Totals(0).toMap().keySet()), sinceMs);
        tank = FuelTank.fromMap(read(TANK, null));
        maf = MafHealth.fromMap(read(MAF, null));
        if (since == null) save();
    }

    public synchronized void save() {
        write(TOTALS, total.toMap(), total.sinceMs());
        write(TANK, tank.toMap(), 0);
        write(MAF, maf.toMap(), 0);
        lastSave = SystemClock.elapsedRealtime();
    }

    // ---- What the screens read and do. ----

    public synchronized Totals trip() { return trip; }

    public synchronized Totals total() { return total; }

    public synchronized FuelTank tank() { return tank; }

    public synchronized MafHealth maf() { return maf; }

    /** Starts the long-term total over from now. */
    public synchronized void resetTotal() {
        total.reset(System.currentTimeMillis());
        save();
    }

    public synchronized void fillTank() {
        tank.fill(Vehicle.tankLitres(app), System.currentTimeMillis());
        save();
    }

    public synchronized void addFuel(double litres) {
        tank.add(litres, Vehicle.tankLitres(app), System.currentTimeMillis());
        save();
    }

    /** Kilometres left, at the best average known; NaN until a fill-up is entered. */
    public synchronized double rangeKm() {
        return tank.rangeKm(total.fuelAverage());
    }

    // ---- Following the readings. ----

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onConnectedEvent(ConnectedEvent event) {
        synchronized (this) {
            // A new connection is a new trip. The air meter's last trip, if
            // long enough, joins its history.
            maf.finishTrip();
            if (Consts.DEBUG_UI != demo) {
                save();
                load(Consts.DEBUG_UI);
            }
            trip = new Totals(System.currentTimeMillis());
            rpm = kmh = ambient = manifold = coolant = mafMass = Float.NaN;
            save();
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
                    coolant = v;
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
                case MAF_AIR_MASS:
                    mafMass = v;
                    break;
                case MAP_AIR_MASS: {
                    // Both masses come from the same frame, the meter's first.
                    double bar = Conversions.boostBar(manifold, ambient);
                    if (MafHealth.conditionsMet(coolant, Settings.get(app, "warm_c"), bar)) {
                        maf.add(mafMass, v);
                    }
                    break;
                }
                case INJECTION_QUANTITY: {
                    double perHour = Conversions.fuelLitresPerHour(v, rpm, Settings.get(app, "fuel_correction"));
                    long now = SystemClock.elapsedRealtime();
                    trip.addFuel(perHour, kmh, now);
                    total.addFuel(perHour, kmh, now);
                    tank.update(perHour, kmh, now);
                    break;
                }
                default:
                    return;
            }
            if (SystemClock.elapsedRealtime() - lastSave > SAVE_EVERY_MS) save();
        }
    }
}
