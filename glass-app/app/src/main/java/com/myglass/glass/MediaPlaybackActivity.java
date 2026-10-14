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
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

/**
 * Media controls for the phone's Apple Music playback. Swipe left/right to the control
 * you want and tap it to send the command to the phone.
 */
public class MediaPlaybackActivity extends Activity {

    private static final String[] LABELS = {
            "Play / Pause", "Next track", "Previous track",
    };
    private static final String[] COMMANDS = {
            "toggle", "next", "prev",
    };

    private CardScrollView cardScrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cardScrollView = new CardScrollView(this);
        cardScrollView.setAdapter(new ControlsAdapter());
        cardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < COMMANDS.length) {
                    send(COMMANDS[position]);
                }
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

    private void send(String command) {
        Intent intent = new Intent(MyGlassService.ACTION_MEDIA_COMMAND);
        intent.putExtra(MyGlassService.EXTRA_COMMAND, command);
        sendBroadcast(intent);
        finish();
    }

    private final class ControlsAdapter extends CardScrollAdapter {
        @Override
        public int getCount() {
            return LABELS.length;
        }

        @Override
        public Object getItem(int position) {
            return LABELS[position];
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CardBuilder builder = new CardBuilder(MediaPlaybackActivity.this,
                    CardBuilder.Layout.TEXT);
            builder.setText(LABELS[position]);
            builder.setFootnote("Apple Music");
            return builder.getView();
        }

        @Override
        public int getPosition(Object item) {
            for (int i = 0; i < LABELS.length; i++) {
                if (LABELS[i].equals(item)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
