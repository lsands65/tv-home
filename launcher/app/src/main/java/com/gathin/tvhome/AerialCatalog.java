package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple's published aerial catalog: a small archive whose entries.json lists the 1080p H.264
 * videos. Cached on the TV so the screensaver does not download the list every time it starts.
 */
final class AerialCatalog {

    private static final String TAG = "TvHome";
    private static final String RESOURCE =
            "https://sylvan.apple.com/itunes-assets/Aerials126/v4/c0/45/d9/c045d9d0-9606-1535-62fe-189edb4f79eb/resources-atv-23J-2.tar";
    private static final long CACHE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_BYTES = 8_000_000;

    static final class Clip {
        final String label;
        final String video;
        final String preview;

        Clip(String label, String video, String preview) {
            this.label = label;
            this.video = video;
            this.preview = preview;
        }
    }

    static List<Clip> load(Context context) throws Exception {
        File cache = new File(context.getFilesDir(), "aerial-entries.json");
        byte[] json = fresh(cache) ? readFile(cache) : null;
        if (json == null) {
            json = entriesJson(download(RESOURCE));
            if (json == null) throw new IllegalStateException("aerial catalog has no entries");
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(json);
            }
        }
        return clips(new JSONObject(new String(json, StandardCharsets.UTF_8)));
    }

    private static boolean fresh(File cache) {
        return cache.isFile() && cache.length() > 0 && System.currentTimeMillis() - cache.lastModified() < CACHE_MS;
    }

    private static List<Clip> clips(JSONObject root) {
        JSONArray assets = root.optJSONArray("assets");
        List<Clip> clips = new ArrayList<>();
        if (assets == null) return clips;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !asset.optBoolean("includeInShuffle", true)) continue;
            String video = asset.optString("url-1080-H264", "");
            if (!video.startsWith("https://")) continue;
            String label = asset.optString("accessibilityLabel", "");
            String preview = asset.optString("previewImage", "");
            if (!preview.startsWith("https://")) preview = null;
            clips.add(new Clip(label, video, preview));
        }
        Collections.shuffle(clips);
        return clips;
    }

    /** The archive is ustar: regular files and directories, names in the 100-byte header field. */
    private static byte[] entriesJson(byte[] tar) {
        int offset = 0;
        while (offset + 512 <= tar.length) {
            boolean zero = true;
            for (int i = 0; i < 512; i++) if (tar[offset + i] != 0) { zero = false; break; }
            if (zero) return null;
            String name = text(tar, offset, 100);
            long size = octal(tar, offset + 124, 12);
            int data = offset + 512;
            int padded = (int) ((size + 511) / 512 * 512);
            if (name.endsWith("entries.json") && !name.contains("._") && size > 1000 && data + size <= tar.length) {
                byte[] json = new byte[(int) size];
                System.arraycopy(tar, data, json, 0, json.length);
                return json;
            }
            offset = data + padded;
        }
        return null;
    }

    private static String text(byte[] bytes, int offset, int length) {
        int end = offset;
        int stop = Math.min(bytes.length, offset + length);
        while (end < stop && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] bytes, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            byte b = bytes[offset + i];
            if (b < '0' || b > '7') break;
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "TvHome");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalStateException("aerial catalog is too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private AerialCatalog() { }
}
