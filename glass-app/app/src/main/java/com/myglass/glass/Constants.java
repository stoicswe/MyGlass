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

import java.util.UUID;

/**
 * Static UUIDs and tunables shared by the ANCS client and the companion service.
 */
public final class Constants {

    private Constants() {
    }

    /*
     * Apple Notification Center Service (ANCS).
     * https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/
     */
    public static final UUID ANCS_SERVICE =
            UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    public static final UUID ANCS_NOTIFICATION_SOURCE =
            UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    public static final UUID ANCS_CONTROL_POINT =
            UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    public static final UUID ANCS_DATA_SOURCE =
            UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");

    /** Client Characteristic Configuration Descriptor. */
    public static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /*
     * Standard Bluetooth SIG Current Time Service (0x1805) with the Current Time
     * characteristic (0x2A2B). The iOS companion advertises this so Glass can read
     * the phone's clock and apply it to the device.
     */
    public static final UUID CTS_SERVICE =
            UUID.fromString("00001805-0000-1000-8000-00805f9b34fb");
    public static final UUID CTS_CURRENT_TIME =
            UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb");
    /** Local Time Information: time-zone offset in 15-minute units. */
    public static final UUID CTS_LOCAL_TIME_INFO =
            UUID.fromString("00002a0f-0000-1000-8000-00805f9b34fb");

    /*
     * A small custom service the MyGlass Companion app also exposes, so Glass can
     * identify the phone and read the clock as Unix epoch milliseconds.
     */
    public static final UUID MYGLASS_SERVICE =
            UUID.fromString("0000a100-0000-1000-8000-00805f9b34fb");
    public static final UUID MYGLASS_DEVICE_NAME =
            UUID.fromString("0000a101-0000-1000-8000-00805f9b34fb");
    public static final UUID MYGLASS_EPOCH_MILLIS =
            UUID.fromString("0000a102-0000-1000-8000-00805f9b34fb");
    /** IANA time-zone identifier of the phone (e.g. "America/New_York"). */
    public static final UUID MYGLASS_TIMEZONE =
            UUID.fromString("0000a103-0000-1000-8000-00805f9b34fb");
    /** Phone -> Glass command channel (JSON), delivered via notifications. */
    public static final UUID MYGLASS_COMMAND =
            UUID.fromString("0000a104-0000-1000-8000-00805f9b34fb");
    /** Glass -> phone command channel (JSON), written to by Glass. */
    public static final UUID MYGLASS_UPLINK =
            UUID.fromString("0000a105-0000-1000-8000-00805f9b34fb");

    /** How long a notification popup stays on screen before it is dismissed. */
    public static final int NOTIFICATION_POPUP_TIMEOUT_MS = 8000;

    /** Delay used to coalesce fragmented ANCS data-source responses. */
    public static final int DATA_SOURCE_COALESCE_MS = 600;

    /** How long a single connection attempt may take before we try another device. */
    public static final int CONNECT_TIMEOUT_MS = 6000;

    /** BLE scan window used to discover a paired iPhone that is not reporting ANCS yet. */
    public static final long SCAN_TIMEOUT_MS = 15000L;

    /** NTP server used for the fallback clock sync. */
    public static final String NTP_HOST = "time.google.com";
    public static final int NTP_TIMEOUT_MS = 8000;
}
