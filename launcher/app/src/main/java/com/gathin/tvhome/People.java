package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who is on the couch. Two or more people split Keep watching. A kid gets a shelf and a PIN.
 * A guest's shows stay on their own row. This does not sign anyone into Netflix or Jellyfin.
 */
final class People {

    private static final String PREFS = "people";
    private static final String PEOPLE = "people";
    private static final String CURRENT = "current";
    private static final String WATCHES = "watches";
    private static final String SNAP = "snap";
    private static final int MAX_SHOWS = 12;

    static final class Person {
        final String id;
        final String name;
        /** Empty, "kid", or "guest". */
        final String role;
        final String pin;
        final List<ShowPick> shows;

        Person(String id, String name, String role, String pin, List<ShowPick> shows) {
            this.id = id;
            this.name = name;
            this.role = role == null ? "" : role;
            this.pin = pin == null ? "" : pin;
            this.shows = shows;
        }

        boolean kid() { return "kid".equals(role); }
        boolean guest() { return "guest".equals(role); }
    }

    static final class ShowPick {
        final String title;
        final String pkg;

        ShowPick(String title, String pkg) {
            this.title = title;
            this.pkg = pkg;
        }
    }

    private static final class Watched {
        final String pkg;
        final String title;
        final String episode;
        final float progress;
        final String art;
        final String intent;

        Watched(String pkg, String title, String episode, float progress, String art, String intent) {
            this.pkg = pkg;
            this.title = title;
            this.episode = episode == null ? "" : episode;
            this.progress = progress;
            this.art = art == null ? "" : art;
            this.intent = intent == null ? "" : intent;
        }

        Recommendations.Card card() {
            return new Recommendations.Card(title, episode, progress, art, intent);
        }
    }

    private People() { }

    static boolean several(Context ctx) {
        return list(ctx).size() >= 2;
    }

    static List<Person> list(Context ctx) {
        ensure(ctx);
        return read(ctx);
    }

    static Person current(Context ctx) {
        List<Person> people = list(ctx);
        if (people.isEmpty()) return null;
        String id = prefs(ctx).getString(CURRENT, "");
        for (Person person : people) if (person.id.equals(id)) return person;
        return people.get(0);
    }

    static void select(Context ctx, String id) {
        prefs(ctx).edit().putString(CURRENT, id).apply();
    }

    static void cycle(Context ctx) {
        List<Person> people = list(ctx);
        if (people.size() < 2) return;
        Person now = current(ctx);
        int at = 0;
        for (int i = 0; i < people.size(); i++) if (people.get(i).id.equals(now.id)) at = i;
        select(ctx, people.get((at + 1) % people.size()).id);
    }

    /** The first person who is not a kid, after a correct PIN. */
    static void leaveKid(Context ctx) {
        for (Person person : list(ctx)) {
            if (!person.kid()) {
                select(ctx, person.id);
                return;
            }
        }
    }

    static void add(Context ctx, String name) {
        List<Person> people = new ArrayList<>(list(ctx));
        String id = idFor(name, people);
        people.add(new Person(id, name.trim(), "", "", new ArrayList<>()));
        write(ctx, people);
        if (people.size() == 1) select(ctx, id);
    }

    static void rename(Context ctx, String id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        List<Person> people = new ArrayList<>();
        for (Person person : list(ctx)) {
            people.add(person.id.equals(id)
                    ? new Person(id, name.trim(), person.role, person.pin, person.shows)
                    : person);
        }
        write(ctx, people);
    }

    static void setRole(Context ctx, String id, String role) {
        List<Person> people = new ArrayList<>();
        for (Person person : list(ctx)) {
            people.add(person.id.equals(id)
                    ? new Person(id, person.name, role, person.pin, person.shows)
                    : person);
        }
        write(ctx, people);
    }

    static void setPin(Context ctx, String id, String pin) {
        List<Person> people = new ArrayList<>();
        for (Person person : list(ctx)) {
            people.add(person.id.equals(id)
                    ? new Person(id, person.name, person.role, pin, person.shows)
                    : person);
        }
        write(ctx, people);
    }

