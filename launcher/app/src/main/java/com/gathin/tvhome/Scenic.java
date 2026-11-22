package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scene, the glass clock behind the landscape, and the idle fade. The dock and Keep watching
 * stay the launcher's own views. After the idle wait the chrome fades, a painting or an aerial
 * fills the screen, and the first key slides the clock down and the rows up without opening an app.
 */
final class Scenic {

    private static final String TAG = "TvHome";
    private static final String[] SCENES = {"night", "golden", "water", "fog", "dawn"};
    private static final int CROSS_MS = 1600;
    private static final int FADE_MS = 800;
    private static final int SLIDE_MS = 760;
    private static final int PARK_MS = 840;

    private final Activity activity;
    private final FrameLayout root;
    private final View grid;
    private final View recs;
    private final View glass;
    private final Allow allow;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "scenic");
        thread.setDaemon(true);
        return thread;
    });
    private String show;
    /** In All, the next advance plays one aerial instead of another still. */
    private boolean aerialTurn;
    /** The aerial playing now is one clip of All, then the stills resume. */
    private boolean oneClip;
    /** Aerials failed during this idle, so All stays on stills until the next time. */
    private boolean aerialsGaveUp;
    private final PathInterpolator slide = new PathInterpolator(0.16f, 1f, 0.3f, 1f);

    private ImageView photoA, photoB, fgA, fgB, artA, artB;
    private GlassClock clock;
    private TextView date, keep, who;
    private View scrim, topButtons;
    private TextureView aerialView;
    private TextView aerialLabel;
    private int videoWidth;
    private int videoHeight;
    private View[] fadeViews;
    private View[] topViews;
    private View[] bottomViews;

    private int scene;
    private boolean front;
    private boolean idle;
    private boolean resumed;
    private boolean swallowUp;
    private int artIndex;
    private boolean artFront;
    private List<Slide> artFiles = Collections.emptyList();
    private AerialProxy proxy;
    private MediaPlayer player;
    private Surface surface;
    private List<AerialCatalog.Clip> clips = Collections.emptyList();
    private int clip;
    private int aerialFailures;
    private int pendingClip = -1;
    private final Runnable idleTask = this::goIdle;
    private final Runnable parkTask = this::park;
    /** Translation each view had before the idle slide, so wake puts it back there. */
    private final Map<View, Float> parkedAt = new HashMap<>();

    interface Allow { boolean get(); }

    static Scenic attach(Activity activity, FrameLayout root, View grid, View recs, View glass, Allow allow) {
        if (!Config.scenic(activity)) return null;
        Scenic scenic = new Scenic(activity, root, grid, recs, glass, allow);
        scenic.build();
        return scenic;
    }

    private Scenic(Activity activity, FrameLayout root, View grid, View recs, View glass, Allow allow) {
        this.activity = activity;
        this.root = root;
        this.grid = grid;
        this.recs = recs;
        this.glass = glass;
        this.allow = allow;
        this.show = Config.screensaverMode(activity);
    }

    private void build() {
        root.setClipChildren(false);
        for (int id : new int[] {R.id.leftBlock, R.id.greeting, R.id.clock, R.id.ampm, R.id.clockDivider, R.id.date}) {
            View header = activity.findViewById(id);
            if (header != null) header.setVisibility(View.GONE);
        }
        topButtons = activity.findViewById(R.id.topButtons);
        if (topButtons.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams pills = (RelativeLayout.LayoutParams) topButtons.getLayoutParams();
            pills.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            pills.addRule(RelativeLayout.ALIGN_PARENT_END);
            pills.removeRule(RelativeLayout.ALIGN_BOTTOM);
            pills.topMargin = 0;
            pills.bottomMargin = 0;
            topButtons.setLayoutParams(pills);
        }
        topButtons.setVisibility(View.GONE);

        photoA = layer();
        photoB = layer();
        clock = new GlassClock(activity);
        fgA = layer();
        fgB = layer();
        scrim = new View(activity);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0x99000000});
        scrim.setBackground(shade);
        artA = layer();
        artB = layer();
        artA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        aerialView = new TextureView(activity);
        aerialView.setOpaque(false);
        aerialView.setAlpha(0f);
        aerialView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                surface = new Surface(surfaceTexture);
                if (pendingClip >= 0) playClip(pendingClip);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                cropAerial();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) { }
        });

        int at = 1;
        add(photoA, at++);
        add(photoB, at++);
        add(clock, at++, topClock());
        add(fgA, at++);
        add(fgB, at++);
        add(scrim, at++);
        add(artA, at++);
        add(artB, at++);
        add(aerialView, at, match());
        aerialLabel = new TextView(activity);
        aerialLabel.setTextColor(0xE6FFFFFF);
        aerialLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        aerialLabel.setShadowLayer(8, 0, 2, 0x99000000);
        aerialLabel.setAlpha(0f);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.BOTTOM);
        int labelPad = Math.round(48 * density());
        labelLp.setMargins(labelPad, labelPad, labelPad, labelPad);
        root.addView(aerialLabel, at + 1, labelLp);

        date = new TextView(activity);
        date.setTextColor(0xF2FFFFFF);
        date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        date.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        FrameLayout.LayoutParams dateLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dateLp.topMargin = Math.round(28 * density());
        root.addView(date, root.indexOfChild(glass), dateLp);

        who = new TextView(activity);
        who.setTextColor(0xF2FFFFFF);
        who.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        who.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        who.setFocusable(true);
        who.setClickable(true);
        who.setVisibility(View.GONE);
        int whoPad = Math.round(14 * density());
        who.setPadding(whoPad, Math.round(8 * density()), whoPad, Math.round(8 * density()));
        who.setOnClickListener(v -> onWho());
        who.setOnFocusChangeListener((v, focused) -> paintWho(focused));
        who.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (activity instanceof MainActivity && ((MainActivity) activity).focusPeopleDown()) return true;
            if (recs instanceof RecRow && ((RecRow) recs).focus()) return true;
            grid.requestFocus();
            return true;
        });
        FrameLayout.LayoutParams whoLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        whoLp.topMargin = Math.round(22 * density());
        whoLp.setMarginStart(Math.round(48 * density()));
        root.addView(who, whoLp);

        keep = new TextView(activity);
        keep.setText("Keep watching");
        keep.setTextColor(0xE6FFFFFF);
        keep.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        keep.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        keep.setVisibility(View.INVISIBLE);
        root.addView(keep, root.indexOfChild(glass), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        recs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeKeep());

        fadeViews = new View[] {date, who, topButtons, clock, scrim, keep, recs, grid, glass};
        topViews = new View[] {date, who, topButtons, clock};
        refreshWho();
        bottomViews = new View[] {keep, recs, grid, glass};

        photoB.setAlpha(0f);
        fgB.setAlpha(0f);
        artA.setAlpha(0f);
        artB.setAlpha(0f);
        tickClock();
        showScene(0, false);
        scheduleScene();
        artFiles = slidesFor(show);
    }

    void resume() {
        resumed = true;
        show = Config.screensaverMode(activity);
        tickClock();
        restartTimer();
        scheduleScene();
        refreshWho();
    }

    /** Up from Keep watching. True when the name took focus. */
    boolean focusWho() {
        if (who == null || who.getVisibility() != View.VISIBLE) return false;
        who.requestFocus();
        return true;
    }

    void refreshWho() {
        if (who == null) return;
        People.Person person = People.current(activity);
        if (!People.several(activity) || person == null) {
            who.setVisibility(View.GONE);
            return;
        }
        who.setText(person.name);
        who.setVisibility(View.VISIBLE);
        paintWho(who.isFocused());
    }

    private void paintWho(boolean focused) {
        who.setTextColor(focused ? 0xFF14181E : 0xF2FFFFFF);
        android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
        plate.setCornerRadius(14 * density());
        plate.setColor(focused ? 0xFFFFFFFF : 0x00000000);
        who.setBackground(plate);
    }

    private void onWho() {
        People.Person person = People.current(activity);
        if (person != null && person.kid()) {
            People.askPin(activity, person.pin, () -> {
                People.leaveKid(activity);
                refreshWho();
                if (activity instanceof MainActivity) ((MainActivity) activity).applyPeople();
            });
            return;
        }
        if (People.list(activity).size() >= 3) {
            String[] names = new String[People.list(activity).size()];
            java.util.List<People.Person> people = People.list(activity);
            for (int i = 0; i < people.size(); i++) names[i] = people.get(i).name;
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.people)
                    .setItems(names, (d, which) -> {
                        People.select(activity, people.get(which).id);
                        refreshWho();
                        if (activity instanceof MainActivity) ((MainActivity) activity).applyPeople();
                    })
                    .show();
            return;
        }
        People.cycle(activity);
        refreshWho();
        if (activity instanceof MainActivity) ((MainActivity) activity).applyPeople();
    }

    void pause() {
        resumed = false;
        main.removeCallbacks(idleTask);
        main.removeCallbacks(advanceArt);
        stopAerial();
        if (idle) snapHome();
    }

    void destroy() {
        pause();
        io.shutdownNow();
        if (proxy != null) proxy.close();
    }

    /** True when this key is the one that brings the home back and must not open an app. */
    boolean onKey(KeyEvent event) {
        int action = event.getAction();
        if (idle) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                wake();
                swallowUp = true;
            }
            return true;
        }
        if (swallowUp && action == KeyEvent.ACTION_UP) {
            swallowUp = false;
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN) restartTimer();
        return false;
    }

    private void restartTimer() {
        main.removeCallbacks(idleTask);
        show = Config.screensaverMode(activity);
        int wait = Config.idleSeconds(activity);
        if (!resumed || wait <= 0 || "off".equals(show)) return;
        main.postDelayed(idleTask, wait * 1000L);
    }

    private void scheduleScene() {
        main.removeCallbacks(this::advanceScene);
        int wait = Config.sceneSeconds(activity);
        if (wait > 0) main.postDelayed(this::advanceScene, wait * 1000L);
    }

    private void schedulePictures() {
        main.removeCallbacks(advanceArt);
        int wait = Config.pictureSeconds(activity);
        if (wait > 0) main.postDelayed(advanceArt, wait * 1000L);
    }

    boolean isIdle() {
        return idle;
    }

    /** The screensaver, now. Back pressed twice at home comes here. */
    void startNow() {
        main.removeCallbacks(idleTask);
        goIdle();
    }

    private void goIdle() {
        if (!resumed || idle || !allow.get()) {
            restartTimer();
            return;
        }
        show = Config.screensaverMode(activity);
        idle = true;
        for (View view : fadeViews) {
            if (view == null) continue;
            view.animate().cancel();
            view.animate().alpha(0f).setDuration(FADE_MS).start();
        }
        if ("aerial".equals(show)) startAerial();
        else if (!"off".equals(show)) showArt();
        main.postDelayed(parkTask, PARK_MS);
    }

    /** Slides each view off from its own translation and remembers that spot. The focus ring
     *  lives in its translation; putting every view back at 0 left Keep watching at the top. */
    private void park() {
        if (!idle) return;
        float away = root.getHeight() * 0.55f;
        for (View view : topViews) shift(view, -away);
        for (View view : bottomViews) shift(view, away);
    }

    private void shift(View view, float delta) {
        if (view == null || parkedAt.containsKey(view)) return;
        float rest = view.getTranslationY();
        parkedAt.put(view, rest);
        view.setTranslationY(rest + delta);
    }

    private void wake() {
        idle = false;
        oneClip = false;
        aerialTurn = false;
        aerialsGaveUp = false;
        main.removeCallbacks(parkTask);
        main.removeCallbacks(advanceArt);
        stopAerial();
        for (View view : topViews) rise(view);
        for (View view : bottomViews) rise(view);
        if (scrim != null) {
            scrim.animate().cancel();
            scrim.animate().alpha(1f).setDuration(520).start();
        }
        artA.animate().cancel();
        artB.animate().cancel();
        artA.animate().alpha(0f).setDuration(FADE_MS).start();
        artB.animate().alpha(0f).setDuration(FADE_MS).start();
        restartTimer();
    }

    private void rise(View view) {
        if (view == null) return;
        Float home = parkedAt.remove(view);
        float y = home == null ? view.getTranslationY() : home;
        view.animate().cancel();
        view.animate().alpha(1f).translationY(y).setDuration(SLIDE_MS).setInterpolator(slide).start();
    }

    private void snapHome() {
        idle = false;
        main.removeCallbacks(parkTask);
        for (View view : fadeViews) {
            if (view == null) continue;
            view.animate().cancel();
            view.setAlpha(1f);
            Float home = parkedAt.remove(view);
            if (home != null) view.setTranslationY(home);
        }
        artA.animate().cancel();
        artB.animate().cancel();
        artA.setAlpha(0f);
        artB.setAlpha(0f);
    }

    private void tickClock() {
        Date now = new Date();
        boolean hours12 = !DateFormat.is24HourFormat(activity);
        clock.setTime(new SimpleDateFormat(hours12 ? "h:mm" : "HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
        main.removeCallbacks(clockTick);
        long delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 200L;
        main.postDelayed(clockTick, delay);
    }

    private final Runnable clockTick = this::tickClock;

    private void advanceScene() {
        if (activity.isDestroyed()) return;
        int wait = Config.sceneSeconds(activity);
        if (wait <= 0) return;
        showScene((scene + 1) % SCENES.length, true);
        main.postDelayed(this::advanceScene, wait * 1000L);
    }

    private void showScene(int index, boolean fade) {
        scene = index;
        String name = SCENES[index];
        io.execute(() -> {
            Bitmap photo = decode("scenes/" + name + ".jpg", false, 1920);
            Bitmap fg = hasCutout(name) ? decode("scenes/" + name + "_fg.webp", true, 1920) : null;
            Bitmap glassBmp = decode("scenes/" + name + "_glass.jpg", false, 1280);
            main.post(() -> {
                if (activity.isDestroyed()) return;
                clock.setGlass(glassBmp);
                ImageView photoIn = front ? photoB : photoA;
                ImageView photoOut = front ? photoA : photoB;
                ImageView fgIn = front ? fgB : fgA;
                ImageView fgOut = front ? fgA : fgB;
                photoIn.setImageBitmap(photo);
                fgIn.setImageBitmap(fg);
                if (!fade) {
                    photoIn.setAlpha(1f);
                    photoOut.setAlpha(0f);
                    fgIn.setAlpha(fg == null ? 0f : 1f);
                    fgOut.setAlpha(0f);
                } else {
                    photoIn.setAlpha(0f);
                    fgIn.setAlpha(0f);
                    photoIn.animate().alpha(1f).setDuration(CROSS_MS).start();
                    photoOut.animate().alpha(0f).setDuration(CROSS_MS).start();
                    fgIn.animate().alpha(fg == null ? 0f : 1f).setDuration(CROSS_MS).start();
                    fgOut.animate().alpha(0f).setDuration(CROSS_MS).start();
                }
                front = !front;
            });
        });
    }

    private static boolean hasCutout(String name) {
        return !"water".equals(name);
    }

    /** Horizontal rest position. setX() stores this in translationX, which the slide also uses. */
    private float keepRestX;
    /** Slide offset added on top of {@link #keepRestX}. */
    private float keepShift;

    /** Keep watching's label rides with the row while a new shelf slides in. */
    void shiftKeep(float translationX, float alpha) {
        keepShift = translationX;
        keep.setAlpha(alpha);
        keep.setTranslationX(keepRestX + keepShift);
    }

    void placeKeep() {
        if (idle) return;
        if (recs.getVisibility() != View.VISIBLE || recs.getHeight() == 0) {
            keep.setVisibility(View.INVISIBLE);
            keep.setAlpha(1f);
            keepShift = 0f;
            return;
        }
        keep.setVisibility(View.VISIBLE);
        if (keep.getHeight() == 0) {
            keep.post(this::placeKeep);
            return;
        }
        // The slide adds keepShift on top of this. Writing the whole offset into translationX
        // left the label on the screen edge once the slide finished at 0.
        keepRestX = recs.getPaddingLeft() - keep.getLeft();
        keep.setTranslationX(keepRestX + keepShift);
        // Vertical position is a margin, so the idle slide (translationY) does not get baked in.
        int y = Math.round(recs.getTop() - keep.getHeight() - 8 * density());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keep.getLayoutParams();
        if (lp.topMargin != y) {
            lp.topMargin = y;
            keep.setLayoutParams(lp);
        }
    }

    private void showArt() {
        artFiles = slidesFor(show);
        if (artFiles.isEmpty()) return;
        artIndex = (int) (Math.random() * artFiles.size());
        artFront = false;
        aerialTurn = "all".equals(show);
        crossArt(true);
        schedulePictures();
    }

    private final Runnable advanceArt = new Runnable() {
        @Override public void run() {
            if (!idle) return;
            if ("all".equals(show) && aerialTurn && !aerialsGaveUp) {
                aerialTurn = false;
                oneClip = true;
                startAerial();
                return;
            }
            if ("all".equals(show) && !aerialsGaveUp) aerialTurn = true;
            artFiles = slidesFor(show);
            if (artFiles.isEmpty()) return;
            artIndex = (artIndex + 1) % artFiles.size();
            crossArt(true);
            schedulePictures();
        }
    };

    private void crossArt(boolean fade) {
        if (artFiles.isEmpty()) return;
        Slide slide = artFiles.get(Math.floorMod(artIndex, artFiles.size()));
        int edge = Math.max(1920, Math.max(root.getWidth(), root.getHeight()));
        io.execute(() -> {
            Bitmap bitmap = slide.file == null ? decode(slide.asset, false, edge) : decodeFile(slide.file, edge);
            main.post(() -> {
                if (!idle || bitmap == null) return;
                ImageView incoming = artFront ? artB : artA;
                ImageView outgoing = artFront ? artA : artB;
                incoming.setImageBitmap(bitmap);
                if (!fade) {
                    incoming.setAlpha(1f);
                    outgoing.setAlpha(0f);
                } else {
                    incoming.setAlpha(0f);
                    incoming.animate().alpha(1f).setDuration(FADE_MS).start();
                    outgoing.animate().alpha(0f).setDuration(FADE_MS).start();
                }
                artFront = !artFront;
            });
        });
    }

    /** Art is the paintings. Photos are the phone uploads. All plays both, and aerials between them. */
    private List<Slide> slidesFor(String mode) {
        List<Slide> slides = new ArrayList<>();
        if (!"photos".equals(mode)) slides.addAll(paintings());
        if (!"art".equals(mode)) {
            File folder = Config.screensaverDir(activity);
            File[] photos = folder == null ? null : folder.listFiles();
            if (photos != null) {
                Arrays.sort(photos, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File photo : photos) {
                    if (!photo.isFile() || photo.length() < 32 || photo.length() > 40L * 1024 * 1024) continue;
                    if (!ThemeFiles.screensaverImage(photo.getName())) continue;
                    slides.add(new Slide(photo));
                }
            }
        }
        return slides;
    }

    private List<Slide> paintings() {
        try {
            String[] names = activity.getAssets().list("art");
            List<Slide> files = new ArrayList<>();
            if (names == null) return files;
            Arrays.sort(names);
            for (String name : names) {
                if (name.endsWith(".jpg")) files.add(new Slide("art/" + name));
            }
            return files;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** A screensaver frame: a bundled asset, or a photo file from the theme folder. */
    private static final class Slide {
        final String asset;
        final File file;

        Slide(String asset) {
            this.asset = asset;
            this.file = null;
        }

        Slide(File file) {
            this.asset = null;
            this.file = file;
        }
    }

    private void startAerial() {
        try {
            if (proxy == null) proxy = new AerialProxy();
        } catch (Exception e) {
            Log.w(TAG, "aerial proxy failed: " + e.getMessage());
            if (oneClip) finishClip();
            return;
        }
        aerialView.setAlpha(0f);
        if (clips.isEmpty()) {
            io.execute(() -> {
                try {
                    List<AerialCatalog.Clip> loaded = AerialCatalog.load(activity);
                    main.post(() -> {
                        clips = loaded;
                        if (!idle) return;
                        if (clips.isEmpty() && oneClip) finishClip();
                        else playClip(0);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "aerial catalog failed: " + e.getMessage());
                    main.post(() -> { if (idle && oneClip) finishClip(); });
                }
            });
        } else {
            playClip(oneClip ? clip + 1 : clip);
        }
    }

    private void playClip(int next) {
        if (!idle || proxy == null || clips.isEmpty() || aerialFailures >= 3) {
            if (idle && oneClip) {
                if (aerialFailures >= 3) aerialsGaveUp = true;
                finishClip();
            }
            return;
        }
        if (surface == null) {
            pendingClip = next;
            return;
        }
        pendingClip = -1;
        clip = Math.floorMod(next, clips.size());
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setOnPreparedListener(mp -> {
                    aerialFailures = 0;
                    mp.setVolume(0f, 0f);
                    mp.start();
                });
                player.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && idle) {
                        aerialView.animate().alpha(1f).setDuration(FADE_MS).start();
                        aerialLabel.setText(clips.get(clip).label);
                        aerialLabel.animate().alpha(1f).setDuration(FADE_MS).start();
                    }
                    return false;
                });
                player.setOnVideoSizeChangedListener((mp, width, height) -> {
                    videoWidth = width;
                    videoHeight = height;
                    aerialView.post(this::cropAerial);
                });
                player.setOnCompletionListener(mp -> main.post(() -> {
                    if (!idle) return;
                    if (oneClip) finishClip();
                    else playClip(clip + 1);
                }));
                player.setOnErrorListener((mp, what, extra) -> {
                    aerialFailures++;
                    main.post(() -> {
                        if (!idle) return;
                        if (oneClip) {
                            if (aerialFailures >= 3) aerialsGaveUp = true;
                            finishClip();
                        } else playClip(clip + 1);
                    });
                    return true;
                });
            } else {
                player.reset();
            }
            player.setSurface(surface);
            player.setDataSource(activity, Uri.parse(proxy.urlFor(clips.get(clip).video)));
            player.prepareAsync();
        } catch (Exception e) {
            aerialFailures++;
            main.post(() -> {
                if (!idle) return;
                if (oneClip) finishClip();
                else playClip(clip + 1);
            });
        }
    }

    /** One aerial from All finished. The next still comes back, then another aerial after the wait. */
    private void finishClip() {
        oneClip = false;
        try {
            if (player != null) player.reset();
        } catch (Exception ignored) {
        }
        aerialView.animate().cancel();
        aerialView.animate().alpha(0f).setDuration(FADE_MS).start();
        aerialLabel.animate().cancel();
        aerialLabel.animate().alpha(0f).setDuration(FADE_MS).start();
        if (!idle || !"all".equals(show)) return;
        artFiles = slidesFor(show);
        if (!artFiles.isEmpty()) {
            artIndex = (artIndex + 1) % artFiles.size();
            crossArt(false);
        }
        aerialTurn = !aerialsGaveUp;
        schedulePictures();
    }

    /** Fill the screen the way Apple TV does, cropping the extra instead of letterboxing. */
    private void cropAerial() {
        if (aerialView == null || videoWidth == 0 || videoHeight == 0) return;
        int viewW = aerialView.getWidth();
        int viewH = aerialView.getHeight();
        if (viewW == 0 || viewH == 0) return;
        float viewAspect = viewW / (float) viewH;
        float videoAspect = videoWidth / (float) videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoAspect > viewAspect) scaleX = videoAspect / viewAspect;
        else scaleY = viewAspect / videoAspect;
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f);
        aerialView.setTransform(matrix);
    }

    private void stopAerial() {
        pendingClip = -1;
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
        }
        aerialView.animate().cancel();
        aerialView.setAlpha(0f);
        if (aerialLabel != null) {
            aerialLabel.animate().cancel();
            aerialLabel.setAlpha(0f);
        }
    }

    private Bitmap decode(String path, boolean alpha, int maxEdge) {
        try (InputStream in = activity.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
            options.inSampleSize = 1;
            while (options.inSampleSize * 2L * maxEdge < longEdge) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception e) {
            Log.w(TAG, "scene " + path + " " + e.getMessage());
            return null;
        }
    }

    /** A photo from the screensaver folder, sampled down to the screen and turned upright. */
    private Bitmap decodeFile(File file, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
            options.inSampleSize = 1;
            while (options.inSampleSize * 2L * maxEdge < longEdge) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return bitmap == null ? null : upright(bitmap, file);
        } catch (Exception e) {
            Log.w(TAG, "photo " + file.getName() + " " + e.getMessage());
            return null;
        }
    }

    /** Phone photos store rotation in EXIF. BitmapFactory leaves them on their side without this. */
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

    private void add(View view, int index) {
        add(view, index, match());
    }

    private void add(View view, int index, FrameLayout.LayoutParams params) {
        root.addView(view, index, params);
    }

    private ImageView layer() {
        ImageView view = new ImageView(activity);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return view;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private FrameLayout.LayoutParams topClock() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.round(230 * density()), Gravity.TOP);
        params.topMargin = Math.round(64 * density());
        return params;
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    /** Giant time filled with the scene's blurred glass, sitting behind the landscape cut-out. */
    private static final class GlassClock extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private Bitmap glass;
        private String time = "";

        GlassClock(Activity activity) {
            super(activity);
            setWillNotDraw(false);
            fill.setTextAlign(Paint.Align.CENTER);
            fill.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            frost.setTextAlign(Paint.Align.CENTER);
            frost.setTypeface(fill.getTypeface());
            frost.setColor(0x8CFFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(activity.getResources().getDisplayMetrics().density * 3f);
            stroke.setColor(0xB3FFFFFF);
            stroke.setTextAlign(Paint.Align.CENTER);
            stroke.setTypeface(fill.getTypeface());
        }

        void setGlass(Bitmap bitmap) {
            glass = bitmap;
            invalidate();
        }

        void setTime(String time) {
            this.time = time == null ? "" : time;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (time.isEmpty() || getWidth() == 0) return;
            float size = getHeight() * 0.92f;
            fill.setTextSize(size);
            while (fill.measureText(time) > getWidth() * 0.92f && size > 40f) {
                size *= 0.96f;
                fill.setTextSize(size);
            }
            stroke.setTextSize(size);
            float y = getHeight() / 2f - (fill.descent() + fill.ascent()) / 2f;
            frost.setTextSize(size);
            canvas.drawText(time, getWidth() / 2f, y, frost);
            if (glass != null) {
                BitmapShader shader = new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                matrix.setScale(getWidth() / (float) glass.getWidth(), getHeight() / (float) glass.getHeight());
                shader.setLocalMatrix(matrix);
                fill.setShader(shader);
                canvas.drawText(time, getWidth() / 2f, y, fill);
            }
            canvas.drawText(time, getWidth() / 2f, y, stroke);
        }
    }
}
