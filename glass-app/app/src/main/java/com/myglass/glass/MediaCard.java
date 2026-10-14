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
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.widget.CardBuilder;

/**
 * A timeline LiveCard that appears while media is playing on the phone (Apple Music via
 * MusicKit), showing the track and playback state. Tapping it opens
 * {@link MediaPlaybackActivity} for play/pause, next and previous.
 */
public class MediaCard {

    private static final String TAG = "MediaCard";
    private static final String CARD_TAG = "myglass_media";

    private final Service service;
    private LiveCard card;
    private String title = "";
    private String artist = "";
    private boolean playing;

    public MediaCard(Service service) {
        this.service = service;
    }

    /** Updates the card; removes it when nothing is playing and there is no track. */
    public void update(String newTitle, String newArtist, boolean isPlaying) {
        title = newTitle != null ? newTitle : "";
        artist = newArtist != null ? newArtist : "";
        playing = isPlaying;
        if (!playing && title.isEmpty()) {
            remove();
            return;
        }
        if (card == null) {
            card = new LiveCard(service, CARD_TAG);
        }
        card.setViews(buildViews());
        Intent controls = new Intent(service, MediaPlaybackActivity.class);
        controls.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        card.setAction(PendingIntent.getActivity(service, 0, controls, 0));
        if (!card.isPublished()) {
            card.publish(LiveCard.PublishMode.REVEAL);
        }
        Log.i(TAG, "Media card: " + title + " / " + artist + " playing=" + playing);
    }

    public void remove() {
        if (card == null) {
            return;
        }
        if (card.isPublished()) {
            card.unpublish();
        }
        card = null;
        Log.i(TAG, "Media card removed");
    }

    private RemoteViews buildViews() {
        CardBuilder builder = new CardBuilder(service.getApplicationContext(),
                CardBuilder.Layout.TEXT);
        StringBuilder text = new StringBuilder(title.isEmpty() ? "Now playing" : title);
        if (!artist.isEmpty()) {
            text.append('\n').append(artist);
        }
        builder.setText(text.toString());
        builder.setFootnote("Apple Music \u00b7 " + (playing ? "Playing" : "Paused"));
        return builder.getRemoteViews();
    }
}
