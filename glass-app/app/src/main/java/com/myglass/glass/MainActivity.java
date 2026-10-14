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
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Voice-triggered entry point ("ok glass, my glass").
 *
 * <p>It explains what the Glassware does and launches {@link MyGlassService}. Tapping
 * the screen dismisses it. Uses plain Android views because this Glass build does not
 * include the GDK.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView view = new TextView(this);
        view.setText(R.string.activity_intro);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        view.setBackgroundColor(Color.BLACK);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setContentView(view);

        startService(new Intent(this, MyGlassService.class)
                .setAction(MyGlassService.ACTION_START));

        // Debug helper: `adb shell am start .../.MainActivity --ez test_notification true`
        if (getIntent() != null && getIntent().getBooleanExtra("test_notification", false)) {
            startService(new Intent(this, MyGlassService.class)
                    .setAction(MyGlassService.ACTION_TEST_NOTIFICATION));
        }
        // Debug helper: `... --ef lat 37.77 --ef lon -122.41` to feed a mock location.
        if (getIntent() != null && getIntent().hasExtra("lat")) {
            GlassLocation.update(this,
                    getIntent().getDoubleExtra("lat", 0),
                    getIntent().getDoubleExtra("lon", 0), 10f, 0L);
        }
    }
}
