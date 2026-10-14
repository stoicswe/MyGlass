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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONObject;

/**
 * Foreground service that keeps the ANCS connection alive, renders iPhone
 * notifications as Glass popups and keeps the device clock in sync.
 */
public class MyGlassService extends Service implements AncsClient.Listener {

    private static final String TAG = "MyGlassService";

    public static final String ACTION_START = "com.myglass.glass.action.START";
    public static final String ACTION_SYNC_TIME = "com.myglass.glass.action.SYNC_TIME";
    public static final String ACTION_RECONNECT = "com.myglass.glass.action.RECONNECT";
    public static final String ACTION_TEST_NOTIFICATION = "com.myglass.glass.action.TEST_NOTIFICATION";
    public static final String ACTION_SET_WIFI = "com.myglass.glass.action.SET_WIFI";
    public static final String ACTION_RUN_SETUP = "com.myglass.glass.action.RUN_SETUP";
    public static final String ACTION_STOP = "com.myglass.glass.action.STOP";
    public static final String ACTION_NOTIFICATION_ACTION =
            "com.myglass.glass.action.NOTIFICATION_ACTION";
    public static final String ACTION_MEDIA_COMMAND = "com.myglass.glass.action.MEDIA_COMMAND";
    public static final String EXTRA_UID = "uid";
    public static final String EXTRA_ACTION = "action";
    public static final String EXTRA_COMMAND = "command";

    private static final String CHANNEL_ID = "myglass";
    private static final int NOTIFICATION_ID = 0x4D4700;

