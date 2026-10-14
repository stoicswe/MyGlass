/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.myglass.glass;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * A small SNTP (RFC 4330) client adapted from the AOSP
 * {@code android.net.SntpClient}. Used as the fallback clock source when Glass has
 * network connectivity but no companion alongside it.
 */
class SntpClient {

    private static final String TAG = "SntpClient";

    private static final int NTP_PORT = 123;
    private static final int NTP_MODE_CLIENT = 3;
    private static final int NTP_VERSION = 3;
    private static final int NTP_PACKET_SIZE = 48;
    private static final int ORIGINATE_TIME_OFFSET = 24;
    private static final int RECEIVE_TIME_OFFSET = 32;
    private static final int TRANSMIT_TIME_OFFSET = 40;
    private static final long OFFSET_1900_TO_1970 = ((365L * 70L) + 17L) * 24L * 60L * 60L;

    private long ntpTime;
    private long roundTripTime;

    /** Milliseconds since the Unix epoch according to the server. */
    long getNtpTime() {
        return ntpTime;
    }

    long getRoundTripTime() {
        return roundTripTime;
    }

    boolean requestTime(String host, int timeout) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);
            InetAddress address = InetAddress.getByName(host);
            byte[] buffer = new byte[NTP_PACKET_SIZE];
            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, NTP_PORT);

            buffer[0] = NTP_MODE_CLIENT | (NTP_VERSION << 3);

            long requestTime = System.currentTimeMillis();
            long requestTicks = System.nanoTime() / 1000;
            writeTimeStamp(buffer, TRANSMIT_TIME_OFFSET, requestTime);

            socket.send(request);

            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            long responseTicks = System.nanoTime() / 1000;
            long responseTime = requestTime + (responseTicks - requestTicks) / 1000;

            long originateTime = readTimeStamp(buffer, ORIGINATE_TIME_OFFSET);
            long receiveTime = readTimeStamp(buffer, RECEIVE_TIME_OFFSET);
            long transmitTime = readTimeStamp(buffer, TRANSMIT_TIME_OFFSET);
            long roundTrip = responseTicks - requestTicks - (transmitTime - receiveTime);
            long clockOffset = ((receiveTime - originateTime) + (transmitTime - responseTime)) / 2;

            ntpTime = responseTime + clockOffset;
            roundTripTime = roundTrip;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SNTP request to " + host + " failed: " + e.getMessage());
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static long readTimeStamp(byte[] buffer, int offset) {
        long seconds = read32(buffer, offset);
        long fraction = read32(buffer, offset + 4);
        return (seconds - OFFSET_1900_TO_1970) * 1000L + ((fraction * 1000L) / 0x100000000L);
    }

    private static void writeTimeStamp(byte[] buffer, int offset, long time) {
        long seconds = time / 1000L + OFFSET_1900_TO_1970;
        long fraction = ((time % 1000L) * 0x100000000L) / 1000L;
        write32(buffer, offset, seconds);
        write32(buffer, offset + 4, fraction);
    }

    private static long read32(byte[] buffer, int offset) {
        long value = (buffer[offset] & 0xFFL) << 24;
        value |= (buffer[offset + 1] & 0xFFL) << 16;
        value |= (buffer[offset + 2] & 0xFFL) << 8;
        value |= (buffer[offset + 3] & 0xFFL);
        return value;
    }

    private static void write32(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) (value >> 24);
        buffer[offset + 1] = (byte) (value >> 16);
        buffer[offset + 2] = (byte) (value >> 8);
        buffer[offset + 3] = (byte) value;
    }
}
