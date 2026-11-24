package com.mooo.hairyone.td5tester;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import androidx.core.content.ContextCompat;

import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.ConnectingEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.events.FaultsEvent;
import com.mooo.hairyone.td5tester.events.NotConnectedEvent;
import com.mooo.hairyone.td5tester.events.ToggleUIEvent;
import com.mooo.hairyone.td5tester.events.VehicleInfoEvent;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The link to the ECU, one per process.
 *
 * It used to live in the Connect screen, and Android rebuilds that screen on
 * every split-screen or size change: the old link kept reading unseen while the
 * new screen believed nothing was connected, and a second link could open next
 * to it. Here the link outlives the screens, and every bus operation (connect,
 * disconnect, faults, actuator tests) runs in turn on a single worker thread,
 * so two of them can never talk to the ECU at the same time.
 */
public final class EcuSession {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private static EcuSession instance;

    public static synchronized EcuSession get(Context context) {
        if (instance == null) instance = new EcuSession(context.getApplicationContext());
        return instance;
    }

    private final Logger log = Log4jHelper.getLogger(EcuSession.class);
    private final Context context;
    private final ExecutorService bus = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ecu-bus");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.DISCONNECTED;
    private volatile boolean busy = false;
    // Set by a deliberate disconnect, so the automatic connection leaves it alone.
    private volatile boolean userDisconnected = false;

