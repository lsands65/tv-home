package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Names on the couch, and a kids profile's PIN and shelf. */
public final class PeopleActivity extends Activity {
    private static final String[] ROLES = {"", "kid", "guest"};

    private LinearLayout list;
    private LinearLayout kid;
    private String selected = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(0xFF101419);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(64), dp(28), dp(64), dp(28));
        TextView title = new TextView(this);
        title.setText(R.string.people);
        title.setTextColor(Color.WHITE);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 34);
        root.addView(title);
        TextView help = new TextView(this);
        help.setText(R.string.people_help);
        help.setTextColor(0xD9FFFFFF);
        help.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(dp(640), LinearLayout.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(8);
        helpLp.bottomMargin = dp(12);
        root.addView(help, helpLp);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        kid = new LinearLayout(this);
        kid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams kidLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kidLp.topMargin = dp(18);
        root.addView(kid, kidLp);
        scroll.addView(root);
        setContentView(scroll);
        paint();
    }

    private void paint() {
        list.removeAllViews();
        List<People.Person> people = People.list(this);
        if (selected.isEmpty() && !people.isEmpty()) selected = people.get(0).id;
        boolean still = false;
        int focusAt = 0;
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).id.equals(selected)) {
                still = true;
                focusAt = i;
            }
        }
        if (!still) {
            selected = people.isEmpty() ? "" : people.get(0).id;
            focusAt = 0;
        }
        for (People.Person person : people) list.addView(personRow(person));
        TextView add = row(getString(R.string.people_add), "");
        add.setOnClickListener(v -> askName(null));
        list.addView(add, rowParams());
        if (!selected.isEmpty()) {
            TextView remove = row(getString(R.string.people_remove), "");
            remove.setOnClickListener(v -> {
                People.remove(this, selected);
                selected = "";
                paint();
            });
            list.addView(remove, rowParams());
        }
        paintKid();
        if (list.getChildCount() > 0) list.getChildAt(Math.min(focusAt, list.getChildCount() - 1)).requestFocus();
    }

    private TextView personRow(People.Person person) {
        TextView row = row(person.name, roleLabel(person.role));
        row.setOnClickListener(v -> askName(person.id));
        row.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            selected = person.id;
            int at = 0;
            for (int i = 0; i < ROLES.length; i++) if (ROLES[i].equals(person.role)) at = i;
            int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            People.setRole(this, person.id, ROLES[Math.floorMod(at + dir, ROLES.length)]);
            paint();
            return true;
        });
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused && !person.id.equals(selected)) {
                selected = person.id;
                paintKid();
            }
            style(row, focused);
        });
        return row;
    }

    private void paintKid() {
        kid.removeAllViews();
        People.Person person = null;
        for (People.Person one : People.list(this)) if (one.id.equals(selected)) person = one;
        if (person == null || !person.kid()) return;
        People.Person kidPerson = person;
        TextView pin = row(getString(R.string.people_pin), kidPerson.pin.isEmpty()
                ? getString(R.string.people_pin_none) : "●●●●");
        pin.setOnClickListener(v -> capturePin(kidPerson.id));
        kid.addView(pin, rowParams());
        for (int i = 0; i < kidPerson.shows.size(); i++) {
            People.ShowPick show = kidPerson.shows.get(i);
            int index = i;
            TextView line = row(show.title, labelFor(show.pkg));
            line.setOnClickListener(v -> {
                People.removeShow(this, kidPerson.id, index);
                paint();
            });
            kid.addView(line, rowParams());
        }
        TextView addShow = row(getString(R.string.people_add_show), "");
        addShow.setOnClickListener(v -> addShow(kidPerson.id));
        kid.addView(addShow, rowParams());
    }

    private void capturePin(String id) {
        People.askPin(this, "", () -> { });
        // askPin with an empty expected pin accepts the first four digits only when they match "".
        // Collect them with a dedicated pad instead.
        StringBuilder typed = new StringBuilder();
        TextView dots = new TextView(this);
        dots.setTextColor(Color.WHITE);
        dots.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
        dots.setGravity(Gravity.CENTER);
        dots.setText("· · · ·");
        android.widget.GridLayout pad = new android.widget.GridLayout(this);
        pad.setColumnCount(3);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(12), dp(24), dp(8));
        column.addView(dots);
        column.addView(pad);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.people_pin_set)
                .setView(column)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        for (int n = 1; n <= 9; n++) addDigit(pad, dialog, dots, typed, id, n);
        addDigit(pad, dialog, dots, typed, id, -1);
        addDigit(pad, dialog, dots, typed, id, 0);
        addDigit(pad, dialog, dots, typed, id, -2);
        dialog.show();
        pad.getChildAt(0).requestFocus();
    }

    private void addDigit(android.widget.GridLayout pad, AlertDialog dialog, TextView dots,
                          StringBuilder typed, String id, int key) {
        TextView button = new TextView(this);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        button.setTextColor(Color.WHITE);
        button.setFocusable(true);
        button.setMinWidth(dp(72));
        button.setMinHeight(dp(56));
        if (key >= 0) button.setText(Integer.toString(key));
        else if (key == -2) button.setText(R.string.people_pin_clear);
        button.setOnClickListener(v -> {
            if (key == -1) return;
            if (key == -2) typed.setLength(0);
            else if (typed.length() < 4) typed.append(key);
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) text.append(' ');
                text.append(i < typed.length() ? '●' : '·');
            }
            dots.setText(text);
            if (typed.length() == 4) {
                People.setPin(this, id, typed.toString());
                dialog.dismiss();
                paint();
            }
        });
        pad.addView(button);
    }

    private void addShow(String id) {
        askText(R.string.people_show_title, title -> {
            if (title.isEmpty()) return;
            List<ResolveInfo> apps = leanback();
            if (apps.isEmpty()) return;
            String[] labels = new String[apps.size()];
            for (int i = 0; i < apps.size(); i++) labels[i] = String.valueOf(apps.get(i).loadLabel(getPackageManager()));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.people_show_app)
                    .setItems(labels, (d, which) -> {
                        People.addShow(this, id, title, apps.get(which).activityInfo.packageName);
                        paint();
                    })
                    .show();
        });
    }

    private List<ResolveInfo> leanback() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo info : apps) {
            if (info.activityInfo == null) continue;
            if (getPackageName().equals(info.activityInfo.packageName)) continue;
            out.add(info);
        }
        return out;
    }

    private void askName(String id) {
        People.Person person = null;
        if (id != null) {
            for (People.Person one : People.list(this)) if (one.id.equals(id)) person = one;
        }
        String start = person == null ? "" : person.name;
        askText(person == null ? R.string.people_add : R.string.people_rename, text -> {
            if (text.isEmpty()) return;
            if (id == null) People.add(this, text);
            else {
                People.rename(this, id, text);
                selected = id;
            }
            paint();
        }, start);
    }

    private void askText(int title, java.util.function.Consumer<String> done) {
        askText(title, done, "");
    }

    private void askText(int title, java.util.function.Consumer<String> done, String start) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setText(start);
        field.setSelection(field.getText().length());
        field.setTextColor(Color.BLACK);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> done.accept(field.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        field.requestFocus();
    }

    private String roleLabel(String role) {
        if ("kid".equals(role)) return getString(R.string.people_kid);
        if ("guest".equals(role)) return getString(R.string.people_guest);
        return getString(R.string.people_grown);
    }

    private String labelFor(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private TextView row(String name, String value) {
        TextView view = new TextView(this);
        view.setText(value == null || value.isEmpty() ? name : name + "      " + value);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setFocusable(true);
        view.setClickable(true);
        view.setPadding(dp(18), 0, dp(18), 0);
        view.setMinHeight(dp(46));
        view.setOnFocusChangeListener((v, focused) -> style(view, focused));
        style(view, false);
        return view;
    }

    private void style(TextView view, boolean focused) {
        view.setTextColor(focused ? 0xFF14181E : Color.WHITE);
        android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
        plate.setCornerRadius(dp(14));
        plate.setColor(focused ? Color.WHITE : 0x22FFFFFF);
        view.setBackground(plate);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(520), dp(46));
        lp.topMargin = dp(8);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
