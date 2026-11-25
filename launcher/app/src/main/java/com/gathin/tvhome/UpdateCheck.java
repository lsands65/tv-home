package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Looks at the latest GitHub release of this launcher and, when its tag is a higher version
 * code, offers to install the APK. A private repo needs a token in the update-token file or
 * the theme's update_token. The tag is the version code, for example v9.
 */
final class UpdateCheck {

    private static final String TAG = "TvHome";
    private static final String PREFS = "updates";
    /** One look per process. Returning to Home does not ask GitHub again. */
    private static final AtomicBoolean LOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "update");
        thread.setDaemon(true);
        return thread;
    });

    private static AlertDialog dialog;

    private UpdateCheck() { }

    /**
     * Shows an update already found. The first time Home appears in this process, asks GitHub.
     * Later returns to Home do not ask again.
     */
    static void onHomeResumed(Activity activity) {
        present(activity);
        if (!LOOKED.compareAndSet(false, true)) return;
        if (!BUSY.compareAndSet(false, true)) return;
        IO.execute(() -> {
            try {
                look(activity.getApplicationContext());
            } finally {
                BUSY.set(false);
                activity.runOnUiThread(() -> {
                    if (!activity.isDestroyed()) present(activity);
                });
            }
        });
    }

    private static void look(Context context) {
        String repo = Config.updateRepo(context);
        String token = Config.updateToken(context);
        String url = "https://api.github.com/repos/" + repo + "/releases/latest";
        HttpURLConnection conn = null;
        try {
            conn = open(url, token, "application/vnd.github+json");
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "update check answered " + status);
                stamp(context);
                return;
            }
            String body = new String(read(conn.getInputStream(), 1_000_000), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                markChecked(context, 0, "", "");
                return;
            }
            int code = codeOf(json.optString("tag_name"));
            String asset = apkAsset(json.optJSONArray("assets"));
            String name = json.optString("name", "").trim();
            if (name.isEmpty()) name = json.optString("tag_name", "");
            if (code < 0 || asset == null) {
                Log.w(TAG, "update release has no version tag or apk");
                markChecked(context, 0, "", "");
                return;
            }
            markChecked(context, code, name, asset);
        } catch (Exception e) {
            Log.w(TAG, "update check failed: " + e.getMessage());
            stamp(context);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void present(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;
        if (activity instanceof MainActivity) {
            MainActivity home = (MainActivity) activity;
            if (home.screensaverOn() || home.somethingOpen()) return;
        }
        var prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int code = prefs.getInt("code", 0);
        int dismissed = prefs.getInt("dismissed", 0);
        String name = prefs.getString("name", "");
        String asset = prefs.getString("asset", "");
        if (code <= installed(activity) || code <= dismissed || asset == null || asset.isEmpty()) return;
        String label = name == null || name.isEmpty() ? Integer.toString(code) : name;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.update_title)
                .setMessage(activity.getString(R.string.update_ready, label))
                .setPositiveButton(R.string.update_install, (d, w) -> install(activity, asset))
                .setNegativeButton(R.string.update_later, (d, w) -> dismiss(activity, code))
                .setOnCancelListener(d -> dismiss(activity, code))
                .create();
        dialog.setOnDismissListener(d -> {
            if (dialog == d) dialog = null;
        });
        dialog.show();
    }

    private static void dismiss(Context context, int code) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("dismissed", code).apply();
    }

    private static void install(Activity activity, String asset) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_allow, Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(settings);
            } catch (Exception e) {
                Log.w(TAG, "install permission screen failed: " + e.getMessage());
            }
            return;
        }
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        String token = Config.updateToken(activity);
        IO.execute(() -> {
            File apk = new File(activity.getCacheDir(), "update.apk");
            boolean wrote = download(asset, token, apk);
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                if (!wrote) {
                    Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                commit(activity, apk);
            });
        });
    }

    private static boolean download(String asset, String token, File apk) {
        if (!asset.startsWith("https://api.github.com/repos/") || !asset.contains("/releases/assets/")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = open(asset, token, "application/octet-stream");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "update download answered " + conn.getResponseCode());
                return false;
            }
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > 80_000_000) return false;
                    out.write(buf, 0, n);
                }
            }
            return apk.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "update download failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void commit(Activity activity, File apk) {
        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            params.setSize(apk.length());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new java.io.FileInputStream(apk);
                     OutputStream out = session.openWrite("update", 0, apk.length())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent callback = new Intent(activity, StatusReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pending = PendingIntent.getBroadcast(activity, id, callback, flags);
                session.commit(pending.getIntentSender());
            }
        } catch (Exception e) {
            Log.w(TAG, "update install failed: " + e.getMessage());
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
        } finally {
            apk.delete();
        }
    }

    /** The system install confirmation comes back through here. */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Exception e) {
                    Log.w(TAG, "update confirm failed: " + e.getMessage());
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "update install status " + status);
            }
        }
    }

    private static void stamp(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("checked", System.currentTimeMillis())
                .apply();
    }

    private static void markChecked(Context context, int code, String name, String asset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("checked", System.currentTimeMillis())
                .putInt("code", code)
                .putString("name", name)
                .putString("asset", asset)
                .apply();
    }

    private static int installed(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** v9 and 9 are version code 9. Anything else is not a release this launcher understands. */
    private static int codeOf(String tag) {
        if (tag == null) return -1;
        String value = tag.trim();
        if (value.length() > 1 && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
            value = value.substring(1);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String apkAsset(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("url", "");
            if (name.endsWith(".apk") && url.startsWith("https://api.github.com/")) return url;
        }
        return null;
    }

    private static HttpURLConnection open(String url, String token, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        boolean apk = accept.startsWith("application/octet");
        conn.setReadTimeout(apk ? 120000 : 20000);
        conn.setUseCaches(!apk);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TvHome");
        conn.setRequestProperty("Accept", accept);
        if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }

    private static byte[] read(InputStream in, int max) throws Exception {
        try (InputStream input = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buf)) >= 0) {
                total += n;
                if (total > max) throw new java.io.IOException("too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
