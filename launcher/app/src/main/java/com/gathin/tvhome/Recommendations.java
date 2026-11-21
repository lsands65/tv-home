package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Programs apps have already published for the home screen: Continue Watching (Watch Next) and each
 * app's preview row. Titles, artwork and the intent that opens the title come from the app. This
 * launcher does not fetch a catalogue of its own.
 */
final class Recommendations {

    private static final String TAG = "TvHome";
    private static final int PER_APP = 8;

    static final class Card {
        final String title;
        /** "S2 E5" when the app published both numbers, otherwise the episode name. Empty for a film. */
        final String episode;
        /** 0 hides the bar. Otherwise how far through, from the app's playback position and duration. */
        final float progress;
        final String art;
        final String intentUri;

        Card(String title, String episode, float progress, String art, String intentUri) {
            this.title = title == null ? "" : title;
            this.episode = episode == null ? "" : episode;
            this.progress = progress;
            this.art = art;
            this.intentUri = intentUri;
        }
    }

    private static final class Row {
        final String pkg;
        final String title;
        final String episode;
        final float progress;
        final String art;
        final String intentUri;
        final long weight;
        final boolean watchNext;

        Row(String pkg, String title, String episode, float progress, String art, String intentUri,
            long weight, boolean watchNext) {
            this.pkg = pkg;
            this.title = title;
            this.episode = episode;
            this.progress = progress;
            this.art = art;
            this.intentUri = intentUri;
            this.weight = weight;
            this.watchNext = watchNext;
        }
    }

    /** Package name to that app's cards. Watch Next comes before the preview row, newest or heaviest first. */
    static Map<String, List<Card>> load(ContentResolver resolver, String sportsEpg) {
        Map<Long, String> channelPackage = channels(resolver);
        Map<String, List<Row>> buckets = new LinkedHashMap<>();
        read(resolver, TvContract.WatchNextPrograms.CONTENT_URI, channelPackage, true, buckets);
        read(resolver, TvContract.PreviewPrograms.CONTENT_URI, channelPackage, false, buckets);
        Map<String, List<Card>> out = new LinkedHashMap<>();
        int programs = 0;
        for (Map.Entry<String, List<Row>> entry : buckets.entrySet()) {
            List<Row> rows = entry.getValue();
            rows.sort((a, b) -> {
                if (a.watchNext != b.watchNext) return a.watchNext ? -1 : 1;
                return Long.compare(b.weight, a.weight);
            });
            List<Card> cards = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rows.size() && cards.size() < PER_APP; i++) {
                Row row = rows.get(i);
                // Watch Next and the preview row often carry the same episode. Keep the first.
                String key = row.title.isEmpty() ? (row.art == null ? "" : row.art) : row.title;
                if (!seen.add(key)) continue;
                cards.add(new Card(row.title, row.episode, row.progress, row.art, row.intentUri));
            }
            if (!cards.isEmpty()) {
                out.put(entry.getKey(), cards);
                programs += cards.size();
            }
        }
        // TiviMate does not publish a row. A theme's sports_epg guide supplies live and upcoming games.
        List<Card> sports = SportsGuide.load(sportsEpg);
        if (!sports.isEmpty()) {
            out.put(SportsGuide.PACKAGE, sports);
            programs += sports.size();
        }
        Log.i(TAG, "recommendations: " + programs + " programs from " + out.size() + " apps");
        return out;
    }

    private static Map<Long, String> channels(ContentResolver resolver) {
        Map<Long, String> map = new HashMap<>();
        Cursor cursor = query(resolver, TvContract.Channels.CONTENT_URI);
        if (cursor == null) return map;
        try {
            int id = cursor.getColumnIndex("_id");
            int pkg = cursor.getColumnIndex("package_name");
            if (id < 0 || pkg < 0) return map;
            while (cursor.moveToNext()) {
                String name = cursor.getString(pkg);
                if (name != null && !name.isEmpty()) map.put(cursor.getLong(id), name);
            }
        } finally {
            cursor.close();
        }
        return map;
    }

    private static void read(ContentResolver resolver, Uri uri, Map<Long, String> channelPackage,
                             boolean watchNext, Map<String, List<Row>> buckets) {
        Cursor cursor = query(resolver, uri);
        if (cursor == null) return;
        try {
            int title = cursor.getColumnIndex("title");
            int episodeTitle = cursor.getColumnIndex("episode_title");
            int season = cursor.getColumnIndex("season_display_number");
            int episode = cursor.getColumnIndex("episode_display_number");
            int position = cursor.getColumnIndex("last_playback_position_millis");
            int duration = cursor.getColumnIndex("duration_millis");
            int poster = cursor.getColumnIndex("poster_art_uri");
            int thumb = cursor.getColumnIndex("thumbnail_uri");
            int intent = cursor.getColumnIndex("intent_uri");
            int browsable = cursor.getColumnIndex("browsable");
            int channel = cursor.getColumnIndex("channel_id");
            int pkgCol = cursor.getColumnIndex("package_name");
            int weight = cursor.getColumnIndex(watchNext ? "last_engagement_time_utc_millis" : "weight");
            while (cursor.moveToNext()) {
                if (browsable >= 0 && !cursor.isNull(browsable) && cursor.getInt(browsable) == 0) continue;
                String pkg = pkgCol >= 0 ? cursor.getString(pkgCol) : null;
                if ((pkg == null || pkg.isEmpty()) && channel >= 0 && !cursor.isNull(channel)) {
                    pkg = channelPackage.get(cursor.getLong(channel));
                }
                if (pkg == null || pkg.isEmpty()) continue;
                String art = poster >= 0 ? cursor.getString(poster) : null;
                if (art == null || art.isEmpty()) art = thumb >= 0 ? cursor.getString(thumb) : null;
                String name = title >= 0 ? cursor.getString(title) : "";
                if (name == null) name = "";
                String open = intent >= 0 ? cursor.getString(intent) : null;
                long rank = weight >= 0 && !cursor.isNull(weight) ? cursor.getLong(weight) : 0L;
                long pos = position >= 0 && !cursor.isNull(position) ? cursor.getLong(position) : 0L;
                long dur = duration >= 0 && !cursor.isNull(duration) ? cursor.getLong(duration) : 0L;
                float progress = dur > 0 && pos > 0 ? Math.max(0f, Math.min(1f, pos / (float) dur)) : 0f;
                String line = episodeLine(text(cursor, season), text(cursor, episode),
                        text(cursor, episodeTitle), name);
                buckets.computeIfAbsent(pkg, k -> new ArrayList<>())
                        .add(new Row(pkg, name, line, progress, art, open, rank, watchNext));
            }
        } finally {
            cursor.close();
        }
    }

    /** Season and episode when the app published both, otherwise the episode's own name. */
    private static String episodeLine(String season, String episode, String episodeTitle, String title) {
        if (!season.isEmpty() && !episode.isEmpty()) return "S" + season + " E" + episode;
        if (episodeTitle.isEmpty() || episodeTitle.equalsIgnoreCase(title)) return "";
        return episodeTitle;
    }

    private static String text(Cursor cursor, int column) {
        if (column < 0 || cursor.isNull(column)) return "";
        String value = cursor.getString(column);
        return value == null ? "" : value.trim();
    }

    private static Cursor query(ContentResolver resolver, Uri uri) {
        try {
            return resolver.query(uri, null, null, null, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "recommendations query failed for " + uri, e);
            return null;
        }
    }

    private Recommendations() { }
}
