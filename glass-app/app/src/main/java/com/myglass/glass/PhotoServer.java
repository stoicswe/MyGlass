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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * A tiny HTTP server that lets the phone download the photos and videos Glass has taken
 * (from {@code /sdcard/DCIM/Camera}). It listens on {@link #PORT} and serves:
 *
 * <ul>
 *   <li>{@code GET /list} — JSON array of {@code {name,size,type}} (newest first);</li>
 *   <li>{@code GET /file/&lt;name&gt;} — the raw bytes of one item.</li>
 * </ul>
 *
 * Bitmaps over Bluetooth LE would be far too slow, so transfer happens over Wi-Fi.
 */
public class PhotoServer {

    private static final String TAG = "PhotoServer";
    public static final int PORT = 8765;

    private final File dir = new File(Environment.getExternalStorageDirectory(), "DCIM/Camera");
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread thread;

    public File directory() {
        return dir;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "myglass-photo-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        serverSocket = null;
    }

    private void loop() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.i(TAG, "Serving " + dir + " on port " + PORT);
            while (running) {
                Socket socket = serverSocket.accept();
                try {
                    handle(socket);
                } catch (Exception e) {
                    Log.w(TAG, "request failed: " + e.getMessage());
                } finally {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "server error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String requestLine = reader.readLine();
        if (requestLine == null) {
            return;
        }
        String path = "/";
        String[] parts = requestLine.split(" ");
        if (parts.length >= 2) {
            path = parts[1];
        }
        Log.i(TAG, "Request: " + requestLine);

        if (path.startsWith("/list")) {
            sendJson(socket, listJson());
        } else if (path.startsWith("/thumb/")) {
            String name = path.substring("/thumb/".length());
            int query = name.indexOf('?');
            if (query >= 0) {
                name = name.substring(0, query);
            }
            sendThumbnail(socket, URLDecoder.decode(name, "UTF-8"));
        } else if (path.startsWith("/file/")) {
            String name = path.substring("/file/".length());
            int query = name.indexOf('?');
            if (query >= 0) {
                name = name.substring(0, query);
            }
            sendFile(socket, URLDecoder.decode(name, "UTF-8"));
        } else {
            sendText(socket, "MyGlass photo server");
        }
    }

    private String listJson() {
        JSONArray array = new JSONArray();
        try {
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
                    }
                });
                for (File file : files) {
                    if (!file.isFile()) {
                        continue;
                    }
                    String lower = file.getName().toLowerCase(Locale.US);
                    boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".png");
                    boolean video = lower.endsWith(".mp4") || lower.endsWith(".3gp")
                            || lower.endsWith(".mov");
                    if (!image && !video) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("name", file.getName());
                    o.put("size", file.length());
                    o.put("type", image ? "image" : "video");
                    array.put(o);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "list failed: " + e.getMessage());
        }
        return array.toString();
    }

    private void sendFile(Socket socket, String name) throws Exception {
        // Basic path-traversal protection: names are plain file names only.
        if (name.contains("/") || name.contains("..") || name.length() == 0) {
            sendText(socket, "bad name");
            return;
        }
        File file = new File(dir, name);
        if (!file.isFile()) {
            sendText(socket, "not found");
            return;
        }
        String lower = name.toLowerCase(Locale.US);
        String contentType = lower.endsWith(".mp4") ? "video/mp4"
                : lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".mov") ? "video/quicktime"
                : "image/jpeg";

        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + file.length() + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("US-ASCII"));
        FileInputStream in = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.flush();
    }

    private void sendThumbnail(Socket socket, String name) throws Exception {
        if (name.contains("/") || name.contains("..") || name.length() == 0) {
            sendText(socket, "bad name");
            return;
        }
        File file = new File(dir, name);
        if (!file.isFile()) {
            sendText(socket, "not found");
            return;
        }
        byte[] jpeg = thumbnail(file);
        if (jpeg == null) {
            sendText(socket, "no thumb");
            return;
        }
        sendBytes(socket, "image/jpeg", jpeg);
    }

    /** Builds a small JPEG preview: a scaled image, or a frame for videos. */
    private byte[] thumbnail(File file) {
        try {
            String lower = file.getName().toLowerCase(Locale.US);
            Bitmap bitmap;
            if (lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mov")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(file.getAbsolutePath());
                bitmap = retriever.getFrameAtTime(0);
                retriever.release();
            } else {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 4;
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
            if (bitmap == null) {
                return null;
            }
            int max = 360;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            float scale = Math.min(1f, (float) max / Math.max(width, height));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, (int) (width * scale)),
                    Math.max(1, (int) (height * scale)), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "thumbnail failed: " + e.getMessage());
            return null;
        }
    }

    private void sendJson(Socket socket, String body) throws Exception {        sendBytes(socket, "application/json", body.getBytes("UTF-8"));
    }

    private void sendText(Socket socket, String body) throws Exception {
        sendBytes(socket, "text/plain", body.getBytes("UTF-8"));
    }

    private void sendBytes(Socket socket, String contentType, byte[] body) throws Exception {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }
}
