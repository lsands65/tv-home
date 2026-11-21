package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver that plays Apple's published 1080p aerial videos, one after another, while the set
 * sits idle. The picture fades up from black, and fades back to black before the home screen fades in.
 */
public class AerialDream extends DreamService {

    private static final String TAG = "TvHome";
    private static final int PREVIEW_BYTES = 2_000_000;
    static final int FADE_MS = 900;
    private static final int REVEAL_FALLBACK_MS = 8000;

    /** When the aerial finished handing the screen back, so Home can fade in. Same process. */
    static volatile long wokeAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private FrameLayout root;
    private TextureView texture;
    private ImageView preview;
    private TextView label;
    private MediaPlayer player;
    private Surface surface;
    private List<AerialCatalog.Clip> clips = Collections.emptyList();
    private AerialProxy proxy;
    private int index;
    private int pending = -1;
    private int failures;
    private int videoWidth;
    private int videoHeight;
    private boolean stopped;
    private boolean revealed;
    private boolean waking;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Drawn in the window, not on a video overlay, so the picture can fade with the home screen.
        texture = new TextureView(this);
        texture.setOpaque(false);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                surface = new Surface(surfaceTexture);
                if (pending >= 0 && !stopped) {
                    int next = pending;
                    pending = -1;
                    play(next);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                crop();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });
        root.addView(texture, match());

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(preview, match());

        label = new TextView(this);
        label.setTextColor(0xCCFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        label.setShadowLayer(8, 0, 2, 0x99000000);
        int pad = dp(48);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.gravity = Gravity.START | Gravity.BOTTOM;
        labelParams.setMargins(pad, pad, pad, pad);
        root.addView(label, labelParams);

        root.setAlpha(0f);
        setContentView(root);
        MainActivity.prepareForAerial();
    }

    @Override
    public void onWakeUp() {
        if (waking) return;
        waking = true;
        if (root == null) {
            finish();
            return;
        }
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(this::finishToHome)
                .start();
    }

    private void finishToHome() {
        wokeAt = android.os.SystemClock.uptimeMillis();
        finish();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        stopped = false;
        try {
            proxy = new AerialProxy();
        } catch (Exception e) {
            Log.w(TAG, "aerial proxy failed: " + e.getMessage());
            finish();
            return;
        }
        io = Executors.newSingleThreadExecutor();
        main.postDelayed(this::reveal, REVEAL_FALLBACK_MS);
        io.execute(() -> {
            try {
                List<AerialCatalog.Clip> loaded = AerialCatalog.load(this);
                main.post(() -> {
                    if (stopped) return;
                    clips = loaded;
                    play(0);
                });
            } catch (Exception e) {
                Log.w(TAG, "aerial catalog failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDreamingStopped() {
        stopPlayback();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopPlayback();
        super.onDetachedFromWindow();
    }

    private void play(int next) {
        if (stopped || proxy == null || clips.isEmpty() || failures >= 3) return;
        if (surface == null) {
            pending = next;
            return;
        }
        index = Math.floorMod(next, clips.size());
        AerialCatalog.Clip clip = clips.get(index);
        label.setText(clip.label);
        preview.setAlpha(1f);
        showPreview(clip.preview);
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setOnPreparedListener(mp -> {
                    failures = 0;
                    mp.setVolume(0f, 0f);
                    mp.start();
                    preview.animate().alpha(0f).setDuration(700).start();
                });
                player.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) reveal();
                    return false;
                });
                player.setOnVideoSizeChangedListener((mp, width, height) -> {
                    videoWidth = width;
                    videoHeight = height;
                    crop();
                });
                player.setOnCompletionListener(mp -> main.post(() -> play(index + 1)));
                player.setOnErrorListener((mp, what, extra) -> {
                    failures++;
                    Log.w(TAG, "aerial playback failed (" + failures + ")");
                    main.post(() -> play(index + 1));
                    return true;
                });
            } else {
                player.reset();
            }
            player.setSurface(surface);
            player.setDataSource(this, Uri.parse(proxy.urlFor(clip.video)));
            player.prepareAsync();
        } catch (Exception e) {
            failures++;
            Log.w(TAG, "aerial playback failed (" + failures + ")");
            main.post(() -> play(index + 1));
        }
    }

    private void crop() {
        if (texture == null || videoWidth == 0 || videoHeight == 0) return;
        int viewW = texture.getWidth();
        int viewH = texture.getHeight();
        if (viewW == 0 || viewH == 0) return;
        float viewAspect = viewW / (float) viewH;
        float videoAspect = videoWidth / (float) videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoAspect > viewAspect) scaleX = videoAspect / viewAspect;
        else scaleY = viewAspect / videoAspect;
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f);
        texture.setTransform(matrix);
    }

    private void showPreview(String url) {
        preview.setImageDrawable(null);
        if (url == null || io == null) return;
        io.execute(() -> {
            Bitmap bitmap = fetchPreview(url);
            if (bitmap == null) return;
            main.post(() -> {
                if (stopped) return;
                preview.setImageBitmap(bitmap);
                reveal();
            });
        });
    }

    private Bitmap fetchPreview(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "TvHome");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > PREVIEW_BYTES) return null;
                    out.write(buf, 0, n);
                }
                if (total == 0) return null;
                byte[] bytes = out.toByteArray();
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void reveal() {
        if (revealed || waking || stopped || root == null) return;
        revealed = true;
        root.animate().alpha(1f).setDuration(FADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    private void stopPlayback() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
