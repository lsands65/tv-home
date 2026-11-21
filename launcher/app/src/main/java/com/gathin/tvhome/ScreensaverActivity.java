package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;

/** Picks what the idle screen plays, and lets a phone add photos while this screen is open. */
public final class ScreensaverActivity extends Activity implements PhotoServer.Ready {
    private static final String[] MODES = {"art", "aerial", "photos", "all"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView[] chips = new TextView[MODES.length];
    private TextView help;
    private TextView count;
    private TextView remove;
    private TextView address;
    private ImageView qr;
    private LinearLayout phone;
    private PhotoServer server;
    private String url = "";

    private final Runnable recount = new Runnable() {
        @Override public void run() {
            showCount();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(0xFF101419);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(64), dp(48), dp(64), dp(48));
        root.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.screensaver);
        title.setTextColor(Color.WHITE);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34);
        left.addView(title);

        help = new TextView(this);
        help.setTextColor(0xD9FFFFFF);
        help.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(12);
        helpLp.bottomMargin = dp(8);
        left.addView(help, helpLp);

        int[] labels = {R.string.screensaver_art, R.string.screensaver_aerials,
                R.string.screensaver_photos, R.string.screensaver_all};
        for (int i = 0; i < MODES.length; i++) {
            final String mode = MODES[i];
            TextView chip = chip(getString(labels[i]));
            chip.setOnClickListener(v -> choose(mode));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            left.addView(chip, lp);
            chips[i] = chip;
        }

        count = new TextView(this);
        count.setTextColor(0xB3FFFFFF);
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = dp(18);
        left.addView(count, countLp);

        remove = chip(getString(R.string.screensaver_remove));
        remove.setOnClickListener(v -> confirmRemove());
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT);
        removeLp.topMargin = dp(10);
        left.addView(remove, removeLp);

        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        phone = new LinearLayout(this);
        phone.setOrientation(LinearLayout.VERTICAL);
        phone.setGravity(Gravity.CENTER_HORIZONTAL);
        qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        int size = dp(280);
        phone.addView(qr, new LinearLayout.LayoutParams(size, size));
        TextView scan = new TextView(this);
        scan.setText(R.string.screensaver_scan);
        scan.setTextColor(Color.WHITE);
        scan.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        scan.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = dp(14);
        phone.addView(scan, scanLp);
        address = new TextView(this);
        address.setTextColor(0x99FFFFFF);
        address.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        address.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addressLp = new LinearLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.WRAP_CONTENT);
        addressLp.topMargin = dp(8);
        phone.addView(address, addressLp);
        root.addView(phone, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        paint();
        chips[index(Config.screensaverMode(this))].requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (wantsPhone()) openPhone();
        main.postDelayed(recount, 1000);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(recount);
        closePhone();
        super.onStop();
    }

    @Override
    public void up(String address) {
        if (isDestroyed()) return;
        runOnUiThread(() -> {
            url = address;
            this.address.setText(address);
            qr.setImageBitmap(code(address));
        });
    }

    @Override
    public void down() {
        if (isDestroyed()) return;
        runOnUiThread(() -> {
            url = "";
            qr.setImageDrawable(null);
            address.setText(R.string.screensaver_offline);
        });
    }

    private void choose(String mode) {
        getSharedPreferences(Config.SCREENSAVER_PREFS, MODE_PRIVATE).edit()
                .putString(Config.SCREENSAVER_MODE, mode).apply();
        paint();
        if (wantsPhone()) openPhone();
        else closePhone();
    }

    private void paint() {
        String mode = Config.screensaverMode(this);
        int helpId = "aerial".equals(mode) ? R.string.screensaver_aerial_help
                : "photos".equals(mode) ? R.string.screensaver_photos_help
                : "all".equals(mode) ? R.string.screensaver_all_help
                : R.string.screensaver_art_help;
        help.setText(helpId);
        phone.setVisibility(wantsPhone() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < chips.length; i++) style(chips[i], MODES[i].equals(mode));
        style(remove, false);
        showCount();
    }

    private void showCount() {
        int photos = Config.screensaverPhotoCount(this);
        count.setText(getResources().getQuantityString(R.plurals.screensaver_photo_count, photos, photos));
        remove.setVisibility(photos > 0 ? View.VISIBLE : View.GONE);
    }

    private boolean wantsPhone() {
        String mode = Config.screensaverMode(this);
        return "photos".equals(mode) || "all".equals(mode);
    }

    private void openPhone() {
        if (server != null) return;
        File folder = Config.screensaverDir(this);
        if (folder == null) {
            down();
            return;
        }
        server = new PhotoServer(folder, this);
        server.start();
    }

    private void closePhone() {
        if (server != null) {
            server.stop();
            server = null;
        }
        url = "";
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.screensaver_remove_confirm)
                .setPositiveButton(R.string.screensaver_remove, (dialog, which) -> {
                    File folder = Config.screensaverDir(this);
                    File[] files = folder == null ? null : folder.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (ThemeFiles.screensaverImage(file.getName())) file.delete();
                        }
                    }
                    showCount();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void style(TextView chip, boolean selected) {
        boolean focused = chip.isFocused();
        chip.setTextColor(focused ? 0xFF14181E : Color.WHITE);
        android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
        plate.setCornerRadius(dp(14));
        if (focused) plate.setColor(Color.WHITE);
        else if (selected) {
            plate.setColor(0x33FFFFFF);
            plate.setStroke(dp(2), Color.WHITE);
        } else plate.setColor(0x22FFFFFF);
        chip.setBackground(plate);
    }

    private TextView chip(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setOnFocusChangeListener((v, has) -> paint());
        return view;
    }

    private Bitmap code(String text) {
        try {
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512);
            Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565);
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private int index(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
