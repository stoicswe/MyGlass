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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Read more" view for a notification stack.
 *
 * <p>Use the touchpad to <b>swipe left/right</b> through the stack (one card per
 * notification, newest first) and <b>tap</b> to open that notification's options menu.
 * Long messages are paginated into multiple cards.
 */
public class NotificationDetailActivity extends Activity {

    /** Rough number of characters that fit on one Glass text card. */
    private static final int PAGE_LENGTH = 130;

    private static final int MENU_POSITIVE = 1;
    private static final int MENU_NEGATIVE = 2;
    private static final int MENU_REMOVE = 3;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("h:mm a", Locale.US);

    /** One card page: a notification plus one page of its (paginated) text. */
    private static final class Page {
        final AncsNotification notification;
        final String text;
        final int index;
        final int count;

        Page(AncsNotification notification, String text, int index, int count) {
            this.notification = notification;
            this.text = text;
            this.index = index;
            this.count = count;
        }
    }

    private CardScrollView cardScrollView;
    private final List<Page> pages = new ArrayList<Page>();
    private String tag;

    public static Intent intent(Context context, String cardTag) {
        Intent intent = new Intent(context, NotificationDetailActivity.class);
        intent.putExtra(TimelineCards.EXTRA_TAG, cardTag);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tag = getIntent() != null
                ? getIntent().getStringExtra(TimelineCards.EXTRA_TAG) : null;
        List<AncsNotification> list = TimelineCards.lookup(tag);
        if (list == null || list.isEmpty()) {
            finish();
            return;
        }
        for (AncsNotification notification : list) {
            List<String> chunks = paginate(detailText(notification));
            for (int i = 0; i < chunks.size(); i++) {
                pages.add(new Page(notification, chunks.get(i), i, chunks.size()));
            }
        }

        cardScrollView = new CardScrollView(this);
        cardScrollView.setAdapter(new StackAdapter());
        cardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openOptionsMenu();
            }
        });
        setContentView(cardScrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cardScrollView.activate();
    }

    @Override
    protected void onPause() {
        cardScrollView.deactivate();
        super.onPause();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        Page page = currentPage();
        if (page != null) {
            AncsNotification notification = page.notification;
            if (notification.hasPositiveAction()) {
                menu.add(Menu.NONE, MENU_POSITIVE, 0, notification.positiveActionText());
            }
            if (notification.hasNegativeAction()) {
                menu.add(Menu.NONE, MENU_NEGATIVE, 1, notification.negativeActionText());
            }
        }
        menu.add(Menu.NONE, MENU_REMOVE, 2, R.string.action_remove);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Page page = currentPage();
        switch (item.getItemId()) {
            case MENU_POSITIVE:
                if (page != null) {
                    performAction(page.notification.uid, Ancs.ACTION_POSITIVE);
                }
                finish();
                return true;
            case MENU_NEGATIVE:
                if (page != null) {
                    performAction(page.notification.uid, Ancs.ACTION_NEGATIVE);
                }
                finish();
                return true;
            case MENU_REMOVE:
                if (page != null) {
                    Intent dismiss = new Intent(TimelineCards.ACTION_DISMISS);
                    dismiss.putExtra(TimelineCards.EXTRA_TAG, tag);
                    dismiss.putExtra(TimelineCards.EXTRA_UID, page.notification.uid);
                    sendBroadcast(dismiss);
                }
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void performAction(long uid, int action) {
        Intent intent = new Intent(MyGlassService.ACTION_NOTIFICATION_ACTION);
        intent.putExtra(MyGlassService.EXTRA_UID, uid);
        intent.putExtra(MyGlassService.EXTRA_ACTION, action);
        sendBroadcast(intent);
    }

    private Page currentPage() {
        if (cardScrollView == null || pages.isEmpty()) {
            return null;
        }
        int position = cardScrollView.getSelectedItemPosition();
        if (position < 0 || position >= pages.size()) {
            return null;
        }
        return pages.get(position);
    }

    private final class StackAdapter extends CardScrollAdapter {
        @Override
        public int getCount() {
            return pages.size();
        }

        @Override
        public Object getItem(int position) {
            return pages.get(position);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Page page = pages.get(position);
            AncsNotification notification = page.notification;
            CardBuilder builder = new CardBuilder(NotificationDetailActivity.this,
                    CardBuilder.Layout.TEXT);
            builder.setText(page.text);
            String footnote = notification.metaText();
            if (page.count > 1) {
                footnote += " (" + (page.index + 1) + "/" + page.count + ")";
            }
            builder.setFootnote(footnote);
            long when = notification.dateMillis > 0
                    ? notification.dateMillis : System.currentTimeMillis();
            builder.setTimestamp(TIME_FORMAT.format(new Date(when)));
            if (page.count > 1 && page.index == 0) {
                builder.showStackIndicator(true);
            }
            return builder.getView();
        }

        @Override
        public int getPosition(Object item) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i) == item) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static String detailText(AncsNotification notification) {
        StringBuilder sb = new StringBuilder(notification.headingText());
        String body = notification.bodyText();
        if (body != null && body.length() > 0) {
            sb.append("\n\n").append(body);
        }
        return sb.toString();
    }

    /** Splits long text into Glass-sized pages, breaking on word boundaries. */
    private static List<String> paginate(String text) {
        List<String> pages = new ArrayList<String>();
        if (text == null || text.length() == 0) {
            pages.add("");
            return pages;
        }
        int total = text.length();
        for (int start = 0; start < total; ) {
            int end = Math.min(start + PAGE_LENGTH, total);
            if (end < total) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > start + 1) {
                    end = lastSpace + 1;
                }
            }
            String chunk = text.substring(start, end);
            if (start > 0) {
                int first = 0;
                while (first < chunk.length() && chunk.charAt(first) == ' ') {
                    first++;
                }
                chunk = chunk.substring(first);
            }
            pages.add(chunk);
            start = end;
        }
        return pages;
    }
}
