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

import java.util.List;

/**
 * Transparent options menu shown when a notification timeline card is tapped. It offers:
 * <ul>
 *   <li><b>Read more</b> — the full, scrollable notification text;</li>
 *   <li>the notification's explicit <b>positive action</b> (e.g. "Reply") when iOS
 *       advertises one;</li>
 *   <li>the <b>negative action</b> (e.g. "Dismiss") when advertised;</li>
 *   <li><b>Remove</b> — drop the card from the timeline.</li>
 * </ul>
 */
public class CardMenuActivity extends Activity {

    private static final int MENU_READ = 1;
    private static final int MENU_POSITIVE = 2;
    private static final int MENU_NEGATIVE = 3;
    private static final int MENU_REMOVE = 4;

    private String tag;
    private AncsNotification latest;

    public static Intent intent(Context context, String cardTag) {
        Intent intent = new Intent(context, CardMenuActivity.class);
        intent.putExtra(TimelineCards.EXTRA_TAG, cardTag);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tag = getIntent() != null
                ? getIntent().getStringExtra(TimelineCards.EXTRA_TAG) : null;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        openOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        List<AncsNotification> list = TimelineCards.lookup(tag);
        latest = (list != null && !list.isEmpty()) ? list.get(0) : null;

        menu.add(Menu.NONE, MENU_READ, 0, R.string.action_read);
        if (latest != null) {
            if (latest.hasPositiveAction()) {
                menu.add(Menu.NONE, MENU_POSITIVE, 1, latest.positiveActionText());
            }
            if (latest.hasNegativeAction()) {
                menu.add(Menu.NONE, MENU_NEGATIVE, 2, latest.negativeActionText());
            }
        }
        menu.add(Menu.NONE, MENU_REMOVE, 3, R.string.action_remove);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_READ:
                startActivity(NotificationDetailActivity.intent(this, tag));
                finish();
                return true;
            case MENU_POSITIVE:
                performAction(Ancs.ACTION_POSITIVE);
                return true;
            case MENU_NEGATIVE:
                performAction(Ancs.ACTION_NEGATIVE);
                return true;
            case MENU_REMOVE:
                Intent dismiss = new Intent(TimelineCards.ACTION_DISMISS);
                dismiss.putExtra(TimelineCards.EXTRA_TAG, tag);
                sendBroadcast(dismiss);
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void performAction(int action) {
        if (latest == null) {
            finish();
            return;
        }
        Intent intent = new Intent(MyGlassService.ACTION_NOTIFICATION_ACTION);
        intent.putExtra(MyGlassService.EXTRA_UID, latest.uid);
        intent.putExtra(MyGlassService.EXTRA_ACTION, action);
        sendBroadcast(intent);
        finish();
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        super.onOptionsMenuClosed(menu);
        finish();
    }
}