    private AncsClient ancsClient;
    private NotificationPresenter presenter;
    private MediaCard mediaCard;
    private boolean ntpSynced;
    private boolean ianaZoneSeen;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Periodically reports Glass battery/storage to the phone. */
    private final Runnable glassInfoRunnable = new Runnable() {
        @Override
        public void run() {
            sendGlassInfo();
            handler.postDelayed(this, 60000L);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                sendGlassInfo();
            }
        }
    };

    /** Receives notification-action taps and media commands from the Glass UI. */
    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (ACTION_NOTIFICATION_ACTION.equals(intent.getAction())) {
                long uid = intent.getLongExtra(EXTRA_UID, -1);
                int action = intent.getIntExtra(EXTRA_ACTION, -1);
                if (uid >= 0 && action >= 0 && ancsClient != null) {
                    ancsClient.performAction(uid, action);
                }
            } else if (ACTION_MEDIA_COMMAND.equals(intent.getAction())) {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                if (command != null && ancsClient != null) {
                    ancsClient.sendUplink("{\"m\":\"" + command + "\"}");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        presenter = new NotificationPresenter(this);
        ancsClient = new AncsClient(this, this);
        mediaCard = createMediaCardIfAvailable();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_NOTIFICATION_ACTION);
        filter.addAction(ACTION_MEDIA_COMMAND);
        registerReceiver(actionReceiver, filter);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.postDelayed(glassInfoRunnable, 5000L);
    }

    /** Sends this Glass's name, battery and storage to the phone over the uplink. */
    private void sendGlassInfo() {
        if (ancsClient == null) {
            return;
        }
        try {
            int level = -1;
            boolean charging = false;
            Intent battery = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (raw >= 0 && scale > 0) {
                    level = raw * 100 / scale;
                }
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }

            long total = 0L;
            long free = 0L;
            try {
                StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
                total = stat.getBlockCount() * (long) stat.getBlockSize();
                free = stat.getAvailableBlocks() * (long) stat.getBlockSize();
            } catch (Exception ignored) {
            }

            JSONObject info = new JSONObject();
            info.put("cmd", "glass_info");
            info.put("name", Build.MODEL);
            info.put("model", Build.MODEL);
            info.put("battery", level);
            info.put("charging", charging);
            info.put("free", free);
            info.put("total", total);
            ancsClient.sendUplink(info.toString());
        } catch (Exception e) {
            Log.w(TAG, "glass info failed: " + e.getMessage());
        }
    }

    private MediaCard createMediaCardIfAvailable() {
        try {
            Class.forName("com.google.android.glass.timeline.LiveCard");
            return new MediaCard(this);
        } catch (Throwable t) {
            Log.i(TAG, "GDK not available; media card disabled");
            return null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildForegroundNotification(getString(R.string.status_connecting)));

        String action = intent != null && intent.getAction() != null
                ? intent.getAction() : ACTION_START;

        switch (action) {
            case ACTION_SYNC_TIME:
                syncTimeOverNtp();
                break;
            case ACTION_RECONNECT:
                ancsClient.reconnect();
                break;
            case ACTION_TEST_NOTIFICATION:
                showTestNotification();
                break;
            case ACTION_SET_WIFI:
                if (intent != null) {
                    SetupActions.setWifi(this, intent.getStringExtra("ssid"),
                            intent.getIntExtra("enc", SetupActions.ENC_WPA),
                            intent.getStringExtra("psk"));
                }
                break;
            case ACTION_RUN_SETUP:
                if (intent != null) {
                    SetupActions.runSetup(this, intent.getStringExtra("setup_string"));
                }
                break;
            case ACTION_STOP:
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_START:
            default:
                ancsClient.start();
                syncTimeOverNtp();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (ancsClient != null) {
            ancsClient.stop();
        }
        if (presenter != null) {
            presenter.dismissAll();
        }
        if (mediaCard != null) {
            mediaCard.remove();
        }
        try {
            unregisterReceiver(actionReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------------------------
    // AncsClient.Listener
    // ------------------------------------------------------------------------------------

    @Override
    public void onAncsConnected(String deviceName) {
        Log.i(TAG, "Connected to companion: " + deviceName);
        String name = deviceName != null && deviceName.length() > 0
                ? deviceName : getString(R.string.app_name);
        updateNotification(getString(R.string.status_connected, name));
        sendGlassInfo();
    }

    @Override
    public void onAncsDisconnected() {
        updateNotification(getString(R.string.status_disconnected));
    }

    @Override
    public void onAncsNotification(AncsNotification notification) {
        if (notification.isRemoved()) {
            presenter.dismiss(notification.uid);
            return;
        }
        presenter.show(notification);
    }

    @Override
    public void onCompanionTime(final long epochMillis) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (TimeSync.applyTime(MyGlassService.this, epochMillis)) {
                    Log.i(TAG, "Clock synced from companion");
                    updateNotification(getString(R.string.time_synced));
                }
            }
        }, "clock-apply").start();
    }

    @Override
    public void onCompanionTimeZone(final String zoneId, final Integer offsetMinutes) {
        // Prefer the IANA identifier (handles DST); fall back to the fixed offset only
        // if no identifier has been seen.
        if (zoneId == null && ianaZoneSeen) {
            return;
        }
        if (zoneId != null) {
            ianaZoneSeen = true;
        }
        final String zone = (zoneId != null && zoneId.length() > 0)
                ? zoneId : zoneIdFromOffset(offsetMinutes);
        if (zone == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = TimeSync.applyTimeZone(MyGlassService.this, zone);
                Log.i(TAG, "Time zone " + zone + " applied=" + ok);
                if (ok) {
                    updateNotification(getString(R.string.time_synced) + " \u00b7 " + zone);
                }
            }
        }, "tz-apply").start();
    }

    @Override
    public void onCompanionCommand(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String cmd = json.optString("cmd");
            if ("ping".equals(cmd)) {
                return;
            }
            Log.i(TAG, "Companion command: " + payload);
            if ("wifi".equals(cmd)) {
                SetupActions.setWifi(this, json.optString("ssid"),
                        json.optInt("enc", SetupActions.ENC_WPA), json.optString("psk"));
            } else if ("setup".equals(cmd)) {
                String setupString = json.optString("setup_string");
                if (setupString == null || setupString.length() == 0) {
                    setupString = SetupActions.buildSetupString(
                            json.optString("email"),
                            json.optInt("enc", SetupActions.ENC_WPA),
                            json.optString("ssid"),
                            json.optString("psk"),
                            json.optBoolean("adb", false));
                }
                SetupActions.runSetup(this, setupString);
            } else if ("location".equals(cmd)) {
                double lat = json.optDouble("lat", Double.NaN);
                double lon = json.optDouble("lon", Double.NaN);
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    GlassLocation.update(this, lat, lon,
                            (float) json.optDouble("acc", 15.0),
                            json.optLong("t", 0L));
                }
            } else if ("media_state".equals(cmd)) {
                if (mediaCard != null) {
                    mediaCard.update(json.optString("title", ""), json.optString("artist", ""),
                            json.optBoolean("playing", false));
                }
            } else if ("media_stop".equals(cmd)) {
                if (mediaCard != null) {
                    mediaCard.remove();
                }
            } else if ("phone".equals(cmd)) {
                String name = json.optString("name", "Phone");
                int battery = json.optInt("battery", -1);
                Log.i(TAG, "Phone info: " + name + " battery=" + battery);
                updateNotification(getString(R.string.app_name) + " \u00b7 " + name
                        + (battery >= 0 ? " (" + battery + "%)" : ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad companion command", e);
        }
    }

    private static String zoneIdFromOffset(Integer offsetMinutes) {        if (offsetMinutes == null) {
            return null;
        }
        int minutes = offsetMinutes;
        char sign = minutes < 0 ? '-' : '+';
        minutes = Math.abs(minutes);
        return String.format(java.util.Locale.US, "GMT%c%02d:%02d", sign, minutes / 60, minutes % 60);
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private void syncTimeOverNtp() {
        if (ntpSynced) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (TimeSync.syncOverNtp(MyGlassService.this)) {
                    ntpSynced = true;
                }
            }
        }, "ntp-sync").start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** Posts a sample iPhone notification so the popup can be verified without a phone. */
    private void showTestNotification() {
        AncsNotification sample = new AncsNotification();
        sample.uid = System.currentTimeMillis();
        sample.eventId = Ancs.EVENT_ADDED;
        sample.categoryId = 6; // email
        sample.appIdentifier = "com.apple.MobileMail";
        sample.title = "Test notification";
        sample.message = "If you can see this on Glass, popups are working.";
        presenter.show(sample);
    }

    private Notification buildForegroundNotification(String text) {
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildForegroundNotification(text));
        }
    }
}
