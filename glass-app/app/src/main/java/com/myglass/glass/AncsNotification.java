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
 * A single iOS notification decoded from ANCS.
 */
public class AncsNotification {

    public static final int FLAG_SILENT = 0x01;
    public static final int FLAG_IMPORTANT = 0x02;
    public static final int FLAG_PRE_EXISTING = 0x04;
    public static final int FLAG_POSITIVE_ACTION = 0x08;
    public static final int FLAG_NEGATIVE_ACTION = 0x10;

    public long uid;
    public int eventId;
    public int eventFlags;
    public int categoryId;
    public int categoryCount;

    public String appIdentifier;
    public String title;
    public String subtitle;
    public String message;
    public String dateString;
    public long dateMillis;
    public String positiveActionLabel;
    public String negativeActionLabel;

    public boolean isSilent() {
        return (eventFlags & FLAG_SILENT) != 0;
    }

    public boolean isImportant() {
        return (eventFlags & FLAG_IMPORTANT) != 0;
    }

    public boolean isPreExisting() {
        return (eventFlags & FLAG_PRE_EXISTING) != 0;
    }

    public boolean isRemoved() {
        return eventId == Ancs.EVENT_REMOVED;
    }

    /** Whether iOS advertises a positive (e.g. "Reply") action for this notification. */
    public boolean hasPositiveAction() {
        return (eventFlags & FLAG_POSITIVE_ACTION) != 0;
    }

    /** Whether iOS advertises a negative (e.g. "Dismiss") action for this notification. */
    public boolean hasNegativeAction() {
        return (eventFlags & FLAG_NEGATIVE_ACTION) != 0;
    }

    public String positiveActionText() {
        return positiveActionLabel != null && positiveActionLabel.length() > 0
                ? positiveActionLabel : "Reply";
    }

    public String negativeActionText() {
        return negativeActionLabel != null && negativeActionLabel.length() > 0
                ? negativeActionLabel : "Dismiss";
    }

    /** Best-effort friendly application name from the reverse-DNS app identifier. */
    public String appName() {
        if (appIdentifier == null || appIdentifier.length() == 0) {
            return Ancs.categoryName(categoryId);
        }
        int dot = appIdentifier.lastIndexOf('.');
        String name = dot >= 0 ? appIdentifier.substring(dot + 1) : appIdentifier;
        if (name.length() == 0) {
            return Ancs.categoryName(categoryId);
        }
        StringBuilder sb = new StringBuilder(name.length());
        sb.append(Character.toUpperCase(name.charAt(0)));
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-' || c == '_') {
                sb.append(' ');
            } else if (Character.isUpperCase(c) && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public String headingText() {
        if (title != null && title.length() > 0) {
            return title;
        }
        return appName();
    }

    public String bodyText() {
        StringBuilder sb = new StringBuilder();
        if (subtitle != null && subtitle.length() > 0) {
            sb.append(subtitle);
        }
        if (message != null && message.length() > 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(message);
        }
        return sb.toString();
    }

    /** Full notification text for the scrollable detail card. */
    public String detailText() {
        StringBuilder sb = new StringBuilder();
        if (title != null && title.length() > 0) {
            sb.append(title);
        }
        if (subtitle != null && subtitle.length() > 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(subtitle);
        }
        if (message != null && message.length() > 0) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(message);
        }
        if (sb.length() == 0) {
            sb.append(appName());
        }
        return sb.toString();
    }

    /** Footnote-ish line: app name plus the ANCS category. */
    public String metaText() {
        String app = appName();
        String category = Ancs.categoryName(categoryId);
        if (category != null && category.length() > 0 && !category.equalsIgnoreCase(app)) {
            return app + " \u00b7 " + category;
        }
        return app;
    }
}
