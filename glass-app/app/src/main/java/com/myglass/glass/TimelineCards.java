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

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.widget.CardBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes ANCS notifications as Glass timeline {@link LiveCard}s built with the GDK's
 * {@link CardBuilder}. Notifications of the same ANCS category (type) stack onto a single
 * card, which shows the newest plus a "+N more" indicator; the card menu can read the
 * whole stack or perform the notification's explicit action.
 *
 * <p>The GDK classes come from the Glass system (glass-core.jar), so this class must only
 * be instantiated after confirming the GDK is present (see {@link NotificationPresenter}).
 */
public class TimelineCards {

    private static final String TAG = "TimelineCards";

    public static final String ACTION_DISMISS = "com.myglass.glass.action.DISMISS_CARD";
    public static final String EXTRA_TAG = "card_tag";
    public static final String EXTRA_UID = "uid";

    private static final String GROUP_PREFIX = "myglass_app_";
    private static final String GROUP_PREFIX_CATEGORY = "myglass_cat_";

    private static final String PREFS = "myglass_timeline";
    private static final String KEY_GROUPS = "groups";

    private final Service service;
    private final Map<String, LiveCard> cards = new HashMap<String, LiveCard>();
    private final Map<Long, String> uidToGroup = new HashMap<Long, String>();

    /** Group tag -> notifications (newest first). Static so activities can look it up. */
    private static final Map<String, List<AncsNotification>> groups =
            new HashMap<String, List<AncsNotification>>();

    public static List<AncsNotification> lookup(String tag) {
        List<AncsNotification> list = tag == null ? null : groups.get(tag);
        return list;
    }

