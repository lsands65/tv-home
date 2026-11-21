package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One horizontal row of the focused dock app's published programs, sitting above the app's name.
 * Up from the dock enters the first card. Left and right move between cards. Up again leaves for
 * the top bar, Down returns to the dock.
 */
final class RecRow extends LinearLayout {

    static final int PERMISSION_REQUEST = 41;
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final int MAX_CARDS = 5;

    interface Host {
        void focusDock();
        void focusTop();
        /** The row gained or lost cards. The label is not a child of this view, so the host hides it. */
        void onRowVisibility(boolean shown);
    }

    private final Activity activity;
    private final Host host;
    private final float density;
    private final boolean scenic;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "recommendations");
        thread.setDaemon(true);
        return thread;
    });
    private final LruCache<String, Bitmap> art = new LruCache<>(6 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 400);
        }
    };

    private final Runnable reload = this::reload;
    private Map<String, List<Recommendations.Card>> programs = Collections.emptyMap();
    private String shownPackage;
    private String shownLabel;
    private float heroTop;
    private boolean observing;
    private int generation;

    RecRow(Activity activity, Host host) {
        super(activity);
        this.activity = activity;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        scenic = Config.scenic(activity);
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        int side = (int) ((scenic ? 48 : 68) * density);
        setPadding(side, 0, side, 0);
        setClipChildren(false);
    }

    void start() {
        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] { PERMISSION }, PERMISSION_REQUEST);
            return;
        }
        watch();
        reload();
    }

    void reload() {
        if (activity.isDestroyed()) return;
        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return;
        watch();
        int ticket = ++generation;
        io.execute(() -> {
            Map<String, List<Recommendations.Card>> loaded =
                    Recommendations.load(activity.getContentResolver(), Config.sportsEpg(activity));
            handler.post(() -> {
                if (ticket != generation || activity.isDestroyed()) return;
                programs = loaded;
                if (shownPackage != null) bind(shownPackage, shownLabel);
            });
        });
    }

    /** Puts this app's row just above the dock name. Hides the row when the app has published nothing. */
    void show(String pkg, String label, float heroTop) {
        this.heroTop = heroTop;
        boolean same = pkg != null && pkg.equals(shownPackage);
        shownPackage = pkg;
        shownLabel = label;
        if (same) {
            place();
            return;
        }
        bind(pkg, label);
    }

    void hide() {
        shownPackage = null;
        setVisibility(GONE);
        host.onRowVisibility(false);
    }

    /** Up from the dock. True when a card took focus. */
    boolean focus() {
        if (getVisibility() != VISIBLE || getChildCount() == 0) return false;
        getChildAt(0).requestFocus();
        return true;
    }

    void close() {
        handler.removeCallbacks(reload);
        generation++;
        if (observing) {
            activity.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
        io.shutdownNow();
    }

    private void watch() {
        if (observing) return;
        try {
            activity.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
            activity.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
            observing = true;
        } catch (SecurityException ignored) { }
    }

    private void bind(String pkg, String label) {
        List<Recommendations.Card> list = pkg == null ? null : programs.get(pkg);
        removeAllViews();
        if (list == null || list.isEmpty()) {
            setVisibility(GONE);
            host.onRowVisibility(false);
            return;
        }
        int count = Math.min(scenic ? 3 : MAX_CARDS, list.size());
        for (int i = 0; i < count; i++) addView(card(list.get(i), pkg, i, count));
        setVisibility(VISIBLE);
        place();
        host.onRowVisibility(true);
    }

    private void place() {
        if (getVisibility() != VISIBLE) return;
        if (getHeight() == 0) {
            post(this::place);
            return;
        }
        // Scenic: the anchor is 8dp above the icons and the pill rises another 12dp, so 48dp here
        // leaves about 44dp of the scene between the cards and the bar. Margin, not translation:
        // the idle slide owns translation and animates it back.
        int y = Math.round(heroTop - getHeight() - (scenic ? 48 : 18) * density);
        if (getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (lp.topMargin != y) {
                lp.topMargin = y;
                setLayoutParams(lp);
            }
            return;
        }
        setY(y);
    }

    /** Wide glass row: a small picture, the app, and the title beside it. */
    private View glassCard(Recommendations.Card item, String pkg, int index, int count) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setDefaultFocusHighlightEnabled(false);
        card.setContentDescription(item.title);
        int pad = Math.round(8 * density);
        card.setPadding(pad, pad, Math.round(16 * density), pad);
        // Every card keeps a title line, an episode line and a bar, so the row does not change height.
        LayoutParams params = new LayoutParams((int) (270 * density), Math.round(88 * density));
        if (index > 0) params.setMarginStart((int) (14 * density));
        card.setLayoutParams(params);
        String headline = item.title;
        String episode = item.episode;
        int split = headline.indexOf('\n');
        if (split >= 0) {
            if (episode.isEmpty()) episode = headline.substring(split + 1).trim();
            headline = headline.substring(0, split).trim();
        }
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x33FFFFFF);
        glass.setCornerRadius(18 * density);
        card.setBackground(glass);

        ImageView thumb = new ImageView(activity);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * density);
            }
        });
        card.addView(thumb, new LayoutParams((int) (84 * density), (int) (48 * density)));
        loadArt(thumb, item.art);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(VERTICAL);
        text.setPadding(Math.round(12 * density), 0, 0, 0);
        TextView app = new TextView(activity);
        app.setText(shownLabel == null ? "" : shownLabel);
        app.setTextColor(0xB3FFFFFF);
        app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        app.setMaxLines(1);
        app.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(app);
        TextView title = new TextView(activity);
        title.setText(headline);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setMaxLines(1);
        title.setMinLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        TextView episodeView = new TextView(activity);
        episodeView.setText(episode.isEmpty() ? " " : episode);
        episodeView.setTextColor(0xC7FFFFFF);
        episodeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        episodeView.setMaxLines(1);
        episodeView.setMinLines(1);
        episodeView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        episodeView.setPadding(0, Math.round(1 * density), 0, 0);
        if (episode.isEmpty()) episodeView.setVisibility(INVISIBLE);
        text.addView(episodeView);
        if (!episode.isEmpty()) card.setContentDescription(headline + ", " + episode);
        ProgressBar bar = new ProgressBar(activity, Math.max(0f, item.progress), 2 * density);
        LayoutParams barParams = new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(3 * density));
        barParams.topMargin = Math.round(6 * density);
        if (item.progress <= 0f) bar.setVisibility(INVISIBLE);
        text.addView(bar, barParams);
        card.addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        card.setOnFocusChangeListener((v, focused) -> {
            glass.setStroke(focused ? Math.max(2, Math.round(2 * density)) : 0, 0xFFFFFFFF);
            card.invalidate();
        });
        card.setOnClickListener(v -> open(item.intentUri, pkg));
        card.setOnKeyListener((v, keyCode, event) -> onCardKey(v, index, count, keyCode, event));
        return card;
    }

    private View card(Recommendations.Card item, String pkg, int index, int count) {
        if (scenic) return glassCard(item, pkg, index, count);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setDefaultFocusHighlightEnabled(false);
        card.setContentDescription(item.title);
        LayoutParams params = new LayoutParams((int) (168 * density), LayoutParams.WRAP_CONTENT);
        if (index > 0) params.setMarginStart((int) (14 * density));
        card.setLayoutParams(params);

        FrameLayout artFrame = new FrameLayout(activity);
        ImageView poster = new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable plate = new GradientDrawable();
        plate.setColor(0xFF161616);
        plate.setCornerRadius(8 * density);
        poster.setBackground(plate);
        poster.setClipToOutline(true);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 8 * density);
            }
        });
        int gap = Math.round(4 * density);
        FrameLayout.LayoutParams posterParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        posterParams.setMargins(gap, gap, gap, gap);
        artFrame.addView(poster, posterParams);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(0x00000000);
        stroke.setCornerRadius(10 * density);
        artFrame.setBackground(stroke);
        card.addView(artFrame, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (94 * density)));
        loadArt(poster, item.art);

        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, (int) (8 * density), 0, 0);
        title.setAlpha(0.75f);
        card.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        card.setOnFocusChangeListener((v, focused) -> {
            stroke.setStroke(focused ? Math.max(2, Math.round(2 * density)) : 0, 0xFFFFFFFF);
            artFrame.invalidate();
            title.setAlpha(focused ? 1f : 0.75f);
        });
        card.setOnClickListener(v -> open(item.intentUri, pkg));
        card.setOnKeyListener((v, keyCode, event) -> onCardKey(v, index, count, keyCode, event));
        return card;
    }

    private boolean onCardKey(View card, int index, int count, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (index == 0) return true;
            getChildAt(index - 1).requestFocus();
            card.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (index == count - 1) return true;
            getChildAt(index + 1).requestFocus();
            card.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            host.focusTop();
            card.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            host.focusDock();
            card.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
            return true;
        }
        return false;
    }

    private void open(String intentUri, String pkg) {
        if (intentUri != null && !intentUri.isEmpty()) {
            try {
                Intent intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME | Intent.URI_ANDROID_APP_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            } catch (Exception ignored) {
                // The published intent did not resolve. Fall through to opening the app.
            }
        }
        Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(launch);
        } catch (ActivityNotFoundException ignored) { }
    }

    private void loadArt(ImageView poster, String uri) {
        poster.setImageDrawable(null);
        poster.setTag(uri);
        if (uri == null || uri.isEmpty()) return;
        Bitmap cached = art.get(uri);
        if (cached != null) {
            poster.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = decode(uri);
            if (bitmap == null) return;
            art.put(uri, bitmap);
            poster.post(() -> {
                if (uri.equals(poster.getTag())) poster.setImageBitmap(bitmap);
            });
        });
    }

    private Bitmap decode(String uri) {
        try {
            Uri parsed = Uri.parse(uri);
            String scheme = parsed.getScheme();
            byte[] bytes;
            if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) bytes = download(uri);
            else bytes = readStream(activity.getContentResolver().openInputStream(parsed));
            if (bytes == null || bytes.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            int target = (int) (168 * density * 2);
            while (options.inSampleSize * 2 * target < bounds.outWidth) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    /** Artwork address published with the program. Capped so one huge image cannot sit in memory. */
    private static byte[] download(String uri) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readStream(InputStream in) throws Exception {
        if (in == null) return null;
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buf)) >= 0) {
                total += n;
                if (total > 2_000_000) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** How far through the title, a white fill on a dim track. */
    private static final class ProgressBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float fraction;
        private final float radius;

        ProgressBar(android.content.Context context, float fraction, float radius) {
            super(context);
            this.fraction = fraction;
            this.radius = radius;
            track.setColor(0x40FFFFFF);
            fill.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float w = getWidth();
            canvas.drawRoundRect(0, 0, w, h, radius, radius, track);
            float ink = w * fraction;
            if (ink > 0f) canvas.drawRoundRect(0, 0, ink, h, radius, radius, fill);
        }
    }
}
