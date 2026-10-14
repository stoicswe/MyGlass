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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Shows ANCS notifications on Glass.
 *
 * <p>When the GDK is available (as on this Glass build), each notification is published
 * as a persistent timeline {@link TimelineCards LiveCard} using {@code CardBuilder} — so
 * it shows up in the timeline like a photo card, not just as a transient popup. If the
 * GDK classes are missing, it falls back to a standard Android notification popup.
 */
public class NotificationPresenter {

    private static final String TAG = "NotificationPresenter";

    private final Context context;
    private final NotificationManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> active = new HashSet<Integer>();
    private final TimelineCards cards;

    public NotificationPresenter(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.cards = createCardsIfAvailable(context);
    }

    private static TimelineCards createCardsIfAvailable(Context context) {
        if (!(context instanceof Service)) {
            return null;
        }
        try {
            Class.forName("com.google.android.glass.timeline.LiveCard");
            return new TimelineCards((Service) context);
        } catch (Throwable t) {
            Log.i(TAG, "GDK not available; using notification popups (" + t + ")");
            return null;
        }
    }

    public void show(AncsNotification notification) {
        if (cards != null) {
            try {
                cards.show(notification);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Timeline card failed; falling back to popup", t);
            }
        }
        showPopup(notification);
    }

    private void showPopup(AncsNotification notification) {
        int id = notificationId(notification.uid);
        cancel(id);

        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(notification.headingText())
                .setContentText(notification.bodyText())
                .setTicker(notification.appName())
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(PendingIntent.getActivity(
                        context, 0, new Intent(context, MainActivity.class), 0));

        // Silent ANCS notifications should not make a sound.
        if (!notification.isSilent()) {
            builder.setDefaults(Notification.DEFAULT_ALL);
        }

        manager.notify(id, builder.build());
        active.add(id);
        Log.i(TAG, "Showing popup for " + notification.appName() + " (uid " + notification.uid + ")");

        final int cancelId = id;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                cancel(cancelId);
            }
        }, Constants.NOTIFICATION_POPUP_TIMEOUT_MS);
    }

    public void dismiss(long uid) {
        if (cards != null) {
            cards.dismiss(uid);
        }
        cancel(notificationId(uid));
    }

    public void dismissAll() {
        handler.removeCallbacksAndMessages(null);
        if (cards != null) {
            cards.dismissAll();
        }
        for (Integer id : new HashSet<Integer>(active)) {
            cancel(id);
        }
    }

    private void cancel(int id) {
        active.remove(id);
        if (manager != null) {
            manager.cancel(id);
        }
    }

    private static int notificationId(long uid) {
        return (int) (uid & 0x7FFFFFFF);
    }
}
