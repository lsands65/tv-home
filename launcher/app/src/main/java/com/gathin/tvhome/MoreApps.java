package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(108)));

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
                int gridH = Math.min(dp(340), rows * dp(108));
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
        return apps;
    }

    private void open(App app) {
        popup.dismiss();
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
            tile.setPadding(dp(6), dp(6), dp(6), dp(6));
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            float radius = 12 * density;
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            tile.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView name = new TextView(activity);
            name.setTextColor(0xF2FFFFFF);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, dp(6), 0, 0);
            tile.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(tile, icon, name);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            App app = apps.get(position);
            holder.icon.setImageDrawable(app.icon);
            holder.name.setText(app.label);
            holder.itemView.setContentDescription(app.label);
            holder.itemView.setOnClickListener(v -> open(app));
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                v.setScaleX(focused ? 1.08f : 1f);
                v.setScaleY(focused ? 1.08f : 1f);
                GradientDrawable stroke = new GradientDrawable();
                stroke.setColor(0x00000000);
                stroke.setCornerRadius(16 * density);
                if (focused) stroke.setStroke(Math.max(2, dp(2)), 0xFFFFFFFF);
                v.setBackground(stroke);
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;

            Holder(View tile, ImageView icon, TextView name) {
                super(tile);
                this.icon = icon;
                this.name = name;
            }
        }
    }
}