    // Everything below is only touched from the bus thread and the dashboard
    // thread, and the dashboard is always stopped before the bus uses it.
    byte[] mReadBuffer = new byte[Consts.RESPONSE_BUFFER_SIZE];
    boolean mFastInitCompleted = false;
    private volatile boolean mDashboardRunning = false;
    private Thread mDashboardThread = null;
    Requests mRequests = new Requests();
    UsbDevice mUsbDevice = null;
    UsbInterface mUsbInterface = null;
    UsbEndpoint mUsbEndpointIn = null;
    UsbEndpoint mUsbEndpointOut = null;
    UsbDeviceConnection mUsbDeviceConnection = null;
    boolean mFakeUsbDeviceConnection = false;
    // One recording per connection; reads paused for a test append to it.
    private String csvFilename = null;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (isFtdi(device)) {
                    Util.log_msg("Câble branché.");
                    cableAttached();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (isFtdi(device)) {
                    Util.log_msg("Câble débranché.");
                    bus.execute(EcuSession.this::closeLink);
                }
            }
        }
    };

    private EcuSession(Context context) {
        this.context = context;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // System broadcasts reach a non-exported receiver; nothing else should.
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    // ---------------------------------------------------------------- state

    public State state() { return state; }

    public boolean isConnected() { return state == State.CONNECTED; }

    /** A bus operation is running; the connect button waits for it. */
    public boolean isBusy() { return busy; }

    private void setState(State next) {
        state = next;
        switch (next) {
            case CONNECTING: EventBus.getDefault().post(new ConnectingEvent()); break;
            case CONNECTED: EventBus.getDefault().post(new ConnectedEvent()); break;
            default: EventBus.getDefault().post(new NotConnectedEvent()); break;
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        EventBus.getDefault().post(new ToggleUIEvent(!value));
    }

    // ------------------------------------------------------------- commands

    /** The connect button: connects when idle, disconnects when connected. */
    public synchronized void toggle() {
        if (state == State.DISCONNECTED) {
            userDisconnected = false;
            connect();
        } else if (state == State.CONNECTED) {
            userDisconnected = true;
            bus.execute(this::closeLink);
        }
        // While connecting, the press is ignored: the attempt finishes on its own.
    }

    /** Opens the link by itself when the cable is there, so the app lands on live data. */
    public synchronized void autoConnect() {
        if (userDisconnected || Consts.DEBUG_UI) return;
        if (state != State.DISCONNECTED) return;
        if (findFtdiDevice() == null) return;
        connect();
    }

    /** The cable was plugged in, which counts as asking for the link. */
    public synchronized void cableAttached() {
        userDisconnected = false;
        autoConnect();
    }

    private synchronized void connect() {
        if (state != State.DISCONNECTED) return;
        setState(State.CONNECTING);
        bus.execute(this::openLink);
    }

    /** Demo mode can only change while nothing is connected. */
    public synchronized boolean setDemo(boolean on) {
        if (state != State.DISCONNECTED) return false;
        Consts.DEBUG_UI = on;
        Util.log_msg(on ? "Mode démo activé : les valeurs sont simulées."
                        : "Mode démo désactivé.");
        return true;
    }

    public void readFaults() { bus.execute(this::doReadFaults); }

    public void clearFaults() { bus.execute(this::doClearFaults); }

    public void runActuator(Requests.RequestPidEnum pid, String label) {
        bus.execute(() -> doRunActuator(pid, label));
    }

    /** The app is closing for good: stop reading and let go of the cable. */
    public synchronized void shutdown() {
        userDisconnected = false;
        bus.execute(this::closeLink);
    }

    // ------------------------------------------------------- link lifecycle

    private void openLink() {
        setBusy(true);
        EventBus.getDefault().postSticky(new VehicleInfoEvent("", "", ""));
        EventBus.getDefault().postSticky(new FaultsEvent(null));
        boolean ok = false;
        try {
            ok = usbOpen() && fastInit();
        } catch (Exception ex) {
            log.error("openLink", ex);
            Util.log_msg("Erreur pendant la connexion : " + ex.getMessage());
        }
        if (ok) {
            String stamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", java.util.Locale.US).format(new Date());
            csvFilename = context.getExternalFilesDir(null) + File.separator + "td5tester_" + stamp + ".csv";
            Util.log_msg("Lecture en direct, enregistrée dans " + new File(csvFilename).getName());
            setState(State.CONNECTED);
            readVehicleInfo();
            startDashboard();
        } else {
            releaseUsb();
            setState(State.DISCONNECTED);
        }
        setBusy(false);
    }

    private void closeLink() {
        boolean open = state != State.DISCONNECTED || mFakeUsbDeviceConnection || mUsbDeviceConnection != null;
        if (!open) return;
        setBusy(true);
        stopDashboard();
        releaseUsb();
        Util.log_msg("Déconnecté.");
        setState(State.DISCONNECTED);
        setBusy(false);
    }

    private boolean usbOpen() {
        Util.log_msg("Connexion au câble USB…");

        if (Consts.DEBUG_UI) {
            sleep(1500);
            mFakeUsbDeviceConnection = true;
            Util.log_msg("Câble simulé (mode démo).");
            return true;
        }

        mFastInitCompleted = false;
        mUsbDevice = findFtdiDevice();
        if (mUsbDevice == null) {
            Util.log_msg("Câble FTDI introuvable : vérifie qu'il est bien branché.");
            return false;
        }

        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        // Asking again for a device we already hold is what made the dialog
        // appear on every single connection.
        if (!manager.hasPermission(mUsbDevice)) {
            Util.log_msg("Autorisation USB demandée : accepte-la à l'écran.");
            int flags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
            Intent intent = new Intent(Consts.ACTION_USB_PERMISSION).setPackage(context.getPackageName());
            manager.requestPermission(mUsbDevice, PendingIntent.getBroadcast(context, 0, intent, flags));
            // The answer is read from the manager itself: an immutable intent
            // comes back without the "granted" extra, so it cannot be trusted.
            long deadline = System.currentTimeMillis() + Consts.PERMISSION_WAIT_SECS * 1000L;
            while (!manager.hasPermission(mUsbDevice) && System.currentTimeMillis() < deadline) {
                sleep(500);
            }
            if (!manager.hasPermission(mUsbDevice)) {
                Util.log_msg("Autorisation USB refusée ou sans réponse.");
                return false;
            }
        }

        for (int i = 0; i < mUsbDevice.getInterfaceCount(); i++) {
            UsbInterface usbInterface = mUsbDevice.getInterface(i);
            UsbEndpoint out = null, in = null;
            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                UsbEndpoint e = usbInterface.getEndpoint(j);
                if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == UsbConstants.USB_DIR_OUT) out = e;
                if (e.getDirection() == UsbConstants.USB_DIR_IN) in = e;
            }
            if (out != null && in != null) {
                mUsbInterface = usbInterface;
                mUsbEndpointOut = out;
                mUsbEndpointIn = in;
                break;
            }
        }
        if (mUsbInterface == null) {
            Util.log_msg("Le câble ne présente pas d'interface FTDI utilisable.");
            return false;
        }

        mUsbDeviceConnection = manager.openDevice(mUsbDevice);
        if (mUsbDeviceConnection == null) {
            Util.log_msg("Impossible d'ouvrir le câble USB.");
            return false;
        }
        if (!mUsbDeviceConnection.claimInterface(mUsbInterface, true)) {
            Util.log_msg("Le câble USB est déjà utilisé par une autre application.");
            return false;
        }
        control_transfer(FTDI.SIO_RESET,         FTDI.SIO_RESET_SIO,         FTDI.CH_A);
        control_transfer(FTDI.SIO_RESET,         FTDI.SIO_RESET_PURGE_RX,    FTDI.CH_A);
        control_transfer(FTDI.SIO_RESET,         FTDI.SIO_RESET_PURGE_TX,    FTDI.CH_A);
        control_transfer(FTDI.SIO_SET_FLOW_CTRL, FTDI.SIO_DISABLE_FLOW_CTRL, FTDI.CH_A);
        control_transfer(FTDI.SIO_SET_DATA,      FTDI.LINE_8N1,              FTDI.CH_A);
        control_transfer(FTDI.SIO_SET_BAUDRATE,  FTDI.BAUDRATE_10400);
        // The chip otherwise holds each reply up to 16 ms before handing it
        // over, on every single request.
        control_transfer(FTDI.SIO_SET_LATENCY_TIMER, FTDI.LATENCY_FAST, FTDI.CH_A);
        Util.log_msg(String.format("Câble ouvert (%s).", mUsbDevice.getProductName()));
        return true;
    }

    private boolean fastInit() {
        Util.log_msg("Initialisation du calculateur (FASTINIT)…");
        mFastInitCompleted = false;

        if (Consts.DEBUG_UI) {
            sleep(1500);
            mFastInitCompleted = true;
        } else {
            byte[] HI = new byte[]{(byte) 0x01};
            byte[] LO = new byte[]{(byte) 0x00};
            try {
                control_transfer(FTDI.SIO_SET_BITMODE, FTDI.BITBANG_ON);
                bulk_transfer_write(HI, 1);
                Thread.sleep(500);
                bulk_transfer_write(LO, 1);
                Thread.sleep(25);
                bulk_transfer_write(HI, 1);
                Thread.sleep(25);
                control_transfer(FTDI.SIO_SET_BITMODE, FTDI.BITBANG_OFF);

                control_transfer(FTDI.SIO_RESET, FTDI.SIO_RESET_PURGE_RX, FTDI.CH_A);
                control_transfer(FTDI.SIO_RESET, FTDI.SIO_RESET_PURGE_TX, FTDI.CH_A);

                if (get_pid(Requests.RequestPidEnum.INIT_FRAME) && get_pid(Requests.RequestPidEnum.START_DIAGNOSTICS) && get_pid(Requests.RequestPidEnum.REQUEST_SEED)) {
                    short seed = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                    int key = generate_key(seed);
                    mRequests.request.get(Requests.RequestPidEnum.KEY_RETURN).request[3] = (byte) ((key & 0xFFFF) >>> 8);
                    mRequests.request.get(Requests.RequestPidEnum.KEY_RETURN).request[4] = (byte) (key & 0xFF);
                    mFastInitCompleted = get_pid(Requests.RequestPidEnum.KEY_RETURN);
                }
            } catch (Exception ex) {
                log.error("fastInit", ex);
            }
        }
        Util.log_msg(mFastInitCompleted
                ? "Calculateur connecté."
                : "Le calculateur ne répond pas. Vérifie que le contact est mis.");
        return mFastInitCompleted;
    }

    private void releaseUsb() {
        try {
            if (mUsbDeviceConnection != null) {
                if (mUsbInterface != null) mUsbDeviceConnection.releaseInterface(mUsbInterface);
                mUsbDeviceConnection.close();
            }
        } catch (Exception ex) {
            log.error("releaseUsb", ex);
        }
        mUsbDeviceConnection = null;
        mUsbInterface = null;
        mUsbDevice = null;
        mUsbEndpointIn = null;
        mUsbEndpointOut = null;
        mFastInitCompleted = false;
        mFakeUsbDeviceConnection = false;
    }

    private UsbDevice findFtdiDevice() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isFtdi(device)) return device;
        }
        return null;
    }

    private static boolean isFtdi(UsbDevice device) {
        return device != null && device.getVendorId() == FTDI.VENDOR_ID && device.getProductId() == FTDI.PRODUCT_ID;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // ------------------------------------------------------ bus operations

    private void doReadFaults() {
        if (!isConnected()) {
            Util.log_msg("Lecture des défauts impossible : calculateur non connecté.");
            return;
        }
        setBusy(true);
        stopDashboard();
        Util.log_msg("Lecture des défauts…");
        if (Consts.DEBUG_UI) {
            sleep(1200);
            byte[] demo = new byte[Faults.FIRST_BYTE + Faults.BYTE_COUNT];
            demo[Faults.FIRST_BYTE] = (byte) 0b01000000;
            demo[Faults.FIRST_BYTE + 6] = (byte) 0b00000100;
            List<Faults.Fault> found = Faults.decode(demo);
            Util.log_msg(String.format("%d défaut(s) lu(s).", found.size()));
            EventBus.getDefault().postSticky(new FaultsEvent(found));
        } else if (get_pid(Requests.RequestPidEnum.FAULT_CODES)) {
            List<Faults.Fault> found = Faults.decode(mReadBuffer);
            for (Faults.Fault fault : found) {
                Util.log_msg(String.format("%s %s", fault.code(), fault.label));
            }
            Util.log_msg(String.format("%d défaut(s) lu(s).", found.size()));
            EventBus.getDefault().postSticky(new FaultsEvent(found));
        } else {
            Util.log_msg("Le calculateur n'a pas répondu à la lecture des défauts.");
        }
        startDashboard();
        setBusy(false);
    }

    private void doClearFaults() {
        if (!isConnected()) {
            Util.log_msg("Effacement impossible : calculateur non connecté.");
            return;
        }
        setBusy(true);
        stopDashboard();
        Util.log_msg("Effacement des défauts…");
        boolean ok;
        if (Consts.DEBUG_UI) {
            sleep(2000);
            ok = true;
        } else {
            ok = get_pid(Requests.RequestPidEnum.CLEAR_FAULTS);
        }
        if (ok) {
            Util.log_msg("Défauts effacés.");
            EventBus.getDefault().postSticky(new FaultsEvent(null));
        } else {
            Util.log_msg("Le calculateur a refusé l'effacement des défauts.");
        }
        startDashboard();
        setBusy(false);
    }

    private void doRunActuator(Requests.RequestPidEnum pid, String label) {
        if (!isConnected()) {
            Util.log_msg(String.format("%s : impossible, calculateur non connecté.", label));
            return;
        }
        setBusy(true);
        stopDashboard();
        if (Consts.DEBUG_UI) {
            sleep(800);
            Util.log_msg(String.format("%s : simulé (mode démo).", label));
        } else {
            boolean ok = get_pid(pid);
            Util.log_msg(String.format("%s : %s", label, ok ? "commande acceptée" : "refusée par le calculateur"));
        }
        startDashboard();
        setBusy(false);
    }

    // ------------------------------------------------------- live reading

    private void startDashboard() {
        if (!mFastInitCompleted || csvFilename == null) return;
        if (mDashboardThread != null && mDashboardThread.isAlive()) return;
        mDashboardRunning = true;
        mDashboardThread = new Thread(this::dashboard, "ecu-dashboard");
        mDashboardThread.start();
    }

    /** Stops the live reading and waits for it, so the bus is free on return. */
    private void stopDashboard() {
        mDashboardRunning = false;
        Thread t = mDashboardThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(5000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        mDashboardThread = null;
    }

    private void dashboard() {

        SimpleDateFormat simpleDateFormat;
        String csv_filename = csvFilename;
        LogRecord logRecord = new LogRecord();

        try (
            PrintWriter csv_filehandle = new PrintWriter(new FileOutputStream(new File(csv_filename), true))
        ) {
            boolean lost = false;
            simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            long pass = 0;
            boolean ambientKnown = false;
            while (mDashboardRunning) {
                if (Consts.DEBUG_UI) {
                    simulate();
                    try { Thread.sleep(250); } catch (Exception ex) {}
                } else {
                    int pid_count = 0;
                    java.util.Set<Requests.RequestPidEnum> due = PollSchedule.forPass(pass++, ambientKnown);
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.ENGINE_RPM) && get_pid(Requests.RequestPidEnum.ENGINE_RPM)) {
                        // 04 61 09 [03 06] 77
                        logRecord.EngineRpm = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.RPM, logRecord.EngineRpm));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.BATTERY_VOLTAGE) && get_pid(Requests.RequestPidEnum.BATTERY_VOLTAGE)) {
                        // 06 61 10 [36 2E] 35 FA 0A
                        logRecord.BatteryVoltage = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.BATTERY_VOLTAGE, Conversions.volts(logRecord.BatteryVoltage)));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.VEHICLE_SPEED) && get_pid(Requests.RequestPidEnum.VEHICLE_SPEED)) {
                        // 03 61 0D [00] 71
                        // Unsigned: a signed byte would turn 130 km/h into -126.
                        logRecord.VehicleSpeed = (short) (mReadBuffer[3] & 0xFF);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.VEHICLE_SPEED, realSpeed(logRecord.VehicleSpeed)));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.TEMPERATURES) && get_pid(Requests.RequestPidEnum.TEMPERATURES)) {
                        // The sensor on the airbox is AAP(ambient air pressure and temp) sensor and it has temp reading only on EU3 models.
                        // 12 61 1A [0C 80] 06 A4 [0C 52] 07 59 [10 88] 00 04 [0C 06] 08 A6 DD
                        logRecord.CoolantTemperature  = Util.bytes2short(mReadBuffer[ 3], mReadBuffer[ 4]);
                        logRecord.InletTemperature    = Util.bytes2short(mReadBuffer[ 7], mReadBuffer[ 8]);
                        logRecord.ExternalTemperature = Util.bytes2short(mReadBuffer[11], mReadBuffer[12]);
                        logRecord.FuelTemperature     = Util.bytes2short(mReadBuffer[15], mReadBuffer[16]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.COOLANT_TEMP,  Conversions.temperatureCelsius(logRecord.CoolantTemperature)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.INLET_TEMP,    Conversions.temperatureCelsius(logRecord.InletTemperature)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.EXTERNAL_TEMP, Conversions.temperatureCelsius(logRecord.ExternalTemperature)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.FUEL_TEMP,     Conversions.temperatureCelsius(logRecord.FuelTemperature)));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.THROTTLE_POSITION) && get_pid(Requests.RequestPidEnum.THROTTLE_POSITION)) {
                        // 0A 61 1B [01 68] [12 1E] [00 00] [13 82] B4
                        logRecord.AcceleratorTrack1 = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        logRecord.AcceleratorTrack2 = Util.bytes2short(mReadBuffer[5], mReadBuffer[6]);
                        logRecord.AcceleratorTrack3 = Util.bytes2short(mReadBuffer[7], mReadBuffer[8]);
                        logRecord.AcceleratorSupply = Util.bytes2short(mReadBuffer[9], mReadBuffer[10]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_TRACK_1, Conversions.volts(logRecord.AcceleratorTrack1)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_TRACK_2, Conversions.volts(logRecord.AcceleratorTrack2)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_TRACK_3, Conversions.volts(logRecord.AcceleratorTrack3)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_SUPPLY,  Conversions.volts(logRecord.AcceleratorSupply)));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.AMBIENT_PRESSURE) && get_pid(Requests.RequestPidEnum.AMBIENT_PRESSURE)) {
                        logRecord.AmbientPressure = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        ambientKnown = true;
                        // logRecord.AmbientPressureRaw = Util.bytes2short(mReadBuffer[5], mReadBuffer[6]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AMBIENT_PRESSURE, Conversions.pressureKpa(logRecord.AmbientPressure)));
                        //EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AMBIENT_PRESSURE2, logRecord.AmbientPressureRaw / 100.0));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.MAP_MAF) && get_pid(Requests.RequestPidEnum.MAP_MAF)) {
                        logRecord.ManifoldAirPressure = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        logRecord.ManifoldAirFlow = Util.bytes2short(mReadBuffer[7], mReadBuffer[8]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.MANIFOLD_AIR_PRESSURE, Conversions.pressureKpa(logRecord.ManifoldAirPressure)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AIR_FLOW, Conversions.airFlowKgH(logRecord.ManifoldAirFlow)));
                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.POWER_BALANCE) && get_pid(Requests.RequestPidEnum.POWER_BALANCE)) {
                        logRecord.PowerBalance1      = Util.bytes2short(mReadBuffer[ 3], mReadBuffer[ 4]);
                        logRecord.PowerBalance2      = Util.bytes2short(mReadBuffer[ 5], mReadBuffer[ 6]);
                        logRecord.PowerBalance3      = Util.bytes2short(mReadBuffer[ 7], mReadBuffer[ 8]);
                        logRecord.PowerBalance4      = Util.bytes2short(mReadBuffer[ 9], mReadBuffer[10]);
                        logRecord.PowerBalance5      = Util.bytes2short(mReadBuffer[11], mReadBuffer[12]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_1, Conversions.powerBalance(logRecord.PowerBalance1)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_2, Conversions.powerBalance(logRecord.PowerBalance2)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_3, Conversions.powerBalance(logRecord.PowerBalance3)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_4, Conversions.powerBalance(logRecord.PowerBalance4)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_5, Conversions.powerBalance(logRecord.PowerBalance5)));


                        pid_count++;
                    }
                    if (mDashboardRunning && due.contains(Requests.RequestPidEnum.GET_FUEL_DEMAND) && get_pid(Requests.RequestPidEnum.GET_FUEL_DEMAND)) {
                        logRecord.DriverDemand      = Util.bytes2short(mReadBuffer[ 3], mReadBuffer[ 4]);
                        logRecord.MafAirMass        = Util.bytes2short(mReadBuffer[ 5], mReadBuffer[ 6]);
                        logRecord.MapAirMass        = Util.bytes2short(mReadBuffer[ 7], mReadBuffer[ 8]);
                        logRecord.InjectionQuantity = Util.bytes2short(mReadBuffer[ 9], mReadBuffer[10]);
                        logRecord.AfRatio           = Util.bytes2short(mReadBuffer[11], mReadBuffer[12]);
                        // Ekaitza_Itzali labels word 6 as the smoke map and word 7 as the
                        // torque limiter, and hairyone's own logs agree: at warm idle the
                        // first is the lower of the two, as a smoke limit must be.
                        logRecord.SmokeLimit        = Util.bytes2short(mReadBuffer[13], mReadBuffer[14]);
                        logRecord.TorqueLimit       = Util.bytes2short(mReadBuffer[15], mReadBuffer[16]);
                        logRecord.IdleDemand        = Util.bytes2short(mReadBuffer[17], mReadBuffer[18]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.DRIVER_DEMAND,      Conversions.fuelQuantityMg(logRecord.DriverDemand)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.MAF_AIR_MASS,       Conversions.airMassMg(logRecord.MafAirMass)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.MAP_AIR_MASS,       Conversions.airMassMg(logRecord.MapAirMass)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.INJECTION_QUANTITY, Conversions.fuelQuantityMg(logRecord.InjectionQuantity)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AF_RATIO,           Conversions.airFuelRatio(logRecord.AfRatio)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.TORQUE_LIMIT,       Conversions.fuelQuantityMg(logRecord.TorqueLimit)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.SMOKE_LIMIT,        Conversions.fuelQuantityMg(logRecord.SmokeLimit)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.IDLE_DEMAND,        logRecord.IdleDemand        / 100.0));
                        pid_count++;
                    }

                    csv_filehandle.println(String.format("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                            simpleDateFormat.format(new Date()),
                            logRecord.EngineRpm, logRecord.BatteryVoltage, logRecord.VehicleSpeed,
                            logRecord.CoolantTemperature, logRecord.ExternalTemperature, logRecord.InletTemperature, logRecord.FuelTemperature,
                            logRecord.AcceleratorTrack1, logRecord.AcceleratorTrack2, logRecord.AcceleratorTrack3, logRecord.AcceleratorSupply,
                            logRecord.AmbientPressure, logRecord.ManifoldAirPressure, logRecord.ManifoldAirFlow,
                            logRecord.DriverDemand, logRecord.MafAirMass, logRecord.MapAirMass, logRecord.InjectionQuantity,
                            logRecord.AfRatio, logRecord.TorqueLimit, logRecord.SmokeLimit, logRecord.IdleDemand
                    ));
                    csv_filehandle.flush();
                    // Nothing answered while we were still meant to read: the
                    // ignition is off or the cable is gone.
                    if (pid_count == 0 && mDashboardRunning) {
                        Util.log_msg("Le calculateur ne répond plus : lecture arrêtée.");
                        lost = true;
                        break;
                    }
                }
            }
            if (lost) bus.execute(this::closeLink);
        } catch (Exception ex) {
            log.error("dashboard", ex);
            if (mDashboardRunning) {
                Util.log_msg("Lecture interrompue : " + ex.getMessage());
                bus.execute(this::closeLink);
            }
        } finally {
            mDashboardRunning = false;
        }
    }


    // worked on and shown without a vehicle attached.
    private double simulationPhase = 0;

    private void simulate() {
        simulationPhase += 0.04;
        double wave = (Math.sin(simulationPhase) + 1) / 2;          // 0..1
        double load = Math.pow(wave, 2);

        // Speed follows the load; the engine speed follows the speed through
        // the R380's gears, so the gear indicator has something to learn.
        double speed = load * 110;
        double[] ratios = {3.692, 2.132, 1.397, 1.000, 0.770};
        double[] upTo = {14, 30, 50, 72, 999};
        int g = 0;
        while (speed > upTo[g]) g++;
        double rpm = Math.max(760, speed * 23.5 * ratios[g] / 0.770 * (1 + 0.004 * Math.sin(simulationPhase * 7)));
        post(DashboardEvent.DATA_TYPE.RPM, Math.min(rpm, 4300));
        post(DashboardEvent.DATA_TYPE.VEHICLE_SPEED, realSpeed(speed));
        post(DashboardEvent.DATA_TYPE.BATTERY_VOLTAGE, 13.9 + wave * 0.7);
        // The demo starts cold and warms up, so the warm-up strip can be seen.
        double warmup = Math.min(1.0, simulationPhase / 9.0);
        post(DashboardEvent.DATA_TYPE.COOLANT_TEMP, 22 + warmup * 64 + load * 6);
        post(DashboardEvent.DATA_TYPE.FUEL_TEMP, 62 + wave * 4);
        post(DashboardEvent.DATA_TYPE.INLET_TEMP, 27 + load * 35);
        post(DashboardEvent.DATA_TYPE.EXTERNAL_TEMP, 18 + wave * 2);
        post(DashboardEvent.DATA_TYPE.AMBIENT_PRESSURE, 99.2);
        post(DashboardEvent.DATA_TYPE.MANIFOLD_AIR_PRESSURE, 99.2 + load * 115);
        post(DashboardEvent.DATA_TYPE.AIR_FLOW, 57 + load * 620);
        post(DashboardEvent.DATA_TYPE.ACC_TRACK_1, 0.7 + load * 3.6);
        post(DashboardEvent.DATA_TYPE.ACC_TRACK_2, 0.7 + load * 3.6);
        post(DashboardEvent.DATA_TYPE.ACC_TRACK_3, 0.7 + load * 3.6);
        post(DashboardEvent.DATA_TYPE.ACC_SUPPLY, load * 10);
        // The smoke limit follows the air, which lags behind the pedal while
        // the turbo spools up: on the way up the fuel is briefly held back,
        // as on the vehicle. The ECU injects the lowest of the three.
        double lagWave = (Math.sin(simulationPhase - 0.45) + 1) / 2;
        double demand = load * 48;
        double smoke = 26 + lagWave * lagWave * 24;
        double torque = 40 + load * 12;
        // Same order as the ECU's fuelling frame: the smoke limit comes last.
        post(DashboardEvent.DATA_TYPE.DRIVER_DEMAND, demand);
        post(DashboardEvent.DATA_TYPE.INJECTION_QUANTITY, Math.max(5, Math.min(demand, Math.min(smoke, torque))));
        post(DashboardEvent.DATA_TYPE.TORQUE_LIMIT, torque);
        post(DashboardEvent.DATA_TYPE.SMOKE_LIMIT, smoke);
        post(DashboardEvent.DATA_TYPE.IDLE_DEMAND, -4 + wave * 8);
        post(DashboardEvent.DATA_TYPE.MAF_AIR_MASS, 410 + load * 300);
        // The manifold estimate counts the EGR gas the air meter never sees:
        // well above it at light load, a few percent above once the EGR shuts.
        post(DashboardEvent.DATA_TYPE.MAP_AIR_MASS, (410 + load * 300) * (1.03 + 1.2 * Math.max(0, 0.3 - load)));
        post(DashboardEvent.DATA_TYPE.AF_RATIO, 14.2 + (1 - load) * 7);
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_1, 2 + Math.sin(simulationPhase * 1.3));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_2, 1 + Math.sin(simulationPhase * 1.7));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_3, 1 + Math.sin(simulationPhase * 2.1));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_4, Math.sin(simulationPhase * 0.9));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_5, Math.sin(simulationPhase * 1.1));
    }

    /**
     * The speed every screen sees, already corrected for the tyres, so the
     * speed, the L/100 km and the distances all agree with the road.
     */
    private double realSpeed(double ecuKmh) {
        return Conversions.realSpeed(ecuKmh, Settings.get(context, "speed_read"), Settings.get(context, "speed_real"));
    }

    private void post(DashboardEvent.DATA_TYPE type, double value) {
        EventBus.getDefault().post(new DashboardEvent(type, value));
    }

    // Identity is read once per connection; it never changes while the key is on.
    private void readVehicleInfo() {
        if (Consts.DEBUG_UI) {
            EventBus.getDefault().postSticky(new VehicleInfoEvent(
                    "SALLDVBP7YA123456", "NNN500010", "MSB101090"));
            return;
        }
        String vin = readIdentity(Requests.RequestPidEnum.GET_VIN);
        String ecu = readIdentity(Requests.RequestPidEnum.GET_ECUTYPE);
        String map = readIdentity(Requests.RequestPidEnum.GET_MAPVARIANT);
        EventBus.getDefault().postSticky(new VehicleInfoEvent(vin, ecu, map));
    }

    private String readIdentity(Requests.RequestPidEnum pid) {
        if (!get_pid(pid)) return "";
        int len = mRequests.request.get(pid).response_len & 0xFF;
        return Conversions.identityText(mReadBuffer, 3, Math.max(3, len - 1));
    }

    private int control_transfer(int request, int value) {
        return control_transfer(request, value, 0);
    }

    private int control_transfer(int request, int value, int index) {
        int rc = mUsbDeviceConnection.controlTransfer(FTDI.REQ_OUT, request, value, index, null, 0x00, FTDI.WRITE_TIMEOUT);
        if (rc < 0) {
            log.error(String.format("request=%d, value=%d, index=%d, rc=%d", request, value, index, rc));
        }
        return rc;
    }

    private int bulk_transfer_read(byte[] data, int len) {
        int rc = mUsbDeviceConnection.bulkTransfer(mUsbEndpointIn, data, len, FTDI.READ_TIMEOUT);
        if (rc < 0) {
           //log.error(String.format("data=%s, rc=%d", Util.byte_array_to_hex(data, len), rc));
        }
        return rc;
    }

    private int bulk_transfer_write(byte[] data, int len) {
        int rc = mUsbDeviceConnection.bulkTransfer(mUsbEndpointOut, data, len, FTDI.WRITE_TIMEOUT);
        if (rc < 0) {
            //log.error(String.format("data=%s, rc=%d", Util.byte_array_to_hex(data, len), rc));
        }
        return rc;
    }

    /*
    uint16_t generateKey(uint16_t seed) {
        uint16_t tmp = 0;
        byte tap = 0;
        byte count = ((seed >> 0xC & 0x8) | (seed >> 0x5 & 0x4) | (seed >> 0x3 & 0x2) | (seed & 0x1)) + 1;
        for (byte idx = 0; idx < count; idx++) {
            tap = ((seed >> 1 ) ^ (seed >> 2 ) ^ (seed >> 8 ) ^ (seed >> 9 )) & 1;
            tmp = ((seed >> 1) | (tap << 0xF));
            if ((seed >> 0x3 & 1) && (seed >> 0xD & 1)) {
                seed = tmp & ~1;
            } else {
                seed = tmp | 1;
            }
        }
        return seed;
    }
    */

    private int generate_key(int seedin) {
        int seed = seedin & 0xFFFF;
        log.debug(String.format("seed=%04X, seed_hi=%02X, seed_lo=%02X", seed & 0xFFFF, (seed & 0xFFFF) >>> 8, seed & 0xFF));
        int count = (( (seed >>> 0xC & 0x8) | (seed >>> 0x5 & 0x4) | (seed >>> 0x3 & 0x2) | (seed & 0x1) ) + 1) & 0xFF;
        log.debug(String.format("count=%d", count));
        for (int idx = 0; idx < count; idx++) {
            int tap = (( (seed >>> 1) ^ (seed >>> 2) ^ (seed >>> 8) ^ (seed >>> 9) ) & 1) & 0xFF;
            int tmp = (seed >>> 1) | (tap << 0xF);
            if ( (seed >>> 0x3 & 1) == 1 && (seed >>> 0xD & 1) == 1 ) {
                seed = tmp & ~1;
            } else {
                seed = tmp | 1;
            }
            log.debug(String.format("tap=%d, tmp=%05d, a=%d, b=%d, seed=%05d", tap, tmp, seed >>> 0x03 & 1, seed >>> 0x0d & 1, seed));
        }
        log.debug(String.format("key_hi=%02X, key_lo=%02X", (seed & 0xFFFF) >>> 8, seed & 0xFF));
        return seed;
    }

    private boolean get_pid(Requests.RequestPidEnum pid) {
        boolean result = false;
        if (pid != Requests.RequestPidEnum.INIT_FRAME) {
            try {
                Thread.sleep(Consts.SEND_REQUEST_DELAY);
            } catch (Exception ex) {
                Thread.currentThread().interrupt();
            }
        }
        sendRequest(pid);
        int len = readResponse(pid);
        if (len > 1) {
            byte cs1 = mReadBuffer[len - 1];
            byte cs2 = checksum(mReadBuffer, len - 1);
            if (cs1 == cs2) {
                if (mReadBuffer[1] != 0x7F) {
                    result = true;
                }
            }
        }
        return result;
    }

    private void sendRequest(Requests.RequestPidEnum pid) {
        int len = mRequests.request.get(pid).request.length;
        byte[] data = mRequests.request.get(pid).request;
        data[len - 1] = checksum(data, len - 1);

         String name = mRequests.request.get(pid).name;
         String msg = String.format(">> %s", Util.byte_array_to_hex(data, len));
         //if (mFastInitCompleted) {
         //    log.debug(name);
         //    log.debug(msg);
         //} else {
         //    Util.log_msg(name);
         //    Util.log_msg(msg);
         //}

        bulk_transfer_write(data, len);
    }

    private byte checksum(byte[] data, int len) {
        byte crc = 0;
        for (int i = 0; i < len; i++) {
            crc = (byte) (crc + data[i]);
        }
        return crc;
    }

    private int readResponse(Requests.RequestPidEnum pid) {

        int request_len = mRequests.request.get(pid).request.length;
        int response_len = mRequests.request.get(pid).response_len;
        int max_packet_size = mUsbEndpointIn.getMaxPacketSize();
        int modem_status_len = 2;

        final int modem_status_bytes_len = (
            (int) Math.ceil(
                ((double) request_len + response_len) / ((double) (max_packet_size - modem_status_len)))
        ) * modem_status_len;

        int expected_len = request_len + response_len + modem_status_bytes_len;
        byte[] buf = new byte[expected_len];

        long start_time = System.currentTimeMillis();
        int offset = 0;
        while (true) {
            // Keep reading until we get the expected number of bytes or we timeout
            int bytes_read = bulk_transfer_read(buf, expected_len);

            if (Consts.LOG_EVERY_READ && bytes_read > 0) {
                log.debug(String.format("<< %s", Util.byte_array_to_hex(buf, bytes_read)));
            }

            boolean line_status_error = (buf[1] & FTDI.ERROR_MASK) > 0;
            if (Consts.LOG_EVERY_MODEM_STATUS || line_status_error) {
                log.debug(String.format("modem_status=%s, line_status=%s, line_status_error=%b",
                    Util.integer_to_binary(buf[0]),
                    Util.integer_to_binary(buf[1]),
                    line_status_error
                ));
            }

            // There should be at least the two status bytes from the FTDI chip
            if (buf.length < 2) {
                log.error("malformed mReadBuffer less than 2 bytes read");
                return 0;
            }

            // Remove the modem status bytes from the start of each packet in the buffer
            int data_len = filter_modem_status_bytes(buf, buf, Math.min(buf.length, bytes_read), max_packet_size, modem_status_len);

            if (data_len > 0) {
                System.arraycopy(buf, 0, mReadBuffer, offset, data_len);
                offset += data_len;
            }

            if (pid == Requests.RequestPidEnum.KEY_RETURN && offset == 10 && mReadBuffer[7] == 0x67) {
                // The only instance where a negative response is longer than a positive response
                // >> 04 27 02 F7 B9 DD | 02 67 02 6B       Positive response
                // >> 04 27 02 F7 B9 DD | 03 7F 27 35 DE    Negative response
                break;
            }

            if (offset >= request_len + response_len || System.currentTimeMillis() - start_time > Consts.READ_RESPONSE_TIMEOUT ) {
                if (System.currentTimeMillis() - start_time > FTDI.READ_TIMEOUT) {
                    log.debug("timeout in read_response()");
                }
                break;
            }
        }

        // Remove the echoed request
        if (offset >= request_len) {
            System.arraycopy(mReadBuffer, request_len, mReadBuffer, 0, offset - request_len);
            String msg = String.format("%s >> %s << %s",
                    mRequests.request.get(pid).name,
                    Util.byte_array_to_hex(mRequests.request.get(pid).request, request_len),
                    Util.byte_array_to_hex(mReadBuffer, offset - request_len)
            );
            log.debug(msg);
            if (Consts.DISPLAY_EVERY_REQUEST) {
                Util.log_msg(msg);
            }
            return offset - request_len;
        } else {
            // not enough bytes for a plausible response
            return 0;
        }
    }

    private int filter_modem_status_bytes(byte[] src, byte[] dest, int src_len, int max_packet_len, int modem_status_len) {
        int src_offset = 0;
        int dst_offset = 0;
        src_offset = src_offset + modem_status_len;
        while (src_offset < src_len) {
            int len = ((src_len - src_offset) >= (max_packet_len - modem_status_len)) ?
                    (max_packet_len - modem_status_len) :
                    (src_len - src_offset);
            System.arraycopy(src, src_offset, dest, dst_offset, len);
            src_offset = src_offset + len + modem_status_len;
            dst_offset = dst_offset + len;
        }
        return dst_offset;
    }

}
