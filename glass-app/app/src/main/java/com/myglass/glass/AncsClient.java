/*
 * Copyright (C) 2024 The MyGlass Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.myglass.glass;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * A minimal Apple Notification Center Service (ANCS) client.
 *
 * <p>Glass acts as the BLE accessory: it discovers the iPhone, subscribes to the ANCS
 * Notification Source, and fetches notification attributes from the Data Source via
 * the Control Point. It also reads the phone's clock from the standard Current Time
 * Service (or the MyGlass custom service) so the device clock can be updated.
 *
 * <p>Written against the Android 4.4 (API 19) BLE stack that Google Glass ships with,
 * so only pre-API-21 APIs are used. Discovery deliberately parses the raw
 * advertisement (scan record) because {@link BluetoothDevice#getName()} is often empty
 * on this stack.
 */
public class AncsClient {

    private static final String TAG = "AncsClient";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface Listener {
        void onAncsConnected(String deviceName);

        void onAncsDisconnected();

        void onAncsNotification(AncsNotification notification);

        void onCompanionTime(long epochMillis);

        void onCompanionTimeZone(String zoneId, Integer offsetMinutes);

        void onCompanionCommand(String payload);
    }

    /*
     * ANCS attributes are fetched in two phases because the control-point command must
     * fit a single 20-byte BLE write (Android 4.4's ATT MTU cap).
     */
    private static final int[] MAIN_ATTRIBUTES = {
            Ancs.ATTR_APP_IDENTIFIER,
            Ancs.ATTR_TITLE,
            Ancs.ATTR_SUBTITLE,
            Ancs.ATTR_MESSAGE,
            Ancs.ATTR_DATE,
    };
    private static final int[] ACTION_ATTRIBUTES = {
            Ancs.ATTR_POSITIVE_ACTION_LABEL,
            Ancs.ATTR_NEGATIVE_ACTION_LABEL,
    };

    /* Bluetooth SIG "Current Time Service" 16-bit UUID. */
    private static final int CTS_SERVICE_16 = 0x1805;
    /* The MyGlass custom service uses the SIG base UUID, so it may appear as 0xA100. */
    private static final int MYGLASS_SERVICE_16 = 0xA100;
    /* Apple's Bluetooth SIG company identifier. */
    private static final int APPLE_COMPANY_ID = 0x004C;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic controlPoint;
    private BluetoothGattCharacteristic dataSource;
    private BluetoothGattCharacteristic notificationSource;
    private BluetoothGattCharacteristic commandChar;
    private BluetoothGattCharacteristic uplink;

    /* Sequential GATT operation queue: Android allows only one operation at a time. */
    private final Queue<GattOp> opQueue = new ArrayDeque<GattOp>();
    private boolean opBusy;

    /* Candidate devices discovered while scanning. */
    private final List<BluetoothDevice> candidates = new ArrayList<BluetoothDevice>();
    private final List<BluetoothDevice> appleAdvertisers = new ArrayList<BluetoothDevice>();
    private final Map<String, Integer> rssiByAddress = new java.util.HashMap<String, Integer>();
    private final Set<String> loggedNames = new HashSet<String>();
    private final Set<String> loggedRaw = new HashSet<String>();
    private int candidateIndex;
    private boolean scanning;
    private boolean started;
    private int pendingSubscriptions;
    private boolean ready;
    private final Set<Long> subscribed = new HashSet<Long>();
    private final Set<String> bondRequested = new HashSet<String>();
    private boolean receiverRegistered;
    private long lastProgressMs;
    private long lastDownlinkMs;
    private int stuckCount;
    private boolean keepalivePending;

