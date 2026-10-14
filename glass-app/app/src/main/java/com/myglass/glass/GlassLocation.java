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

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

/**
 * Passes the phone's location to Glass.
 *
 * <p>iOS cannot open an RFCOMM/SPP socket, so the companion streams its GPS over the
 * BLE command channel and this class feeds Glass's location providers with mock
 * locations ({@code ACCESS_MOCK_LOCATION}). Any Glass app querying GPS/network location
 * then sees the phone's position.
 */
public final class GlassLocation {

    private static final String TAG = "GlassLocation";
    private static final String FUSED_PROVIDER = "fused";

    private static boolean providersInstalled;

    private GlassLocation() {
    }

    /** Feeds a location fix from the phone into Glass's providers. */
    public static void update(Context context, double latitude, double longitude,
                              float accuracy, long timeMillis) {
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }
        try {
            if (!providersInstalled) {
                addTestProvider(locationManager, LocationManager.GPS_PROVIDER);
                addTestProvider(locationManager, LocationManager.NETWORK_PROVIDER);
                addTestProvider(locationManager, FUSED_PROVIDER);
                providersInstalled = true;
            }

            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setAccuracy(accuracy > 0 ? accuracy : 15f);
            location.setTime(timeMillis > 0 ? timeMillis : System.currentTimeMillis());
            location.setElapsedRealtimeNanos(System.nanoTime());

            set(locationManager, LocationManager.GPS_PROVIDER, location);
            set(locationManager, LocationManager.NETWORK_PROVIDER, location);
            set(locationManager, FUSED_PROVIDER, location);
            Log.i(TAG, "Phone location -> Glass: " + latitude + "," + longitude
                    + " +/-" + accuracy);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to update location", t);
        }
    }

    private static void set(LocationManager locationManager, String provider, Location location) {
        try {
            locationManager.setTestProviderLocation(provider, location);
        } catch (Throwable t) {
            // The provider may not exist / not be mockable on this build.
            Log.d(TAG, "setTestProviderLocation(" + provider + ") failed: " + t);
        }
    }

    private static void addTestProvider(LocationManager locationManager, String provider) {
        try {
            locationManager.addTestProvider(provider, false, true, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (IllegalArgumentException alreadyExists) {
            // Provider already present; we can still setTestProviderLocation on it.
        } catch (Throwable t) {
            Log.w(TAG, "addTestProvider(" + provider + ") failed: " + t);
        }
    }
}
