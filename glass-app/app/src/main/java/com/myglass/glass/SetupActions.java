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

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Glass's own companion-setup hooks (reverse-engineered from XE). They let a companion
 * configure Wi-Fi and complete setup without any Google service or root:
 *
 * <ul>
 *     <li>{@code com.google.glass.companion.SETUP_WIFI} + extra {@code wifi_setup_string}
 *     ({@code SSID\nENCRYPTION\nPSK}) configures Wi-Fi.</li>
 *     <li>{@code com.google.glass.companion.SETUP_GLASS} + extra {@code setup_string}
 *     (the URL-encoded setup barcode) runs the companion setup flow, which applies
 *     Wi-Fi, sets the clock and disables the setup activity.</li>
 * </ul>
 */
public final class SetupActions {

    private static final String TAG = "SetupActions";

    /* WifiHelper.Encryption ordinals. */
    public static final int ENC_NONE = 0;
    public static final int ENC_WPA = 1;
    public static final int ENC_WEP = 2;

    private SetupActions() {
    }

    /** Configures Wi-Fi from the companion (no root). */
    public static void setWifi(Context context, String ssid, int encryption, String psk) {
        String encryptionName = encryptionName(encryption);
        String wifiSetupString = ssid + "\n" + encryptionName + "\n" + (psk == null ? "" : psk);
        try {
            Intent intent = new Intent("com.google.glass.companion.SETUP_WIFI");
            intent.putExtra("wifi_setup_string", wifiSetupString);
            context.sendBroadcast(intent);
            Log.i(TAG, "SETUP_WIFI sent for ssid=" + ssid + " enc=" + encryptionName);
        } catch (Throwable t) {
            Log.w(TAG, "SETUP_WIFI failed", t);
        }
    }

    /** Runs Glass's companion setup flow from a setup string. */
    public static void runSetup(Context context, String setupString) {
        try {
            Intent intent = new Intent("com.google.glass.companion.SETUP_GLASS");
            intent.putExtra("setup_string", setupString);
            context.sendBroadcast(intent);
            Log.i(TAG, "SETUP_GLASS sent");
        } catch (Throwable t) {
            Log.w(TAG, "SETUP_GLASS failed", t);
        }
    }

    /**
     * Builds the version-1 setup string that Glass's {@code SetupHelper.parseBarcodeValue}
     * understands: {@code version=1&email=..&enc=..&ssid=..&psk=..&adb=..}.
     */
    public static String buildSetupString(String email, int encryption, String ssid,
                                          String psk, boolean enableAdb) {
        StringBuilder sb = new StringBuilder();
        sb.append("version=1");
        sb.append("&email=").append(encode(email));
        sb.append("&enc=").append(encryption);
        sb.append("&ssid=").append(encode(ssid));
        sb.append("&psk=").append(encode(psk == null ? "" : psk));
        sb.append("&adb=").append(enableAdb);
        return sb.toString();
    }

    private static String encryptionName(int encryption) {
        switch (encryption) {
            case ENC_WEP: return "WEP";
            case ENC_WPA: return "WPA";
            case ENC_NONE:
            default: return "NONE";
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
