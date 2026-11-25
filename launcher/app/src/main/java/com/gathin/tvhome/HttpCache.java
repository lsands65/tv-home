package com.gathin.tvhome;

import android.content.Context;
import android.net.http.HttpResponseCache;
import android.util.Log;

import java.io.File;

/**
 * A small disk cache under every HttpURLConnection in the process, so artwork and the release check
 * survive the launcher being killed for memory. Large downloads (videos, the sports guide, the aerial
 * archive, the update APK) turn it off with setUseCaches(false) so they do not push everything else out.
 */
final class HttpCache {
    private static final long SIZE = 10L * 1024 * 1024;

    private HttpCache() { }

    static void install(Context context) {
        if (HttpResponseCache.getInstalled() != null) return;
        try {
            HttpResponseCache.install(new File(context.getCacheDir(), "http"), SIZE);
        } catch (Exception e) {
            Log.w("TvHome", "http cache " + e.getMessage());
        }
    }
}
