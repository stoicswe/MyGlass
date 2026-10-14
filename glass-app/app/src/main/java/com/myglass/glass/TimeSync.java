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

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sets the Glass clock.
 *
 * <p>A normal application cannot change the system clock: doing so needs the
 * signature-level {@code android.permission.SET_TIME}. We therefore try, in order:
 * <ol>
 *     <li>{@link SystemClock#setCurrentTimeMillis(long)} (works if the app is
 *     system-signed / holds SET_TIME);</li>
 *     <li>a rooted shell ({@code su}) running the platform {@code date} command;</li>
 *     <li>otherwise an NTP request over the network is used as a best-effort source.</li>
 * </ol>
 */
public final class TimeSync {

    private static final String TAG = "TimeSync";
    private static final long ACCEPTABLE_DRIFT_MS = 2000L;

    private TimeSync() {
    }

    /** True when the given time is already close enough to now. */
    public static boolean isDriftAcceptable(long epochMillis) {
        return Math.abs(System.currentTimeMillis() - epochMillis) < ACCEPTABLE_DRIFT_MS;
    }

    /**
     * Applies {@code epochMillis} to the device clock.
     *
     * @return true if the clock appears to have been updated.
     */
    public static boolean applyTime(Context context, long epochMillis) {
        if (isDriftAcceptable(epochMillis)) {
            return true;
        }

        // 1. Direct call, requires SET_TIME (system app) on most builds.
        if (trySystemClock(epochMillis)) {
            if (isDriftAcceptable(epochMillis)) {
                return true;
            }
        }

        // 2. Rooted device: use the platform date command.
        if (tryRoot(epochMillis)) {
            return isDriftAcceptable(epochMillis);
        }

        Log.w(TAG, "Unable to set the clock; Glass is not rooted and lacks SET_TIME");
        return false;
    }

    /** Best-effort NTP sync over the network. */
    public static boolean syncOverNtp(Context context) {        SntpClient client = new SntpClient();
        if (client.requestTime(Constants.NTP_HOST, Constants.NTP_TIMEOUT_MS)) {
            long epoch = client.getNtpTime();
            Log.i(TAG, "NTP time: " + epoch + " (rtt " + client.getRoundTripTime() + "us)");
            return applyTime(context, epoch);
        }
        return false;
    }

    /**
     * Applies {@code zoneId} (an IANA id or a "GMT±HH:MM" fixed offset) to the device.
     *
     * @return true if the zone could be set.
     */
    public static boolean applyTimeZone(Context context, String zoneId) {
        if (zoneId == null || zoneId.length() == 0) {
            return false;
        }

        // 1. The official Glass companion hook. XE listens for this broadcast and applies
        //    the zone as a system service, so no root is required. This is exactly what
        //    the original MyGlass companion did (TimeZoneUtil.updateTimeZoneByCompanion).
        try {
            Intent intent = new Intent("com.google.glass.timezone.COMPANION_UPDATE_TIME_ZONE");
            intent.putExtra("time_zone", zoneId);
            intent.putExtra("priority", 100); // TZ_PRIORITY_COMPANION
            context.sendBroadcast(intent);
            try {
                context.sendStickyBroadcast(intent);
            } catch (Throwable ignored) {
            }
            // Give the receiver a moment and verify.
            try {
                Thread.sleep(600L);
            } catch (InterruptedException ignored) {
            }
            String applied = getSystemProperty("persist.sys.timezone");
            Log.i(TAG, "Time zone broadcast sent: " + zoneId + " (now " + applied + ")");
            if (zoneId.equals(applied)) {
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "Time zone broadcast failed: " + t);
        }

        // 2. AlarmManager.setTimeZone requires SET_TIME (system-signed app).
        try {
            AlarmManager alarmManager =
                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.setTimeZone(zoneId);
                Log.i(TAG, "Time zone set via AlarmManager: " + zoneId);
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "AlarmManager.setTimeZone unavailable: " + t);
        }

        // 3. Rooted device: set the persistent property the framework reads at boot.
        if (runSu("setprop persist.sys.timezone " + zoneId)) {
            Log.i(TAG, "Time zone set via su: " + zoneId);
            return true;
        }

        Log.w(TAG, "Unable to set time zone " + zoneId);
        return false;
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class);
            return (String) get.invoke(null, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean trySystemClock(long epochMillis) {
        try {
            Method method = SystemClock.class.getMethod("setCurrentTimeMillis", long.class);
            method.invoke(null, epochMillis);
            Log.i(TAG, "Clock set via SystemClock.setCurrentTimeMillis");
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "SystemClock.setCurrentTimeMillis unavailable: " + t);
            return false;
        }
    }

    private static boolean tryRoot(long epochMillis) {
        // The Android 4.4 toolbox date command has no -s flag; it accepts
        // "YYYYMMDDhhmm.ss" (optionally -u for UTC). busybox/toybox additionally
        // understand "date -s @<seconds>". Try both.
        long seconds = epochMillis / 1000L;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmm.ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String toolboxValue = format.format(new Date(epochMillis));

        String[] commands = {
                "date -s @" + seconds,
                "date -u " + toolboxValue,
        };

        for (String command : commands) {
            if (runSu(command)) {
                Log.i(TAG, "Clock set via su: " + command);
                return true;
            }
        }
        return false;
    }

    private static boolean runSu(String command) {
        Process process = null;
        DataOutputStream stdin = null;
        try {
            process = Runtime.getRuntime().exec("su");
            stdin = new DataOutputStream(process.getOutputStream());
            stdin.writeBytes(command + "\n");
            stdin.writeBytes("exit\n");
            stdin.flush();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            Log.d(TAG, "su failed: " + e.getMessage());
            return false;
        } finally {
            try {
                if (stdin != null) {
                    stdin.close();
                }
            } catch (Exception ignored) {
            }
            if (process != null) {
                process.destroy();
            }
        }
    }
}
