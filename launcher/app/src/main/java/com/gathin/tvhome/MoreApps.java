package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The dock's folder: every other launchable app, as icons, opened from the last tile. */
final class MoreApps {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "more-apps");
        thread.setDaemon(true);
        return thread;
    });

    private static final class App {
        final String label;
        final ComponentName component;
        final Drawable icon;
        final boolean leanback;

        App(String label, ComponentName component, Drawable icon, boolean leanback) {
            this.label = label;
            this.component = component;
            this.icon = icon;
            this.leanback = leanback;
        }
    }

    final PopupWindow popup;
    private final Activity activity;
    private final Runnable dismissed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final RecyclerView grid;
    private boolean closed;

    MoreApps(Activity activity, Runnable dismissed) {
        this.activity = activity;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setClipChildren(false);

        TextView title = new TextView(activity);
        title.setText(R.string.more_apps);
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        title.setPadding(dp(8), 0, dp(8), dp(12));
        panel.addView(title);

        grid = new RecyclerView(activity);
        grid.setLayoutManager(new GridLayoutManager(activity, 5));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        panel.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(118)));

        popup = new PopupWindow(panel, dp(596), dp(430), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissed.run();
        });
    }

    void show() {
        View decor = activity.getWindow().getDecorView();
        popup.showAtLocation(decor, Gravity.CENTER, 0, 0);
        View container = popup.getContentView().getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        Config cfg = Config.load(activity);
        Set<String> skip = new HashSet<>(cfg.dockApps);
        skip.addAll(cfg.hiddenApps);
        skip.add(activity.getPackageName());
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            List<App> apps = load(pm, skip);
            main.post(() -> {
                if (closed || activity.isDestroyed()) return;
                int count = Math.max(1, apps.size());
                int cols = Math.min(5, count);
                ((GridLayoutManager) grid.getLayoutManager()).setSpanCount(cols);
                int rows = (count + cols - 1) / cols;
                int gridH = Math.min(dp(360), rows * dp(118));
                int panelW = cols * dp(112) + dp(48);
                grid.getLayoutParams().height = gridH;
                grid.setAdapter(new Adapter(apps));
                popup.update(panelW, gridH + dp(72));
                grid.post(() -> {
                    RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
                    if (first != null) first.itemView.requestFocus();
                });
            });
        });
    }

    private List<App> load(PackageManager pm, Set<String> skip) {
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        Set<String> leanback = new HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (skip.contains(pkg)) continue;
                if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) leanback.add(pkg);
                if (!unique.containsKey(pkg)) unique.put(pkg, info);
            }
        }
        List<App> apps = new ArrayList<>();
        for (ResolveInfo info : unique.values()) {
            String pkg = info.activityInfo.packageName;
            String label = Cards.cleanLabel(String.valueOf(info.loadLabel(pm)), pkg);
            Drawable icon = NativeIcon.of(activity, info.loadIcon(pm));
            apps.add(new App(label, new ComponentName(pkg, info.activityInfo.name), icon, leanback.contains(pkg)));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            int byLabel = collator.compare(a.label, b.label);
            return byLabel != 0 ? byLabel : a.component.getPackageName().compareTo(b.component.getPackageName());
        });
        apps.add(0, new App(activity.getString(R.string.people),
                new ComponentName(activity, PeopleActivity.class), peopleIcon(), false));
        apps.add(0, new App(activity.getString(R.string.screensaver),
                new ComponentName(activity, ScreensaverActivity.class), photoIcon(), false));
        return apps;
    }

    /** A full-bleed clock, masked by the same rounded tile as the other apps. */
    private Drawable photoIcon() {
        int side = 256;
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF1A3148);
        canvas.drawRect(0, 0, side, side, paint);
        paint.setColor(0x22FFFFFF);
        canvas.drawCircle(side * 0.5f, side * 0.18f, side * 0.55f, paint);
        paint.setColor(0xFFF4F7FB);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(side * 0.045f);
        float cx = side * 0.5f;
        float cy = side * 0.52f;
        float radius = side * 0.24f;
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawLine(cx, cy, cx, cy - radius * 0.58f, paint);
        canvas.drawLine(cx, cy, cx + radius * 0.46f, cy + radius * 0.12f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, side * 0.032f, paint);
        return new BitmapDrawable(activity.getResources(), bitmap);
    }

    /** A full-bleed head, masked by the same rounded tile as the other apps. */
    private Drawable peopleIcon() {
        int side = 256;
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF1A3148);
        canvas.drawRect(0, 0, side, side, paint);
        paint.setColor(0x22FFFFFF);
        canvas.drawCircle(side * 0.5f, side * 0.18f, side * 0.55f, paint);
        paint.setColor(0xFFF4F7FB);
        float cx = side * 0.5f;
        canvas.drawCircle(cx, side * 0.40f, side * 0.14f, paint);
        canvas.drawCircle(cx, side * 0.78f, side * 0.22f, paint);
        return new BitmapDrawable(activity.getResources(), bitmap);
    }

    private void open(App app) {
        popup.dismiss();
        if (ScreensaverActivity.class.getName().equals(app.component.getClassName())) {
            activity.startActivity(new Intent(activity, ScreensaverActivity.class));
            return;
        }
        if (PeopleActivity.class.getName().equals(app.component.getClassName())) {
            activity.startActivity(new Intent(activity, PeopleActivity.class));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(app.component);
        if (app.leanback) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<App> apps;

        Adapter(List<App> apps) {
            this.apps = apps;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setFocusable(true);
            tile.setClickable(true);
            int gap = dp(5);
            int iconSize = dp(56);
            android.widget.FrameLayout well = new android.widget.FrameLayout(activity);
            well.setPadding(gap, gap, gap, gap);
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            float radius = 14 * density;
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            well.addView(icon, new android.widget.FrameLayout.LayoutParams(iconSize, iconSize));
            tile.addView(well, new LinearLayout.LayoutParams(iconSize + gap * 2, iconSize + gap * 2));
            TextView name = new TextView(activity);
            name.setTextColor(0xF2FFFFFF);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, dp(6), 0, 0);
            tile.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(tile, icon, well, name);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            App app = apps.get(position);
            holder.icon.setImageDrawable(app.icon);
            holder.name.setText(app.label);
            holder.itemView.setContentDescription(app.label);
            holder.itemView.setOnClickListener(v -> open(app));
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                GradientDrawable stroke = new GradientDrawable();
                stroke.setColor(0x00000000);
                stroke.setCornerRadius(18 * density);
                if (focused) stroke.setStroke(Math.max(2, dp(2)), 0xFFFFFFFF);
                holder.well.setBackground(focused ? stroke : null);
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final View well;
            final TextView name;

            Holder(View tile, ImageView icon, View well, TextView name) {
                super(tile);
                this.icon = icon;
                this.well = well;
                this.name = name;
            }
        }
    }
}