    static void addShow(Context ctx, String id, String title, String pkg) {
        if (title == null || title.trim().isEmpty() || pkg == null) return;
        List<Person> people = new ArrayList<>();
        for (Person person : list(ctx)) {
            if (!person.id.equals(id)) {
                people.add(person);
                continue;
            }
            List<ShowPick> shows = new ArrayList<>(person.shows);
            shows.add(new ShowPick(title.trim(), pkg));
            people.add(new Person(id, person.name, person.role, person.pin, shows));
        }
        write(ctx, people);
    }

    static void removeShow(Context ctx, String id, int index) {
        List<Person> people = new ArrayList<>();
        for (Person person : list(ctx)) {
            if (!person.id.equals(id)) {
                people.add(person);
                continue;
            }
            List<ShowPick> shows = new ArrayList<>(person.shows);
            if (index >= 0 && index < shows.size()) shows.remove(index);
            people.add(new Person(id, person.name, person.role, person.pin, shows));
        }
        write(ctx, people);
    }

    static void remove(Context ctx, String id) {
        List<Person> people = new ArrayList<>();
        for (Person person : list(ctx)) if (!person.id.equals(id)) people.add(person);
        write(ctx, people);
        Person now = current(ctx);
        if (now == null || now.id.equals(id)) {
            if (people.isEmpty()) prefs(ctx).edit().remove(CURRENT).apply();
            else select(ctx, people.get(0).id);
        }
        JSONObject watches = watches(ctx);
        watches.remove(id);
        prefs(ctx).edit().putString(WATCHES, watches.toString()).apply();
    }

    /** The row for this dock app: shows this person has watched, then anything nobody has claimed. */
    static List<Recommendations.Card> showing(Context ctx, String pkg, List<Recommendations.Card> live) {
        if (!several(ctx) || pkg == null) return live;
        Person person = current(ctx);
        if (person == null) return live;
        Map<String, Recommendations.Card> liveByTitle = new HashMap<>();
        if (live != null) for (Recommendations.Card card : live) liveByTitle.put(card.title, card);
        Set<String> owned = ownedTitles(ctx, pkg);
        List<Recommendations.Card> out = new ArrayList<>();
        for (Watched watched : watched(ctx, person.id)) {
            if (!pkg.equals(watched.pkg)) continue;
            Recommendations.Card newer = liveByTitle.get(watched.title);
            out.add(newer != null ? newer : watched.card());
            if (out.size() == 3) return out;
        }
        if (live != null) {
            for (Recommendations.Card card : live) {
                if (owned.contains(card.title)) continue;
                out.add(card);
                if (out.size() == 3) break;
            }
        }
        return out;
    }

    /** Opening a card claims it for whoever is watching. */
    static void claim(Context ctx, String pkg, Recommendations.Card card) {
        if (!several(ctx) || card == null || pkg == null) return;
        Person person = current(ctx);
        if (person == null || card.title.isEmpty()) return;
        remember(ctx, person.id, new Watched(pkg, card.title, card.episode, card.progress, card.art, card.intentUri));
    }

    /**
     * A show whose progress moved while this person was away from Home is theirs.
     * The first look only remembers the numbers, so the existing row is not given to whoever
     * happens to be selected.
     */
    static void notice(Context ctx, Map<String, List<Recommendations.Card>> live) {
        if (!several(ctx) || live == null) return;
        Person person = current(ctx);
        if (person == null) return;
        JSONObject next = new JSONObject();
        try {
            for (Map.Entry<String, List<Recommendations.Card>> entry : live.entrySet()) {
                for (Recommendations.Card card : entry.getValue()) {
                    if (card.title.isEmpty()) continue;
                    next.put(entry.getKey() + "\u0001" + card.title, (double) card.progress);
                }
            }
        } catch (Exception ignored) {
            return;
        }
        String previous = prefs(ctx).getString(SNAP, "");
        prefs(ctx).edit().putString(SNAP, next.toString()).apply();
        if (previous.isEmpty()) return;
        JSONObject before;
        try {
            before = new JSONObject(previous);
        } catch (Exception e) {
            return;
        }
        for (Map.Entry<String, List<Recommendations.Card>> entry : live.entrySet()) {
            for (Recommendations.Card card : entry.getValue()) {
                if (card.title.isEmpty() || card.progress <= 0.02f) continue;
                String key = entry.getKey() + "\u0001" + card.title;
                double old = before.has(key) ? before.optDouble(key, -1) : -1;
                if (old < 0 || Math.abs(card.progress - old) > 0.02f) {
                    remember(ctx, person.id, new Watched(
                            entry.getKey(), card.title, card.episode, card.progress, card.art, card.intentUri));
                }
            }
        }
    }

