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
    private static final String GEARS = "gears";
    private static final String BILANS = "bilans";
    private static final String HEALTH = "health";
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
    private GearEstimator gears;
    private HealthLog health;
    private boolean demo;

    // The bilans: fuel by speed and by gear, turbo by engine speed, and the
    // time held back by the limiters, for the trip and for the total.
    private final FuelBreakdown speedTrip = new FuelBreakdown(FuelBreakdown.SPEED_LABELS.length);
    private final FuelBreakdown speedTotal = new FuelBreakdown(FuelBreakdown.SPEED_LABELS.length);
    private final FuelBreakdown gearTrip = new FuelBreakdown(5);
    private final FuelBreakdown gearTotal = new FuelBreakdown(5);
    private final BoostMap boostTrip = new BoostMap();
    private final BoostMap boostTotal = new BoostMap();
    private final Limiters limTrip = new Limiters();
    private final Limiters limTotal = new Limiters();
    private Limiters.State limiter = Limiters.State.FREE;
    private int gear = 0;

    private float demand = Float.NaN;
    private float injected = Float.NaN;
    private float torque = Float.NaN;
    private long tripStartMs = 0;

    private float rpm = Float.NaN;
    private float kmh = Float.NaN;
    private float ambient = Float.NaN;
    private float manifold = Float.NaN;
    private float coolant = Float.NaN;
    private float mafMass = Float.NaN;
    private double mafPrevRpm = Double.NaN;
    private double mafPrevBar = Double.NaN;
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
        gears = GearEstimator.fromMap(read(GEARS, null));
        health = HealthLog.fromMap(read(HEALTH, null));
        Map<String, Double> b = read(BILANS, null);
        speedTotal.restore(b, "sp_");
        gearTotal.restore(b, "ge_");
        boostTotal.restore(b, "bo_");
        limTotal.restore(b, "li_");
        if (since == null) save();
    }

    public synchronized void save() {
        write(TOTALS, total.toMap(), total.sinceMs());
        write(TANK, tank.toMap(), 0);
        write(MAF, maf.toMap(), 0);
        write(GEARS, gears.toMap(), 0);
        write(HEALTH, health.toMap(), 0);
        Map<String, Double> b = new HashMap<>();
        b.putAll(speedTotal.toMap("sp_"));
        b.putAll(gearTotal.toMap("ge_"));
        b.putAll(boostTotal.toMap("bo_"));
        b.putAll(limTotal.toMap("li_"));
        write(BILANS, b, 0);
        lastSave = SystemClock.elapsedRealtime();
    }

    // ---- What the screens read and do. ----

    public synchronized Totals trip() { return trip; }

    public synchronized Totals total() { return total; }

    public synchronized FuelTank tank() { return tank; }

    public synchronized MafHealth maf() { return maf; }

    public synchronized HealthLog health() { return health; }

    public synchronized GearEstimator gears() { return gears; }

    /** The gear in use: 1 to 5, -1 to -5 in low range, 0 when unknown. */
    public synchronized int gear() { return gear; }

    /** Whether a limiter is holding the fuel back right now. */
    public synchronized Limiters.State limiter() { return limiter; }

    public synchronized FuelBreakdown fuelBySpeed(boolean total) { return total ? speedTotal : speedTrip; }

    public synchronized FuelBreakdown fuelByGear(boolean total) { return total ? gearTotal : gearTrip; }

    public synchronized BoostMap boost(boolean total) { return total ? boostTotal : boostTrip; }

    /** Turbo averages compared with the long-term reference, in percent. */
    public synchronized double boostDeviation(boolean total) {
        return (total ? boostTotal : boostTrip).deviationFrom(boostTotal);
    }

    public synchronized Limiters limiters(boolean total) { return total ? limTotal : limTrip; }

    /** Starts the long-term total over from now. */
    public synchronized void resetTotal() {
        total.reset(System.currentTimeMillis());
        speedTotal.reset();
        gearTotal.reset();
        boostTotal.reset();
        limTotal.reset();
        save();
    }

    public synchronized void fillTank() {
        tank.fill(Vehicle.tankLitres(app), System.currentTimeMillis());
        save();
    }

    /**
     * A full tank with the litres pumped. When the comparison with the fuel
     * counted since the last full tank holds up, the fuel correction is
     * adjusted by it, so the figures settle after a few fill-ups.
     */
    public synchronized FuelTank.Check fillTank(double pumped) {
        FuelTank.Check check = tank.fill(Vehicle.tankLitres(app), pumped, System.currentTimeMillis());
        if (check.result == FuelTank.Check.Result.ADJUSTED) {
            float before = Settings.get(app, "fuel_correction");
            float after = (float) Math.max(50, Math.min(200, before * check.ratio()));
            Settings.set(app, "fuel_correction", Math.round(after * 10) / 10f);
        }
        save();
        return check;
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
            health.finishTrip();
            if (Consts.DEBUG_UI != demo) {
                save();
                load(Consts.DEBUG_UI);
            }
            trip = new Totals(System.currentTimeMillis());
            speedTrip.reset();
            gearTrip.reset();
            boostTrip.reset();
            limTrip.reset();
            limiter = Limiters.State.FREE;
            gear = 0;
            tripStartMs = SystemClock.elapsedRealtime();
            demand = injected = torque = Float.NaN;
            rpm = kmh = ambient = manifold = coolant = mafMass = Float.NaN;
            mafPrevRpm = mafPrevBar = Double.NaN;
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
                    gear = gears.update(rpm, kmh);
                    break;
                case BATTERY_VOLTAGE:
                    trip.addBattery(v);
                    total.addBattery(v);
                    if (SystemClock.elapsedRealtime() - tripStartMs < HealthLog.BATTERY_WINDOW_MS) health.addStartVoltage(v);
                    break;
                case DRIVER_DEMAND:
                    demand = v;
                    break;
                case TORQUE_LIMIT:
                    torque = v;
                    break;
                case SMOKE_LIMIT: {
                    // The last word of the fuelling frame: everything is in.
                    limiter = Limiters.stateOf(demand, injected, v, torque);
                    float full = Settings.get(app, "pedal_full_mg");
                    boolean floored = demand >= 0.9f * full;
                    long now = SystemClock.elapsedRealtime();
                    limTrip.add(limiter, floored, now);
                    limTotal.add(limiter, floored, now);
                    // Turbo by engine speed, at full load only.
                    if (demand >= 0.8f * full) {
                        double bar = Conversions.boostBar(manifold, ambient);
                        if (!Conversions.isNoReading(bar)) {
                            boostTrip.add(rpm, bar);
                            boostTotal.add(rpm, bar);
                            if (BoostMap.bandOf(rpm) == 3) health.addBoostAt2500(bar);
                        }
                    }
                    break;
                }
                case POWER_BALANCE_1: case POWER_BALANCE_2: case POWER_BALANCE_3:
                case POWER_BALANCE_4: case POWER_BALANCE_5:
                    // Only warm idle gives a fair balance.
                    if (coolant >= Settings.get(app, "warm_c") && rpm < 1000 && (Float.isNaN(kmh) || kmh < 2)) {
                        health.addInjector(event.data_type.ordinal() - DashboardEvent.DATA_TYPE.POWER_BALANCE_1.ordinal(), v);
                    }
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
                    // Counted only warm, under boost, and with the engine
                    // holding steady since the previous frame.
                    double bar = Conversions.boostBar(manifold, ambient);
                    boolean steady = MafHealth.isSteady(mafPrevRpm, rpm, mafPrevBar, bar);
                    if (steady && MafHealth.conditionsMet(coolant, Settings.get(app, "warm_c"), bar)) {
                        maf.add(mafMass, v);
                    }
                    mafPrevRpm = rpm;
                    mafPrevBar = bar;
                    break;
                }
                case INJECTION_QUANTITY: {
                    double perHour = Conversions.fuelLitresPerHour(v, rpm, Settings.get(app, "fuel_correction"));
                    long now = SystemClock.elapsedRealtime();
                    trip.addFuel(perHour, kmh, now);
                    total.addFuel(perHour, kmh, now);
                    tank.update(perHour, kmh, now);
                    injected = v;
                    int slot = FuelBreakdown.speedSlot(kmh);
                    speedTrip.add(slot, perHour, kmh, now);
                    speedTotal.add(slot, perHour, kmh, now);
                    int g = gear > 0 ? gear - 1 : -1;
                    gearTrip.add(g, perHour, kmh, now);
                    gearTotal.add(g, perHour, kmh, now);
                    break;
                }
                default:
                    return;
            }
            if (SystemClock.elapsedRealtime() - lastSave > SAVE_EVERY_MS) save();
        }
    }
}