    /**
     * iOS only delivers ANCS once the link is bonded/encrypted, and the bond often
     * completes a moment after the first (rejected) subscription attempt. Retry once the
     * link is authorized instead of waiting for a disconnect.
     */
    private final Runnable resubscribeRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && !ready) {
                Log.i(TAG, "Retrying ANCS subscription after authorization");
                retrySubscription();
            }
        }
    };

    /**
     * Used when a GATT operation fails at the link level (status 132): the link is no
     * longer usable, so tear it down and rediscover instead of retrying in place.
     */
    private final Runnable reconnectNowRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && !ready) {
                Log.i(TAG, "Link error; reconnecting from scratch");
                reconnect();
            }
        }
    };

    /**
     * Watchdog that keeps Glass trying to reach the phone: if the link makes no progress
     * for a while (a dropped connection, a stale bond, or a wedged scan), it tears the
     * GATT down and rediscovers. This avoids needing to restart the app by hand.
     */
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (started) {
                long now = System.currentTimeMillis();
                if (ready && now - lastDownlinkMs > 60000L) {
                    // The phone stopped sending its heartbeat: the link to the phone app
                    // is stale (e.g. the app restarted), even though it looks connected.
                    Log.i(TAG, "Watchdog: heartbeat lost, treating link as stale");
                    lastDownlinkMs = now;
                    ready = false;
                    closeGatt();
                    reconnect();
                } else if (!ready && now - lastProgressMs > 30000L) {
                    stuckCount++;
                    Log.i(TAG, "Watchdog: no progress for " + ((now - lastProgressMs) / 1000)
                            + "s (attempt " + stuckCount + ")");
                    lastProgressMs = now;
                    if (stuckCount >= 4) {
                        stuckCount = 0;
                        cycleBluetooth();
                    } else {
                        reconnect();
                    }
                } else if (ready) {
                    stuckCount = 0;
                }
                handler.postDelayed(watchdogRunnable, 10000L);
            }
        }
    };

    /** Last resort: toggle Bluetooth to clear a wedged controller/stack. */
    @SuppressWarnings("deprecation")
    private void cycleBluetooth() {
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return;
            }
            Log.i(TAG, "Watchdog: cycling Bluetooth to clear a wedged stack");
            if (adapter.isEnabled()) {
                adapter.disable();
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (started && adapter != null && !adapter.isEnabled()) {
                        adapter.enable();
                    }
                    markProgress();
                }
            }, 3000L);
        } catch (Exception e) {
            Log.w(TAG, "cycleBluetooth failed: " + e.getMessage());
        }
    }

    private void markProgress() {
        lastProgressMs = System.currentTimeMillis();
    }

    /**
     * Keepalive: a stale GATT link (e.g. after the phone app restarts) can look
     * "connected" forever. Periodically read the clock characteristic; if it does not
     * answer in time, the link is dead, so tear it down and rediscover.
     */
    private final Runnable keepaliveTimeout = new Runnable() {
        @Override
        public void run() {
            if (keepalivePending) {
                Log.w(TAG, "Keepalive timed out; link is dead");
                keepalivePending = false;
                ready = false;
                closeGatt();
                if (started) {
                    reconnect();
                }
            }
        }
    };

    private final Runnable keepaliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (started && ready && gatt != null && !keepalivePending) {
                BluetoothGattCharacteristic epoch = findCharacteristic(gatt,
                        Constants.MYGLASS_SERVICE, Constants.MYGLASS_EPOCH_MILLIS);
                if (epoch != null) {
                    keepalivePending = true;
                    enqueue(new GattOp(GattOp.READ, epoch, null, null));
                    handler.removeCallbacks(keepaliveTimeout);
                    handler.postDelayed(keepaliveTimeout, 10000L);
                }
            }
            if (started) {
                handler.postDelayed(keepaliveRunnable, 25000L);
            }
        }
    };

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null
                    || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR);
            if (device == null || state != BluetoothDevice.BOND_BONDED) {
                return;
            }
            Log.i(TAG, "Bond completed with " + safeName(device) + "; re-subscribing to ANCS");
            scheduleResubscribe();
        }
    };

    private void scheduleResubscribe() {
        if (ready) {
            // Already subscribed; a late bond event for the iPhone's rotating address is
            // not a reason to tear the link down.
            return;
        }
        handler.removeCallbacks(resubscribeRunnable);
        handler.postDelayed(resubscribeRunnable, 2500L);
    }

    private void scheduleReconnectNow() {
        if (ready) {
            return;
        }
        handler.removeCallbacks(reconnectNowRunnable);
        handler.postDelayed(reconnectNowRunnable, 1500L);
    }

    private void registerBondReceiver() {
        if (receiverRegistered) {
            return;
        }
        context.registerReceiver(bondReceiver,
                new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        receiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(bondReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    /* Data-source reassembly for the notification currently being fetched. */
    private final ByteArrayOutputStream dataBuffer = new ByteArrayOutputStream();
    private int[] currentPhaseAttributes;
    private int currentPhase;

    /* Reassembly buffer for the framed (chunked) MyGlass phone -> Glass command channel. */
    private final ByteArrayOutputStream commandBuffer = new ByteArrayOutputStream();
    private final Set<Integer> receivedAttributes = new HashSet<Integer>();
    private final Map<Long, AncsNotification> notifications = new LinkedHashMap<Long, AncsNotification>();
    private final Queue<Long> requestQueue = new ArrayDeque<Long>();
    private long activeRequestUid = -1;

    private String pendingDeviceName = "";
    private BluetoothDevice ancsDevice;

    private final Runnable bondRunnable = new Runnable() {
        @Override
        public void run() {
            if (ancsDevice != null) {
                ensureBond(ancsDevice);
            }
        }
    };

    private final Runnable dispatchData = new Runnable() {
        @Override
        public void run() {
            advancePhase();
        }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "Connect timeout");
            closeGatt();
            if (ancsDevice != null) {
                retryAncsDeviceSoon();
            } else {
                connectToNextCandidate();
            }
        }
    };

    private final Runnable discoveryTimeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "Service discovery timeout on " + pendingDeviceName);
            closeGatt();
            if (ancsDevice != null) {
                retryAncsDeviceSoon();
            } else {
                connectToNextCandidate();
            }
        }
    };

    private void retryAncsDeviceSoon() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (started) {
                    retryAncsDevice();
                }
            }
        }, 3000L);
    }

    private void retryAncsDevice() {
        if (!started || ancsDevice == null) {
            return;
        }
        Log.i(TAG, "Reconnecting to iPhone " + safeName(ancsDevice));
        try {
            gatt = ancsDevice.connectGatt(context, false, gattCallback);
        } catch (Exception e) {
            Log.w(TAG, "Reconnect failed: " + e.getMessage());
            gatt = null;
        }
        if (gatt == null) {
            // Fall back to a fresh scan shortly.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (started) {
                        start();
                    }
                }
            }, 3000L);
            return;
        }
        handler.removeCallbacks(connectTimeout);
        handler.postDelayed(connectTimeout, Constants.CONNECT_TIMEOUT_MS);
    }

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            if (started && gatt == null) {
                start();
            }
        }
    };

    public AncsClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isConnected() {
        return gatt != null && ready;
    }

    /** Starts discovery/connection. Safe to call repeatedly. */
    public void start() {
        started = true;
        registerBondReceiver();
        markProgress();
        lastDownlinkMs = System.currentTimeMillis();
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(watchdogRunnable, 15000L);
        handler.removeCallbacks(keepaliveRunnable);
        handler.postDelayed(keepaliveRunnable, 25000L);
        if (gatt != null) {
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter unavailable or disabled");
            scheduleReconnect();
            return;
        }

        candidates.clear();
        candidateIndex = 0;
        appleAdvertisers.clear();
        rssiByAddress.clear();
        ancsDevice = null;

        // Rely on the BLE scan: the MyGlass Companion advertises the Current Time /
        // MyGlass services, which identifies the iPhone unambiguously. (Android's
        // getBondedDevices() returns Classic addresses for an iPhone, which cannot be
        // used for a healthy GATT/ANCS connection.)
        startScan();
    }

    public void stop() {
        started = false;
        unregisterBondReceiver();
        handler.removeCallbacks(watchdogRunnable);
        handler.removeCallbacks(keepaliveRunnable);
        handler.removeCallbacks(keepaliveTimeout);
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        candidates.clear();
        subscribed.clear();
        notifications.clear();
        requestQueue.clear();
        bondRequested.clear();
    }

    /** Forgets the current peer and re-runs discovery. */
    public void reconnect() {
        closeGatt();
        handler.removeCallbacksAndMessages(null);
        started = true;
        start();
    }

    // ------------------------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void startScan() {
        if (adapter == null) {
            scheduleReconnect();
            return;
        }
        if (scanning) {
            return;
        }
        scanning = true;
        listener.onAncsDisconnected();
        Log.i(TAG, "Scanning for the MyGlass Companion...");
        // Note: Android 4.4's startLeScan(UUID[]) filter does not match this device's
        // 16-bit advertised service UUIDs, so we scan unfiltered. The scan is duty-cycled
        // and we only connect to the companion, which keeps the Bluetooth stack healthy.
        boolean ok = adapter.startLeScan(leScanCallback);
        if (!ok) {
            Log.w(TAG, "startLeScan failed");
            scanning = false;
            scheduleReconnect();
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                onScanFinished();
            }
        }, Constants.SCAN_TIMEOUT_MS);
    }

    @SuppressWarnings("deprecation")
    private void stopScan() {
        if (scanning && adapter != null) {
            try {
                adapter.stopLeScan(leScanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                    if (device == null || !started) {
                        return;
                    }
                    if (contains(candidates, device)) {
                        return;
                    }
                    ScanInfo info = parseScanRecord(scanRecord);
                    String name = info.name != null ? info.name : safeName(device);
                    if (loggedRaw.add(device.getAddress())) {
                        Log.i(TAG, "ADV " + device.getAddress() + " rssi=" + rssi
                                + " apple=" + info.appleCompany
                                + " raw=" + toHex(scanRecord));
                    }
                    if (info.appleCompany && !contains(appleAdvertisers, device)) {
                        appleAdvertisers.add(device);
                    }
                    rssiByAddress.put(device.getAddress(), rssi);
                    boolean candidate = isAppleName(name) || info.hasCompanionService;
                    if (candidate) {
                        Log.i(TAG, "Found candidate name=\"" + name + "\" rssi=" + rssi
                                + (info.hasMyGlass ? " [MyGlass]" : "")
                                + (info.hasCts ? " [CTS]" : "")
                                + " addr=" + device.getAddress());
                        candidates.add(device);
                        // Connect as soon as we find the peer.
                        stopScan();
                        connectToNextCandidate();
                    } else if (name != null && loggedNames.add(name)) {
                        Log.i(TAG, "Seen BLE device: \"" + name + "\" rssi=" + rssi);
                    }
                }
            };

    private void onScanFinished() {
        if (gatt != null || !started) {
            return;
        }
        stopScan();
        if (!candidates.isEmpty()) {
            connectToNextCandidate();
        } else {
            Log.i(TAG, "No companion found in this scan; will retry");
            scheduleReconnect();
        }
    }

    private void connectToNextCandidate() {
        if (!started) {
            return;
        }
        if (candidateIndex >= candidates.size()) {
            Log.i(TAG, "All candidates exhausted, rescanning");
            candidates.clear();
            candidateIndex = 0;
            startScan();
            return;
        }
        BluetoothDevice device = candidates.get(candidateIndex++);
        pendingDeviceName = safeName(device);
        Log.i(TAG, "Connecting to " + pendingDeviceName + " [" + device.getAddress() + "]");
        try {
            gatt = device.connectGatt(context, false, gattCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "connectGatt failed: " + e.getMessage());
            gatt = null;
            connectToNextCandidate();
            return;
        }
        if (gatt == null) {
            connectToNextCandidate();
            return;
        }
        handler.removeCallbacks(connectTimeout);
        handler.postDelayed(connectTimeout, Constants.CONNECT_TIMEOUT_MS);
    }

    // ------------------------------------------------------------------------------------
    // GATT callback
    // ------------------------------------------------------------------------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to " + pendingDeviceName + ", discovering services");
                markProgress();
                handler.removeCallbacks(connectTimeout);
                // iOS peripherals need a moment after the link comes up before they answer
                // service discovery; firing discoverServices() immediately on this Android
                // 4.4 stack makes it never call back. Delay it slightly.
                final BluetoothGatt connected = g;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = connected.discoverServices();
                        Log.i(TAG, "discoverServices() returned " + ok
                                + " for " + pendingDeviceName);
                    }
                }, 900L);
                handler.removeCallbacks(discoveryTimeout);
                handler.postDelayed(discoveryTimeout, 12000L);
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from " + pendingDeviceName);
                handler.removeCallbacks(connectTimeout);
                boolean wasReady = ready;
                closeGatt();
                if (wasReady) {
                    listener.onAncsDisconnected();
                }
                if (started && ancsDevice != null) {
                    // Stay with the iPhone across the pairing/bond transition.
                    retryAncsDevice();
                } else if (started) {
                    connectToNextCandidate();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.removeCallbacks(discoveryTimeout);
            markProgress();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: " + status);
                closeGatt();
                connectToNextCandidate();
                return;
            }

            BluetoothGattService ancs = g.getService(Constants.ANCS_SERVICE);
            boolean isCompanion = g.getService(Constants.CTS_SERVICE) != null
                    || g.getService(Constants.MYGLASS_SERVICE) != null;

            if (ancs == null) {
                if (isCompanion) {
                    // The MyGlass Companion is here, but iOS has not exposed ANCS yet.
                    // Bond, read the clock, then reconnect so ANCS becomes available.
                    Log.i(TAG, "Companion found without ANCS; bonding and retrying");
                    ensureBond(g.getDevice());
                    readCompanionClock(g);
                    closeGatt();
                    scheduleReconnect();
                } else {
                    Log.i(TAG, "No ANCS on " + pendingDeviceName + ", trying next");
                    closeGatt();
                    connectToNextCandidate();
                }
                return;
            }

            controlPoint = ancs.getCharacteristic(Constants.ANCS_CONTROL_POINT);
            dataSource = ancs.getCharacteristic(Constants.ANCS_DATA_SOURCE);
            notificationSource = ancs.getCharacteristic(Constants.ANCS_NOTIFICATION_SOURCE);

            if (controlPoint == null || dataSource == null || notificationSource == null) {
                Log.w(TAG, "ANCS service is incomplete");
                closeGatt();
                connectToNextCandidate();
                return;
            }

            Log.i(TAG, "ANCS found on " + pendingDeviceName + "; subscribing");

            ancsDevice = g.getDevice();
            pendingSubscriptions = 0;
            enableNotifications(g, notificationSource);
            enableNotifications(g, dataSource);
            commandChar = findCharacteristic(g, Constants.MYGLASS_SERVICE,
                    Constants.MYGLASS_COMMAND);
            if (commandChar != null) {
                enableNotifications(g, commandChar);
            }
            uplink = findCharacteristic(g, Constants.MYGLASS_SERVICE, Constants.MYGLASS_UPLINK);
            readCompanionClock(g);

            // ANCS requires an authorized (bonded) link. Ask to pair a moment after
            // subscribing so the CCCD writes can complete first.
            if (g.getDevice().getBondState() != BluetoothDevice.BOND_BONDED) {
                handler.removeCallbacks(bondRunnable);
                handler.postDelayed(bondRunnable, 1500L);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Constants.ANCS_NOTIFICATION_SOURCE.equals(c.getUuid())) {
                handleNotificationSource(c.getValue());
            } else if (Constants.ANCS_DATA_SOURCE.equals(c.getUuid())) {
                handleDataSource(c.getValue());
            } else if (Constants.MYGLASS_COMMAND.equals(c.getUuid())) {
                lastDownlinkMs = System.currentTimeMillis();
                handleCommandChunk(c.getValue());
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.i(TAG, "read " + c.getUuid() + " status=" + status
                    + " len=" + (c.getValue() != null ? c.getValue().length : -1));
            keepalivePending = false;
            markProgress();
            opFinished();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (status == 132 && ancsDevice != null) {
                    scheduleReconnectNow();
                }
                return;
            }
            if (Constants.CTS_CURRENT_TIME.equals(c.getUuid())) {
                long epoch = parseCurrentTime(c.getValue());
                if (epoch > 0) {
                    listener.onCompanionTime(epoch);
                }
            } else if (Constants.MYGLASS_EPOCH_MILLIS.equals(c.getUuid())) {
                long epoch = parseEpochMillis(c.getValue());
                if (epoch > 0) {
                    listener.onCompanionTime(epoch);
                }
            } else if (Constants.MYGLASS_DEVICE_NAME.equals(c.getUuid())) {
                byte[] value = c.getValue();
                if (value != null && value.length > 0) {
                    listener.onAncsConnected(new String(value, UTF8).trim());
                }
            } else if (Constants.MYGLASS_TIMEZONE.equals(c.getUuid())) {
                byte[] value = c.getValue();
                if (value != null && value.length > 0) {
                    listener.onCompanionTimeZone(new String(value, UTF8).trim(), null);
                }
            } else if (Constants.CTS_LOCAL_TIME_INFO.equals(c.getUuid())) {
                byte[] value = c.getValue();
                if (value != null && value.length >= 1) {
                    Log.i(TAG, "CTS local time info raw=" + toHex(value));
                    // Byte 0: standard time-zone offset (15-min units, signed).
                    // Byte 1: DST offset (15-min units). Current offset is their sum.
                    int standard = value[0];
                    int dst = value.length >= 2 ? (value[1] & 0xFF) : 0;
                    listener.onCompanionTimeZone(null, (standard + dst) * 15);
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Constants.MYGLASS_UPLINK.equals(c.getUuid())) {
                Log.i(TAG, "Uplink write status=" + status);
            }
            opFinished();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            opFinished();
            markProgress();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed: " + status);
                // 5 = insufficient authentication, 15 = insufficient encryption.
                if ((status == 5 || status == 15) && ancsDevice != null) {
                    ensureBond(ancsDevice);
                    // Retry on this link: the stack may still be encrypting it.
                    scheduleResubscribe();
                } else if (ancsDevice != null) {
                    // Link-level error (e.g. 132): the link is unusable, so reconnect.
                    scheduleReconnectNow();
                }
                return;
            }
            if (d.getCharacteristic() != null
                    && Constants.ANCS_NOTIFICATION_SOURCE.equals(d.getCharacteristic().getUuid())) {
                subscribed.add(d.getCharacteristic().getUuid().getLeastSignificantBits());
            }
            pendingSubscriptions--;
            if (pendingSubscriptions <= 0) {
                pendingSubscriptions = 0;
                if (!ready) {
                    ready = true;
                    Log.i(TAG, "Subscribed to ANCS on " + pendingDeviceName);
                    listener.onAncsConnected(pendingDeviceName);
                }
            }
        }
    };

    private void readCompanionClock(BluetoothGatt g) {
        BluetoothGattCharacteristic cts = findCharacteristic(g, Constants.CTS_SERVICE,
                Constants.CTS_CURRENT_TIME);
        if (cts != null) {
            enqueue(new GattOp(GattOp.READ, cts, null, null));
        }
        BluetoothGattCharacteristic localTime = findCharacteristic(g, Constants.CTS_SERVICE,
                Constants.CTS_LOCAL_TIME_INFO);
        if (localTime != null) {
            enqueue(new GattOp(GattOp.READ, localTime, null, null));
        }
        BluetoothGattCharacteristic zone = findCharacteristic(g, Constants.MYGLASS_SERVICE,
                Constants.MYGLASS_TIMEZONE);
        if (zone != null) {
            enqueue(new GattOp(GattOp.READ, zone, null, null));
        }
        BluetoothGattCharacteristic epoch = findCharacteristic(g, Constants.MYGLASS_SERVICE,
                Constants.MYGLASS_EPOCH_MILLIS);
        if (epoch != null) {
            enqueue(new GattOp(GattOp.READ, epoch, null, null));
        }
        BluetoothGattCharacteristic name = findCharacteristic(g, Constants.MYGLASS_SERVICE,
                Constants.MYGLASS_DEVICE_NAME);
        if (name != null) {
            enqueue(new GattOp(GattOp.READ, name, null, null));
        }
    }

    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
        g.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(Constants.CCCD);
        if (descriptor == null) {
            Log.w(TAG, "Missing CCCD for " + characteristic.getUuid());
            return;
        }
        pendingSubscriptions++;
        enqueue(new GattOp(GattOp.DESCRIPTOR, characteristic, descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        subscribed.add(characteristic.getUuid().getLeastSignificantBits());
    }

    // ------------------------------------------------------------------------------------
    // GATT operation queue
    // ------------------------------------------------------------------------------------

    private static final class GattOp {
        static final int CHARACTERISTIC = 0;
        static final int DESCRIPTOR = 1;
        static final int READ = 2;

        final int type;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] value;

        GattOp(int type, BluetoothGattCharacteristic characteristic,
               BluetoothGattDescriptor descriptor, byte[] value) {
            this.type = type;
            this.characteristic = characteristic;
            this.descriptor = descriptor;
            this.value = value;
        }
    }

    private void enqueue(GattOp op) {
        opQueue.add(op);
        runQueue();
    }

    private void runQueue() {
        if (opBusy || gatt == null || opQueue.isEmpty()) {
            return;
        }
        GattOp op = opQueue.peek();
        boolean queued;
        switch (op.type) {
            case GattOp.READ:
                queued = gatt.readCharacteristic(op.characteristic);
                break;
            case GattOp.DESCRIPTOR:
                op.descriptor.setValue(op.value);
                queued = gatt.writeDescriptor(op.descriptor);
                break;
            case GattOp.CHARACTERISTIC:
            default:
                op.characteristic.setValue(op.value);
                queued = gatt.writeCharacteristic(op.characteristic);
                break;
        }
        if (!queued) {
            // The stack refused the operation; drop it and continue.
            opQueue.poll();
            runQueue();
            return;
        }
        opBusy = true;
    }

    private void opFinished() {
        opBusy = false;
        opQueue.poll();
        runQueue();
    }

    // ------------------------------------------------------------------------------------
    // ANCS notification source / data source
    // ------------------------------------------------------------------------------------

    private void handleNotificationSource(byte[] value) {
        if (value == null || value.length < 8) {
            return;
        }
        AncsNotification notification = new AncsNotification();
        notification.eventId = value[0] & 0xFF;
        notification.eventFlags = value[1] & 0xFF;
        notification.categoryId = value[2] & 0xFF;
        notification.categoryCount = value[3] & 0xFF;
        notification.uid = Ancs.readUid(value, 4);

        if (notification.eventId == Ancs.EVENT_REMOVED) {
            notifications.remove(notification.uid);
            requestQueue.remove(notification.uid);
            listener.onAncsNotification(notification);
            return;
        }

        if (notification.isPreExisting()) {
            // Don't spam the user with the whole notification history at connect time.
            return;
        }

        notifications.put(notification.uid, notification);
        requestQueue.add(notification.uid);
        if (activeRequestUid == -1) {
            sendNextRequest();
        }
    }

    private void sendNextRequest() {
        Long uid = requestQueue.poll();
        if (uid == null) {
            activeRequestUid = -1;
            return;
        }
        activeRequestUid = uid;
        startPhase(uid, MAIN_ATTRIBUTES, 1);
    }

    /** Sends a GetNotificationAttributes control-point command for one phase. */
    private void startPhase(long uid, int[] attributes, int phase) {
        dataBuffer.reset();
        receivedAttributes.clear();
        currentPhaseAttributes = attributes;
        currentPhase = phase;
        byte[] command = Ancs.getNotificationAttributes(uid, attributes);
        enqueue(new GattOp(GattOp.CHARACTERISTIC, controlPoint, null, command));
    }

    private void handleDataSource(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        dataBuffer.write(value, 0, value.length);
        parseDataBuffer();

        handler.removeCallbacks(dispatchData);
        if (currentPhaseAttributes != null
                && receivedAttributes.size() >= currentPhaseAttributes.length) {
            advancePhase();
        } else {
            handler.postDelayed(dispatchData, Constants.DATA_SOURCE_COALESCE_MS);
        }
    }

    private void parseDataBuffer() {
        byte[] data = dataBuffer.toByteArray();
        if (data.length < 5) {
            return;
        }
        long uid = Ancs.readUid(data, 1);
        AncsNotification notification = notifications.get(uid);
        if (notification == null) {
            return;
        }
        int offset = 5;
        while (offset + 3 <= data.length) {
            int attributeId = data[offset] & 0xFF;
            int length = (data[offset + 1] & 0xFF) | ((data[offset + 2] & 0xFF) << 8);
            if (offset + 3 + length > data.length) {
                break; // Attribute not fully received yet.
            }
            String text = new String(data, offset + 3, length, UTF8);
            int end = text.indexOf('\0');
            if (end >= 0) {
                text = text.substring(0, end);
            }
            switch (attributeId) {
                case Ancs.ATTR_APP_IDENTIFIER:
                    notification.appIdentifier = text;
                    break;
                case Ancs.ATTR_TITLE:
                    notification.title = text;
                    break;
                case Ancs.ATTR_SUBTITLE:
                    notification.subtitle = text;
                    break;
                case Ancs.ATTR_MESSAGE:
                    notification.message = text;
                    break;
                case Ancs.ATTR_DATE:
                    notification.dateString = text;
                    notification.dateMillis = Ancs.parseDate(text);
                    break;
                case Ancs.ATTR_POSITIVE_ACTION_LABEL:
                    notification.positiveActionLabel = text;
                    break;
                case Ancs.ATTR_NEGATIVE_ACTION_LABEL:
                    notification.negativeActionLabel = text;
                    break;
                default:
                    break;
            }
            receivedAttributes.add(attributeId);
            offset += 3 + length;
        }
    }

    private void advancePhase() {
        handler.removeCallbacks(dispatchData);
        long uid = activeRequestUid;
        if (uid == -1) {
            return;
        }
        AncsNotification notification = notifications.get(uid);
        if (currentPhase == 1 && notification != null
                && (notification.hasPositiveAction() || notification.hasNegativeAction())) {
            // Fetch the action labels before dispatching.
            startPhase(uid, ACTION_ATTRIBUTES, 2);
            return;
        }
        activeRequestUid = -1;
        currentPhaseAttributes = null;
        currentPhase = 0;
        dataBuffer.reset();
        receivedAttributes.clear();
        if (notification != null) {
            listener.onAncsNotification(notification);
        }
        sendNextRequest();
    }

    /** Performs an ANCS notification action (positive/negative) on the phone. */
    public void performAction(long uid, int actionId) {
        if (controlPoint == null || gatt == null) {
            Log.w(TAG, "Cannot perform action: not connected");
            return;
        }
        Log.i(TAG, "Performing ANCS action " + actionId + " for uid " + uid);
        byte[] command = Ancs.performNotificationAction(uid, actionId);
        enqueue(new GattOp(GattOp.CHARACTERISTIC, controlPoint, null, command));
    }

    /**
     * Sends a JSON command to the phone over the uplink characteristic. The payload is
     * framed with a trailing newline and chunked into &lt;=20-byte writes (Android 4.4's
     * ATT MTU cap); the phone reassembles it.
     */
    public boolean sendUplink(String json) {
        if (uplink == null || gatt == null) {
            Log.w(TAG, "Uplink unavailable");
            return false;
        }
        byte[] framed = (json + "\n").getBytes(UTF8);
        for (int i = 0; i < framed.length; i += 20) {
            int end = Math.min(i + 20, framed.length);
            byte[] chunk = java.util.Arrays.copyOfRange(framed, i, end);
            enqueue(new GattOp(GattOp.CHARACTERISTIC, uplink, null, chunk));
        }
        Log.i(TAG, "Sent uplink: " + json);
        return true;
    }

    /**
     * The phone splits each JSON command into &lt;=20-byte BLE notifications (Android 4.4's
     * ATT MTU caps a single notification at 20 bytes), terminated by a newline. Reassemble
     * the chunks and dispatch each complete command.
     */
    private void handleCommandChunk(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        commandBuffer.write(value, 0, value.length);
        byte[] data = commandBuffer.toByteArray();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                String command = new String(data, start, i - start, UTF8).trim();
                if (command.length() > 0) {
                    listener.onCompanionCommand(command);
                }
                start = i + 1;
            }
        }
        commandBuffer.reset();
        int remaining = data.length - start;
        if (remaining > 0 && remaining <= 4096) {
            commandBuffer.write(data, start, remaining);
        }
    }

    // ------------------------------------------------------------------------------------
    // Advertisement parsing
    // ------------------------------------------------------------------------------------
    private static final class ScanInfo {
        String name;
        boolean hasCts;
        boolean hasMyGlass;
        boolean hasCompanionService;
        boolean appleCompany;
    }

    /**
     * Parses the raw BLE advertisement for the local name and the service UUIDs that
     * identify the MyGlass Companion / iPhone.
     */
    private static ScanInfo parseScanRecord(byte[] record) {
        ScanInfo info = new ScanInfo();
        if (record == null) {
            return info;
        }
        int i = 0;
        while (i + 1 < record.length) {
            int length = record[i] & 0xFF;
            if (length == 0 || i + 1 + length > record.length) {
                break;
            }
            int type = record[i + 1] & 0xFF;
            int dataStart = i + 2;
            int dataLength = length - 1;
            switch (type) {
                case 0x08: // Shortened local name
                case 0x09: // Complete local name
                    info.name = new String(record, dataStart, dataLength, UTF8);
                    break;
                case 0x02: // Incomplete list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    for (int j = dataStart; j + 1 < dataStart + dataLength; j += 2) {
                        int uuid16 = (record[j] & 0xFF) | ((record[j + 1] & 0xFF) << 8);
                        if (uuid16 == CTS_SERVICE_16) {
                            info.hasCts = true;
                        } else if (uuid16 == MYGLASS_SERVICE_16) {
                            info.hasMyGlass = true;
                        }
                    }
                    break;
                case 0x06: // Incomplete list of 128-bit UUIDs
                case 0x07: // Complete list of 128-bit UUIDs
                    for (int j = dataStart; j + 15 < dataStart + dataLength; j += 16) {
                        if (uuidMatches(record, j, Constants.MYGLASS_SERVICE)) {
                            info.hasMyGlass = true;
                        } else if (uuidMatches(record, j, Constants.CTS_SERVICE)) {
                            info.hasCts = true;
                        }
                    }
                    break;
                case 0xFF: // Manufacturer specific data
                    if (dataLength >= 2) {
                        int company = (record[dataStart] & 0xFF)
                                | ((record[dataStart + 1] & 0xFF) << 8);
                        if (company == APPLE_COMPANY_ID) {
                            info.appleCompany = true;
                        }
                    }
                    break;
                default:
                    break;
            }
            info.hasCompanionService = info.hasCts || info.hasMyGlass;
            i += length + 1;
        }
        return info;
    }

    /** Compares a 16-byte advertisement UUID against a canonical UUID (either byte order). */
    private static boolean uuidMatches(byte[] data, int offset, UUID uuid) {
        byte[] canonical = uuidToBytes(uuid);
        boolean direct = true;
        boolean reversed = true;
        for (int i = 0; i < 16; i++) {
            if (data[offset + i] != canonical[i]) {
                direct = false;
            }
            if (data[offset + i] != canonical[15 - i]) {
                reversed = false;
            }
        }
        return direct || reversed;
    }

    private static byte[] uuidToBytes(UUID uuid) {        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (8 * (7 - i)));
        }
        for (int i = 0; i < 8; i++) {
            out[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return out;
    }

    private static String toHex(byte[] data) {
        if (data == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------
    // Clock parsing
    // ------------------------------------------------------------------------------------

    /** Parses the 10-byte Current Time Service payload into epoch milliseconds. */
    static long parseCurrentTime(byte[] value) {
        if (value == null || value.length < 7) {
            return -1;
        }
        int year = (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
        int month = value[2] & 0xFF;
        int day = value[3] & 0xFF;
        int hour = value[4] & 0xFF;
        int minute = value[5] & 0xFF;
        int second = value[6] & 0xFF;
        if (year < 1970 || month < 1 || month > 12 || day < 1 || day > 31) {
            return -1;
        }
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }

    /** Parses an 8-byte little-endian Unix epoch (milliseconds). */
    static long parseEpochMillis(byte[] value) {
        if (value == null || value.length < 8) {
            return -1;
        }
        long millis = 0;
        for (int i = 7; i >= 0; i--) {
            millis = (millis << 8) | (value[i] & 0xFFL);
        }
        return millis > 0 ? millis : -1;
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private static BluetoothGattCharacteristic findCharacteristic(
            BluetoothGatt gatt, UUID serviceUuid, UUID characteristicUuid) {
        BluetoothGattService service = gatt.getService(serviceUuid);
        return service != null ? service.getCharacteristic(characteristicUuid) : null;
    }

    private static boolean contains(List<BluetoothDevice> list, BluetoothDevice device) {
        String address = device.getAddress();
        for (BluetoothDevice other : list) {
            if (address != null && address.equals(other.getAddress())) {
                return true;
            }
        }
        return false;
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    private static boolean isAppleDevice(BluetoothDevice device) {
        return isAppleName(safeName(device));
    }

    private static boolean isAppleName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.contains("iphone")
                || lower.contains("ipad")
                || lower.contains("ipod")
                || lower.contains("apple")
                || lower.contains("myglass");
    }

    private void closeGatt() {
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(discoveryTimeout);
        opQueue.clear();
        opBusy = false;
        pendingSubscriptions = 0;
        ready = false;
        activeRequestUid = -1;
        requestQueue.clear();
        dataBuffer.reset();
        commandBuffer.reset();
        receivedAttributes.clear();
        notifications.clear();
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        subscribed.clear();
        controlPoint = null;
        dataSource = null;
        notificationSource = null;
        commandChar = null;
        uplink = null;
    }

    /**
     * Re-attempts the ANCS subscription on the currently-open link. Used when the first
     * attempt was rejected for insufficient authentication: Android 4.4 does not
     * re-encrypt a stored bond by itself, so we keep the link up and retry rather than
     * tearing it down (which just churns).
     */
    private void retrySubscription() {
        if (!started || ready) {
            return;
        }
        if (gatt == null) {
            reconnect();
            return;
        }
        Log.i(TAG, "Re-attempting ANCS subscription on the current link");
        opQueue.clear();
        opBusy = false;
        pendingSubscriptions = 0;
        ready = false;
        if (notificationSource != null) {
            enableNotifications(gatt, notificationSource);
        }
        if (dataSource != null) {
            enableNotifications(gatt, dataSource);
        }
        if (commandChar != null) {
            enableNotifications(gatt, commandChar);
        }
        readCompanionClock(gatt);
    }

    /**
     * iOS only delivers ANCS notifications to a bonded accessory, so if the iPhone is
     * not paired yet we ask the system to start bonding (this shows the standard iOS
     * pairing prompt).
     */
    private void ensureBond(BluetoothDevice device) {
        try {
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "Already bonded with " + pendingDeviceName);
                return;
            }
            String address = device.getAddress();
            if (bondRequested.contains(address)) {
                Log.i(TAG, "Bond already requested for " + pendingDeviceName);
                return;
            }
            bondRequested.add(address);
            Log.i(TAG, "Requesting bond with " + pendingDeviceName);
            device.createBond();
        } catch (Exception e) {
            Log.w(TAG, "createBond failed: " + e.getMessage());
        }
    }

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, 5000L);
    }
}
