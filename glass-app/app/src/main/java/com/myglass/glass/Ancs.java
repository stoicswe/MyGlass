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

/**
 * Constants and byte-level helpers for the Apple Notification Center Service.
 */
public final class Ancs {

    private Ancs() {
    }

    /* Notification Source - EventID. */
    public static final int EVENT_ADDED = 0;
    public static final int EVENT_MODIFIED = 1;
    public static final int EVENT_REMOVED = 2;

    /* Control Point - CommandID. */
    public static final int CMD_GET_NOTIFICATION_ATTRIBUTES = 0x00;
    public static final int CMD_GET_APP_ATTRIBUTES = 0x01;
    public static final int CMD_PERFORM_NOTIFICATION_ACTION = 0x02;

    /* Notification attribute identifiers. */
    public static final int ATTR_APP_IDENTIFIER = 0;
    public static final int ATTR_TITLE = 1;
    public static final int ATTR_SUBTITLE = 2;
    public static final int ATTR_MESSAGE = 3;
    public static final int ATTR_MESSAGE_SIZE = 4;
    public static final int ATTR_DATE = 5;
    public static final int ATTR_POSITIVE_ACTION_LABEL = 6;
    public static final int ATTR_NEGATIVE_ACTION_LABEL = 7;

    /* PerformNotificationAction action identifiers. */
    public static final int ACTION_POSITIVE = 0;
    public static final int ACTION_NEGATIVE = 1;

    /** Maximum attribute length requested from iOS. */
    public static final int MAX_ATTRIBUTE_LENGTH = 0xFF;

    /** Reads the 4-byte, little-endian NotificationUID. */
    public static long readUid(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    public static void writeUid(byte[] out, int offset, long uid) {
        out[offset] = (byte) (uid & 0xFF);
        out[offset + 1] = (byte) ((uid >> 8) & 0xFF);
        out[offset + 2] = (byte) ((uid >> 16) & 0xFF);
        out[offset + 3] = (byte) ((uid >> 24) & 0xFF);
    }

    /**
     * Builds a {@code GetNotificationAttributes} control-point command for the given
     * notification and attribute identifiers. Kept small enough to fit a default
     * 20-byte BLE attribute write.
     */
    public static byte[] getNotificationAttributes(long uid, int[] attributeIds) {
        byte[] out = new byte[5 + attributeIds.length * 3];
        out[0] = (byte) CMD_GET_NOTIFICATION_ATTRIBUTES;
        writeUid(out, 1, uid);
        int i = 5;
        for (int attributeId : attributeIds) {
            out[i++] = (byte) attributeId;
            out[i++] = (byte) (MAX_ATTRIBUTE_LENGTH & 0xFF);
            out[i++] = (byte) ((MAX_ATTRIBUTE_LENGTH >> 8) & 0xFF);
        }
        return out;
    }

    /** Human readable category name for the ANCS CategoryID. */
    public static String categoryName(int categoryId) {
        switch (categoryId) {
            case 0: return "Other";
            case 1: return "Incoming Call";
            case 2: return "Missed Call";
            case 3: return "Voicemail";
            case 4: return "Social";
            case 5: return "Schedule";
            case 6: return "Email";
            case 7: return "News";
            case 8: return "Health and Fitness";
            case 9: return "Business and Finance";
            case 10: return "Location";
            case 11: return "Entertainment";
            default: return "Other";
        }
    }
}