    private final BroadcastReceiver dismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_DISMISS.equals(intent.getAction())) {
                return;
            }
            String tag = intent.getStringExtra(EXTRA_TAG);
            long uid = intent.getLongExtra(EXTRA_UID, -1);
            if (uid >= 0 && tag != null) {
                dismissUid(tag, uid);
            } else {
                unpublishByTag(tag);
            }
        }
    };

    public TimelineCards(Service service) {
        this.service = service;
        service.registerReceiver(dismissReceiver, new IntentFilter(ACTION_DISMISS));
        restore();
    }

    /** Adds (or updates) the timeline card for a notification's category stack. */
    public void show(AncsNotification notification) {
        String tag = groupTag(notification);
        List<AncsNotification> list = groups.get(tag);
        if (list == null) {
            list = new ArrayList<AncsNotification>();
            groups.put(tag, list);
        }
        removeUid(list, notification.uid);
        list.add(0, notification);
        uidToGroup.put(notification.uid, tag);

        LiveCard card = cards.get(tag);
        if (card == null) {
            card = new LiveCard(service, tag);
            cards.put(tag, card);
        }
        card.setViews(buildViews(list));
        card.setAction(PendingIntent.getActivity(
                service, tag.hashCode(),
                CardMenuActivity.intent(service, tag), 0));
        if (card.isPublished()) {
            card.navigate();
        } else {
            card.publish(LiveCard.PublishMode.REVEAL);
        }
        Log.i(TAG, "Published " + notification.appName() + " card (" + list.size()
                + " in stack " + tag + ")");
        persist();
    }

    /** Removes a notification (ANCS "Removed"); unpublishes the card if the stack empties. */
    public void dismiss(long uid) {
        String tag = uidToGroup.remove(uid);
        if (tag == null) {
            return;
        }
        List<AncsNotification> list = groups.get(tag);
        if (list != null) {
            removeUid(list, uid);
        }
        if (list == null || list.isEmpty()) {
            groups.remove(tag);
            LiveCard card = cards.remove(tag);
            if (card != null && card.isPublished()) {
                card.unpublish();
            }
            Log.i(TAG, "Stack " + tag + " empty; card removed");
        } else {
            LiveCard card = cards.get(tag);
            if (card != null) {
                card.setViews(buildViews(list));
            }
        }
        persist();
    }

    /** Removes a single notification from a stack, keeping the card if anything remains. */
    public void dismissUid(String tag, long uid) {
        if (tag == null) {
            return;
        }
        List<AncsNotification> list = groups.get(tag);
        if (list == null) {
            return;
        }
        removeUid(list, uid);
        uidToGroup.remove(uid);
        if (list.isEmpty()) {
            groups.remove(tag);
            LiveCard card = cards.remove(tag);
            if (card != null && card.isPublished()) {
                card.unpublish();
            }
            Log.i(TAG, "Stack " + tag + " empty; card removed");
        } else {
            LiveCard card = cards.get(tag);
            if (card != null) {
                card.setViews(buildViews(list));
            }
        }
        persist();
    }

    public void dismissAll() {
        for (LiveCard card : cards.values()) {
            if (card.isPublished()) {
                card.unpublish();
            }
        }
        cards.clear();
        uidToGroup.clear();
        groups.clear();
        try {
            service.unregisterReceiver(dismissReceiver);
        } catch (Exception ignored) {
        }
    }

    private RemoteViews buildViews(List<AncsNotification> list) {
        AncsNotification latest = list.get(0);
        StringBuilder text = new StringBuilder(latest.headingText());
        String body = latest.bodyText();
        if (body != null && body.length() > 0) {
            text.append('\n').append(body);
        }
        if (list.size() > 1) {
            text.append("\n\n+").append(list.size() - 1).append(" more");
        }

        CardBuilder builder = new CardBuilder(service.getApplicationContext(),
                CardBuilder.Layout.TEXT);
        builder.setText(text.toString());

        String footnote = latest.metaText();
        if (list.size() > 1) {
            footnote += " (" + list.size() + ")";
        }
        builder.setFootnote(footnote);
        long when = latest.dateMillis > 0 ? latest.dateMillis : System.currentTimeMillis();
        builder.setTimestamp(new SimpleDateFormat("h:mm a", Locale.US).format(new Date(when)));
        if (list.size() > 1) {
            builder.showStackIndicator(true);
        }
        return builder.getRemoteViews();
    }

    private void unpublishByTag(String tag) {
        if (tag == null) {
            return;
        }
        List<AncsNotification> list = groups.remove(tag);
        if (list != null) {
            for (AncsNotification n : list) {
                uidToGroup.remove(n.uid);
            }
        }
        LiveCard card = cards.remove(tag);
        if (card != null && card.isPublished()) {
            card.unpublish();
        }
        persist();
    }

    // ------------------------------------------------------------------------------------
    // Persistence: keep the stacks alive across app/service restarts
    // ------------------------------------------------------------------------------------

    private void persist() {
        try {
            service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_GROUPS, serializeGroups())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "persist failed", e);
        }
    }

    private static String serializeGroups() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<AncsNotification>> entry : groups.entrySet()) {
                JSONArray array = new JSONArray();
                for (AncsNotification n : entry.getValue()) {
                    JSONObject o = new JSONObject();
                    o.put("uid", n.uid);
                    o.put("flags", n.eventFlags);
                    o.put("cat", n.categoryId);
                    o.put("app", str(n.appIdentifier));
                    o.put("title", str(n.title));
                    o.put("sub", str(n.subtitle));
                    o.put("msg", str(n.message));
                    o.put("date", n.dateMillis);
                    o.put("pos", str(n.positiveActionLabel));
                    o.put("neg", str(n.negativeActionLabel));
                    array.put(o);
                }
                root.put(entry.getKey(), array);
            }
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Re-publishes the persisted stacks (silently) after a restart. */
    private void restore() {
        String raw = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_GROUPS, null);
        if (raw == null || raw.length() == 0) {
            return;
        }
        try {
            JSONObject root = new JSONObject(raw);
            Iterator<String> tags = root.keys();
            while (tags.hasNext()) {
                String tag = tags.next();
                JSONArray array = root.optJSONArray(tag);
                if (array == null) {
                    continue;
                }
                List<AncsNotification> list = new ArrayList<AncsNotification>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    AncsNotification n = new AncsNotification();
                    n.uid = o.optLong("uid");
                    n.eventFlags = o.optInt("flags");
                    n.categoryId = o.optInt("cat");
                    n.appIdentifier = o.optString("app");
                    n.title = o.optString("title");
                    n.subtitle = o.optString("sub");
                    n.message = o.optString("msg");
                    n.dateMillis = o.optLong("date");
                    n.positiveActionLabel = o.optString("pos");
                    n.negativeActionLabel = o.optString("neg");
                    list.add(n);
                }
                if (list.isEmpty()) {
                    continue;
                }
                groups.put(tag, list);
                for (AncsNotification n : list) {
                    uidToGroup.put(n.uid, tag);
                }
                LiveCard card = new LiveCard(service, tag);
                cards.put(tag, card);
                card.setViews(buildViews(list));
                card.setAction(PendingIntent.getActivity(service, tag.hashCode(),
                        CardMenuActivity.intent(service, tag), 0));
                card.publish(LiveCard.PublishMode.SILENT);
                Log.i(TAG, "Restored stack " + tag + " (" + list.size() + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
        }
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static void removeUid(List<AncsNotification> list, long uid) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uid == uid) {
                list.remove(i);
                return;
            }
        }
    }

    private static String groupTag(AncsNotification notification) {
        if (notification.appIdentifier != null && notification.appIdentifier.length() > 0) {
            return GROUP_PREFIX + notification.appIdentifier;
        }
        // Fall back to the category when iOS did not report an app identifier.
        return GROUP_PREFIX_CATEGORY + notification.categoryId;
    }
}
