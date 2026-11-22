package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A kid's home: the shows a parent added, and a PIN to get back to everyone else. */
final class KidsHome extends LinearLayout {
    private final Activity activity;
    private final View dock;
    private final View recs;

    KidsHome(Activity activity, FrameLayout root, View dock, View recs) {
        super(activity);
        this.activity = activity;
        this.dock = dock;
        this.recs = recs;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.bottomMargin = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        lp.leftMargin = Math.round(64 * activity.getResources().getDisplayMetrics().density);
        lp.rightMargin = lp.leftMargin;
        root.addView(this, lp);
        setVisibility(GONE);
    }

    boolean blocking() {
        People.Person person = People.current(activity);
        return person != null && person.kid() && People.several(activity);
    }

    void apply() {
        if (!blocking()) {
            setVisibility(GONE);
            dock.setVisibility(VISIBLE);
            return;
        }
        dock.setVisibility(GONE);
        recs.setVisibility(GONE);
        removeAllViews();
        People.Person person = People.current(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        TextView title = new TextView(activity);
        title.setText(person.name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
        addView(title);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(16 * density);
        addView(row, rowLp);
        boolean first = true;
        for (People.ShowPick show : person.shows) {
            TextView card = card(show.title);
            card.setOnClickListener(v -> launch(show.pkg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(220 * density), Math.round(88 * density));
            if (!first) lp.setMarginStart(Math.round(14 * density));
            first = false;
            row.addView(card, lp);
        }
        TextView grown = card(activity.getString(R.string.people_grownups));
        grown.setOnClickListener(v -> People.askPin(activity, person.pin, () -> {
            People.leaveKid(activity);
            if (activity instanceof MainActivity) ((MainActivity) activity).applyPeople();
        }));
        LinearLayout.LayoutParams grownLp = new LinearLayout.LayoutParams(Math.round(220 * density), Math.round(88 * density));
        if (!first) grownLp.setMarginStart(Math.round(14 * density));
        row.addView(grown, grownLp);
        setVisibility(VISIBLE);
        row.getChildAt(0).requestFocus();
    }

    void focus() {
        if (getChildCount() < 2 || !(getChildAt(1) instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) getChildAt(1);
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    private TextView card(String label) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setPadding(Math.round(16 * density()), 0, Math.round(16 * density()), 0);
        view.setOnFocusChangeListener((v, focused) -> {
            view.setTextColor(focused ? 0xFF14181E : Color.WHITE);
            android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
            plate.setCornerRadius(18 * density());
            plate.setColor(focused ? Color.WHITE : 0x33FFFFFF);
            view.setBackground(plate);
        });
        android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
        plate.setCornerRadius(18 * density());
        plate.setColor(0x33FFFFFF);
        view.setBackground(plate);
        return view;
    }

    private void launch(String pkg) {
        Intent intent = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException ignored) { }
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }
}