    static void askPin(Activity activity, String pin, Runnable correct) {
        if (pin == null || pin.isEmpty()) {
            correct.run();
            return;
        }
        StringBuilder typed = new StringBuilder();
        TextView dots = new TextView(activity);
        dots.setTextColor(0xFFFFFFFF);
        dots.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
        dots.setGravity(Gravity.CENTER);
        dots.setText("· · · ·");
        GridLayout pad = new GridLayout(activity);
        pad.setColumnCount(3);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 8));
        column.addView(dots);
        column.addView(pad);
        int[] keys = {1, 2, 3, 4, 5, 6, 7, 8, 9, -1, 0, -2};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.people_pin_title)
                .setView(column)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        for (int key : keys) {
            TextView button = new TextView(activity);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
            button.setFocusable(true);
            button.setClickable(true);
            button.setMinWidth(dp(activity, 72));
            button.setMinHeight(dp(activity, 56));
            button.setTextColor(0xFFFFFFFF);
            if (key >= 0) button.setText(Integer.toString(key));
            else if (key == -2) button.setText(activity.getString(R.string.people_pin_clear));
            else button.setText("");
            button.setOnClickListener(v -> {
                if (key == -1) return;
                if (key == -2) typed.setLength(0);
                else if (typed.length() < 4) typed.append(key);
                paintDots(dots, typed.length());
                if (typed.length() == 4) {
                    if (pin.equals(typed.toString())) {
                        dialog.dismiss();
                        correct.run();
                    } else {
                        typed.setLength(0);
                        dots.setText(activity.getString(R.string.people_pin_again));
                    }
                }
            });
            pad.addView(button);
        }
        dialog.show();
        pad.getChildAt(0).requestFocus();
    }

    private static void paintDots(TextView dots, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) text.append(' ');
            text.append(i < count ? '●' : '·');
        }
        dots.setText(text.toString());
    }

    private static void remember(Context ctx, String id, Watched watched) {
        JSONObject all = watches(ctx);
        JSONArray mine = all.optJSONArray(id);
        if (mine == null) mine = new JSONArray();
        JSONArray next = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("pkg", watched.pkg);
            item.put("title", watched.title);
            item.put("episode", watched.episode);
            item.put("progress", watched.progress);
            item.put("art", watched.art);
            item.put("intent", watched.intent);
            next.put(item);
            for (int i = 0; i < mine.length() && next.length() < MAX_SHOWS; i++) {
                JSONObject old = mine.optJSONObject(i);
                if (old == null) continue;
                if (watched.pkg.equals(old.optString("pkg")) && watched.title.equals(old.optString("title"))) continue;
                next.put(old);
            }
            all.put(id, next);
        } catch (Exception ignored) {
            return;
        }
        prefs(ctx).edit().putString(WATCHES, all.toString()).apply();
    }

    private static Set<String> ownedTitles(Context ctx, String pkg) {
        Set<String> titles = new HashSet<>();
        JSONObject all = watches(ctx);
        JSONArray names = all.names();
        if (names == null) return titles;
        for (int i = 0; i < names.length(); i++) {
            JSONArray mine = all.optJSONArray(names.optString(i));
            if (mine == null) continue;
            for (int j = 0; j < mine.length(); j++) {
                JSONObject item = mine.optJSONObject(j);
                if (item != null && pkg.equals(item.optString("pkg"))) titles.add(item.optString("title"));
            }
        }
        return titles;
    }

    private static List<Watched> watched(Context ctx, String id) {
        List<Watched> out = new ArrayList<>();
        JSONArray mine = watches(ctx).optJSONArray(id);
        if (mine == null) return out;
        for (int i = 0; i < mine.length(); i++) {
            JSONObject item = mine.optJSONObject(i);
            if (item == null) continue;
            out.add(new Watched(item.optString("pkg"), item.optString("title"), item.optString("episode"),
                    (float) item.optDouble("progress", 0), item.optString("art"), item.optString("intent")));
        }
        return out;
    }

    private static JSONObject watches(Context ctx) {
        try {
            return new JSONObject(prefs(ctx).getString(WATCHES, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void ensure(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        if (prefs.contains(PEOPLE)) return;
        JSONArray seed = Config.themePeople(ctx);
        JSONArray people = new JSONArray();
        if (seed != null) {
            for (int i = 0; i < seed.length(); i++) {
                JSONObject item = seed.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                if (name.isEmpty()) continue;
                String role = item.optBoolean("kid", false) ? "kid" : item.optBoolean("guest", false) ? "guest" : "";
                JSONObject person = new JSONObject();
                try {
                    person.put("id", idFor(name, readArray(people)));
                    person.put("name", name);
                    person.put("role", role);
                    person.put("pin", item.optString("pin", ""));
                    person.put("shows", new JSONArray());
                    people.put(person);
                } catch (Exception ignored) { }
            }
        }
        SharedPreferences.Editor editor = prefs.edit().putString(PEOPLE, people.toString());
        if (people.length() > 0) editor.putString(CURRENT, people.optJSONObject(0).optString("id"));
        editor.apply();
    }

    private static List<Person> readArray(JSONArray people) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < people.length(); i++) {
            JSONObject item = people.optJSONObject(i);
            if (item == null) continue;
            out.add(new Person(item.optString("id"), item.optString("name"), "", "", new ArrayList<>()));
        }
        return out;
    }

    private static List<Person> read(Context ctx) {
        List<Person> out = new ArrayList<>();
        try {
            JSONArray people = new JSONArray(prefs(ctx).getString(PEOPLE, "[]"));
            for (int i = 0; i < people.length(); i++) {
                JSONObject item = people.optJSONObject(i);
                if (item == null || item.optString("name").isEmpty()) continue;
                List<ShowPick> shows = new ArrayList<>();
                JSONArray listed = item.optJSONArray("shows");
                if (listed != null) {
                    for (int j = 0; j < listed.length(); j++) {
                        JSONObject show = listed.optJSONObject(j);
                        if (show == null || show.optString("title").isEmpty()) continue;
                        shows.add(new ShowPick(show.optString("title"), show.optString("pkg")));
                    }
                }
                out.add(new Person(item.optString("id"), item.optString("name"),
                        item.optString("role"), item.optString("pin"), shows));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(Context ctx, List<Person> people) {
        JSONArray array = new JSONArray();
        try {
            for (Person person : people) {
                JSONObject item = new JSONObject();
                item.put("id", person.id);
                item.put("name", person.name);
                item.put("role", person.role);
                item.put("pin", person.pin);
                JSONArray shows = new JSONArray();
                for (ShowPick show : person.shows) {
                    JSONObject one = new JSONObject();
                    one.put("title", show.title);
                    one.put("pkg", show.pkg);
                    shows.put(one);
                }
                item.put("shows", shows);
                array.put(item);
            }
        } catch (Exception ignored) {
            return;
        }
        prefs(ctx).edit().putString(PEOPLE, array.toString()).apply();
    }

    private static String idFor(String name, List<Person> people) {
        String base = name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "");
        if (base.isEmpty()) base = "person";
        String id = base;
        int n = 2;
        while (taken(people, id)) id = base + n++;
        return id;
    }

    private static boolean taken(List<Person> people, String id) {
        for (Person person : people) if (person.id.equals(id)) return true;
        return false;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
