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
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
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

    private static final String CHANNEL_ID = "myglass";
    private static final int NOTIFICATION_ID = 0x4D4700;

    private AncsClient ancsClient;
    private NotificationPresenter presenter;
    private boolean ntpSynced;
    private boolean ianaZoneSeen;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        presenter = new NotificationPresenter(this);
        ancsClient = new AncsClient(this, this);
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
        Log.i(TAG, "Companion command: " + payload);
        try {
            JSONObject json = new JSONObject(payload);
            String cmd = json.optString("cmd");
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
