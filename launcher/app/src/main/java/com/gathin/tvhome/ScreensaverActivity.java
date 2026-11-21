package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Picks what the idle screen plays, and lets a phone add photos while this screen is open. */
public final class ScreensaverActivity extends Activity implements PhotoServer.Ready {
    private static final String[] MODES = {"art", "aerial", "photos", "all"};
    private static final int[] IDLE_CHOICES = {30, 60, 120, 300, 600, 0};
    private static final int[] SCENE_CHOICES = {10, 20, 30, 60, 300, 0};
    private static final int[] PICTURE_CHOICES = {15, 30, 45, 60, 120, 300};
    private static final String[] TIMING_KEYS = {
            Config.IDLE_SECONDS, Config.SCENE_SECONDS, Config.PICTURE_SECONDS};
    /** Every row on this screen, so the mode buttons and the times line up. */
    private static final int ROW_WIDTH = 420;
    private static final int ROW_HEIGHT = 46;
    private static final int TILE = 176;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView[] chips = new TextView[MODES.length];
    private final LinearLayout[] timings = new LinearLayout[3];
    private final TextView[] timingNames = new TextView[3];
    private final TextView[] timingValues = new TextView[3];
    private TextView help;
    private TextView count;
    private TextView remove;
    private TextView address;
    private ImageView qr;
    private LinearLayout phone;
    private HorizontalScrollView strip;
    private LinearLayout tiles;
    private PhotoServer server;
    private String url = "";
    private String shown = "";
    private int generation;
    private final ExecutorService decode = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "screensaver-photos");
        thread.setDaemon(true);
        return thread;
    });

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
        root.setPadding(dp(64), dp(24), dp(64), dp(20));

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
                dp(ROW_WIDTH), LinearLayout.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(8);
        helpLp.bottomMargin = dp(4);
        left.addView(help, helpLp);

        int[] labels = {R.string.screensaver_art, R.string.screensaver_aerials,
                R.string.screensaver_photos, R.string.screensaver_all};
        for (int i = 0; i < MODES.length; i++) {
            final String mode = MODES[i];
            TextView chip = chip(getString(labels[i]));
            chip.setOnClickListener(v -> choose(mode));
            left.addView(chip, rowParams());
            chips[i] = chip;
        }

        addTiming(left, 0, R.string.screensaver_starts, IDLE_CHOICES);
        addTiming(left, 1, R.string.screensaver_background, SCENE_CHOICES);
        addTiming(left, 2, R.string.screensaver_pictures, PICTURE_CHOICES);

        count = new TextView(this);
        count.setTextColor(0xB3FFFFFF);
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = dp(14);
        left.addView(count, countLp);

        remove = chip(getString(R.string.screensaver_remove));
        remove.setOnClickListener(v -> confirmRemove());

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(left, new android.widget.FrameLayout.LayoutParams(
                dp(ROW_WIDTH), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(dp(ROW_WIDTH), LinearLayout.LayoutParams.MATCH_PARENT));

        phone = new LinearLayout(this);
        phone.setOrientation(LinearLayout.VERTICAL);
        phone.setGravity(Gravity.CENTER_HORIZONTAL);
        qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        int size = dp(200);
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
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER);
        phone.setGravity(Gravity.CENTER_HORIZONTAL);
        side.addView(phone, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setFillViewport(true);
        strip.setVisibility(View.GONE);
        tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.CENTER);
        strip.addView(tiles, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        stripLp.topMargin = dp(16);
        side.addView(strip, stripLp);
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(dp(220), dp(ROW_HEIGHT));
        removeLp.topMargin = dp(12);
        removeLp.bottomMargin = dp(8);
        side.addView(remove, removeLp);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        sideLp.setMarginStart(dp(28));
        root.addView(side, sideLp);

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
    protected void onDestroy() {
        generation++;
        decode.shutdownNow();
        super.onDestroy();
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
        for (int i = 0; i < timings.length; i++) {
            timings[i].setVisibility(i == 2 && "aerial".equals(mode) ? View.GONE : View.VISIBLE);
            boolean focused = timings[i].hasFocus();
            timingNames[i].setTextColor(focused ? 0xFF14181E : Color.WHITE);
            timingValues[i].setTextColor(focused ? 0xFF14181E : Color.WHITE);
            android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
            plate.setCornerRadius(dp(14));
            plate.setColor(focused ? Color.WHITE : 0x22FFFFFF);
            timings[i].setBackground(plate);
            timingValues[i].setText(duration(reading(i), i == 0));
        }
        style(remove, false);
        showCount();
    }

    private void addTiming(LinearLayout left, int index, int label, int[] choices) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setOnClickListener(v -> step(index, choices, 1));
        row.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                step(index, choices, 1);
                return true;
            }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                step(index, choices, -1);
                return true;
            }
            return false;
        });
        row.setOnFocusChangeListener((v, has) -> paint());
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(this);
        value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        value.setGravity(Gravity.END);
        row.addView(value);
        left.addView(row, rowParams());
        timings[index] = row;
        timingNames[index] = name;
        timingValues[index] = value;
    }

    private void step(int index, int[] choices, int dir) {
        int current = reading(index);
        int at = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i] == current) at = i;
        int next = choices[Math.floorMod(at + dir, choices.length)];
        getSharedPreferences(Config.SCREENSAVER_PREFS, MODE_PRIVATE).edit()
                .putInt(TIMING_KEYS[index], next).apply();
        paint();
    }

    private int reading(int index) {
        if (index == 0) return Config.idleSeconds(this);
        if (index == 1) return Config.sceneSeconds(this);
        return Config.pictureSeconds(this);
    }

    private String duration(int seconds, boolean zeroIsNever) {
        if (seconds <= 0) return getString(zeroIsNever ? R.string.screensaver_never : R.string.screensaver_hold);
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes == 1 ? getString(R.string.screensaver_minute)
                    : getString(R.string.screensaver_minutes, minutes);
        }
        return getString(R.string.screensaver_seconds, seconds);
    }

    private void showCount() {
        List<File> photos = photoFiles();
        int count = photos.size();
        this.count.setText(getResources().getQuantityString(R.plurals.screensaver_photo_count, count, count));
        remove.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        showPhotos(photos);
    }

    /** The pictures already on the TV, so a person can see what the phone sent. */
    private void showPhotos(List<File> photos) {
        String key = signature(photos);
        strip.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        if (key.equals(shown)) return;
        shown = key;
        int ticket = ++generation;
        tiles.removeAllViews();
        if (photos.isEmpty()) return;
        decode.execute(() -> {
            List<Bitmap> bitmaps = new ArrayList<>();
            for (File photo : photos) bitmaps.add(thumb(photo));
            main.post(() -> {
                if (ticket != generation || isDestroyed()) {
                    for (Bitmap bitmap : bitmaps) if (bitmap != null) bitmap.recycle();
                    return;
                }
                tiles.removeAllViews();
                int shown = 0;
                for (Bitmap bitmap : bitmaps) {
                    if (bitmap == null) continue;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE), dp(TILE));
                    if (shown > 0) lp.setMarginStart(dp(12));
                    tiles.addView(tile(bitmap), lp);
                    shown++;
                }
            });
        });
    }

    private List<File> photoFiles() {
        File folder = Config.screensaverDir(this);
        File[] files = folder == null ? null : folder.listFiles();
        List<File> photos = new ArrayList<>();
        if (files == null) return photos;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File photo : files) {
            if (photo.isFile() && photo.length() >= 32 && photo.length() <= 40L * 1024 * 1024
                    && ThemeFiles.screensaverImage(photo.getName())) photos.add(photo);
        }
        return photos;
    }

    private static String signature(List<File> photos) {
        StringBuilder key = new StringBuilder();
        for (File photo : photos) {
            key.append(photo.getName()).append(':').append(photo.length()).append(':')
                    .append(photo.lastModified()).append(';');
        }
        return key.toString();
    }

    private FrameLayout tile(Bitmap bitmap) {
        FrameLayout well = new FrameLayout(this);
        well.setPadding(dp(5), dp(5), dp(5), dp(5));
        well.setFocusable(true);
        well.setOnFocusChangeListener((v, focused) -> {
            android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
            plate.setCornerRadius(dp(18));
            plate.setColor(Color.TRANSPARENT);
            if (focused) plate.setStroke(dp(2), Color.WHITE);
            well.setBackground(plate);
        });
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
        image.setClipToOutline(true);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        well.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return well;
    }

    private Bitmap thumb(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
            int maxEdge = dp(TILE) * 2;
            options.inSampleSize = 1;
            while (options.inSampleSize * 2L * maxEdge < longEdge) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return bitmap == null ? null : upright(bitmap, file);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap upright(Bitmap bitmap, File file) {
        int orientation;
        try (InputStream in = new FileInputStream(file)) {
            orientation = new android.media.ExifInterface(in).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.preScale(-1f, 1f);
                break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.preScale(1f, -1f);
                break;
            default:
                return bitmap;
        }
        Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (turned != bitmap) bitmap.recycle();
        return turned;
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
        view.setPadding(dp(18), dp(10), dp(18), dp(10));
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

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ROW_WIDTH), dp(ROW_HEIGHT));
        lp.topMargin = dp(6);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
