package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screensaver of paintings scaled to fill the screen, so the sides are not left black.
 * Files live in the app's art folder. A key fades the picture out and returns to the home.
 */
public class ArtDream extends DreamService {

    private static final int HOLD_MS = 45_000;
    private static final int FADE_MS = 900;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ImageView shown;
    private ImageView incoming;
    private List<File> files = Collections.emptyList();
    private int index;
    private boolean stopped;
    private boolean waking;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (stopped || files.isEmpty()) return;
            index = (index + 1) % files.size();
            crossfade(files.get(index));
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        shown = painting();
        incoming = painting();
        incoming.setAlpha(0f);
        root.addView(shown, match());
        root.addView(incoming, match());
        root.setAlpha(0f);
        setContentView(root);
        MainActivity.prepareForAerial();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        File dir = artDir();
        File[] found = dir == null ? null : dir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            return file.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg"));
        });
        if (found == null || found.length == 0) {
            finish();
            return;
        }
        files = new ArrayList<>();
        Collections.addAll(files, found);
        Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        showFirst(files.get(0));
    }

    @Override
    public void onDreamingStopped() {
        stopped = true;
        main.removeCallbacks(advance);
        super.onDreamingStopped();
    }

    @Override
    public void onWakeUp() {
        if (waking) return;
        waking = true;
        stopped = true;
        main.removeCallbacks(advance);
        if (root == null) {
            finish();
            return;
        }
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(AerialDream.FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    AerialDream.wokeAt = android.os.SystemClock.uptimeMillis();
                    finish();
                })
                .start();
    }

    private void showFirst(File file) {
        Bitmap bitmap = decode(file);
        if (bitmap == null) {
            finish();
            return;
        }
        shown.setImageBitmap(bitmap);
        root.animate().alpha(1f).setDuration(FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        if (files.size() > 1) main.postDelayed(advance, HOLD_MS);
    }

    private void crossfade(File file) {
        Bitmap bitmap = decode(file);
        if (bitmap == null || stopped) return;
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.animate().alpha(1f).setDuration(FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (stopped) return;
                    shown.setImageBitmap(bitmap);
                    incoming.setAlpha(0f);
                    if (files.size() > 1) main.postDelayed(advance, HOLD_MS);
                })
                .start();
    }

    /** Long edge stays at the screen's long edge, so a 4K file is not decoded past what the panel draws. */
    private Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int screen = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        if (screen < 3840) screen = 3840;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (options.inSampleSize * 2 * screen < longEdge) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private ImageView painting() {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return view;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
    }

    private File artDir() {
        File base = getExternalFilesDir(null);
        if (base == null) return null;
        File dir = new File(base, "art");
        return dir.isDirectory() ? dir : null;
    }
}
