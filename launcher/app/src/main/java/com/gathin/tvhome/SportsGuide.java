package com.gathin.tvhome;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live and upcoming games from an XMLTV guide (a theme's sports_epg). TiviMate never publishes a
 * recommendation row of its own. Studio shows, replays, and the guide's "Playing at …" placeholders
 * are left out. Wisconsin teams are listed first, and a Sunday game still shows on Saturday.
 * The guide is fetched at most every few minutes; which games are current is decided when the row is drawn.
 */
final class SportsGuide {

    static final String PACKAGE = "ar.tvplayer.tv";

    private static final String TAG = "TvHome";
    private static final long CACHE_MS = 15 * 60 * 1000L;
    private static final long UPCOMING_MS = 12 * 60 * 60 * 1000L;
    /** Far enough ahead that a Sunday kickoff still shows on Saturday. */
    private static final long WISCONSIN_MS = 36 * 60 * 60 * 1000L;
    private static final String[] WISCONSIN = {
            "wisconsin", "badgers", "packers", "brewers", "bucks", "marquette", "green bay", "admirals"
    };
    private static final int MAX_BYTES = 20_000_000;
    private static final int MAX_CARDS = 8;

    private static final Set<String> SPORT = new HashSet<>(Arrays.asList(
            "sports", "football", "basketball", "baseball", "hockey", "soccer", "golf", "tennis",
            "boxing", "volleyball", "cricket", "auto racing", "motorsports", "motorcycle racing",
            "horse racing", "boat racing", "playoff sports", "multi-sport event", "classic sport event",
            "pro wrestling", "australian rules football", "field hockey", "esports", "watersports",
            "action sports", "table tennis"));
    private static final Set<String> SKIP_CATEGORY = new HashSet<>(Arrays.asList(
            "sports talk", "documentary", "news", "talk"));

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault());

    private static final class Channel {
        final String name;
        final String art;

        Channel(String name, String art) {
            this.name = name;
            this.art = art;
        }
    }

    private static final class Game {
        final long start;
        final long stop;
        final String title;
        final String channel;
        final String art;
        final boolean matchup;
        final boolean wisconsin;

        Game(long start, long stop, String title, String channel, String art, boolean matchup, boolean wisconsin) {
            this.start = start;
            this.stop = stop;
            this.title = title;
            this.channel = channel;
            this.art = art;
            this.matchup = matchup;
            this.wisconsin = wisconsin;
        }
    }

    private static List<Game> cached = Collections.emptyList();
    private static String cachedUrl = "";
    private static long cachedAt;

    static List<Recommendations.Card> load(String url) {
        if (url == null || url.isEmpty()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        if (url.equals(cachedUrl) && now - cachedAt < CACHE_MS) return cards(cached, now);
        try {
            List<Game> games = fetch(url, now);
            cached = games;
            cachedUrl = url;
            cachedAt = now;
            Log.w(TAG, "sports guide: " + games.size() + " games");
            return cards(games, now);
        } catch (Exception e) {
            Log.w(TAG, "sports guide failed: " + e.getMessage());
            return url.equals(cachedUrl) ? cards(cached, now) : Collections.emptyList();
        }
    }

    private static List<Recommendations.Card> cards(List<Game> games, long now) {
        List<Game> picked = new ArrayList<>();
        for (Game game : games) {
            boolean live = game.start <= now && now < game.stop;
            long horizon = game.wisconsin ? WISCONSIN_MS : UPCOMING_MS;
            boolean upcoming = now <= game.start && game.start < now + horizon;
            if (live || upcoming) picked.add(game);
        }
        picked.sort((a, b) -> {
            if (a.wisconsin != b.wisconsin) return a.wisconsin ? -1 : 1;
            boolean aLive = a.start <= now;
            boolean bLive = b.start <= now;
            if (aLive != bLive) return aLive ? -1 : 1;
            int byTime = aLive ? Long.compare(b.start, a.start) : Long.compare(a.start, b.start);
            if (byTime != 0) return byTime;
            if (a.matchup != b.matchup) return a.matchup ? -1 : 1;
            return a.title.compareToIgnoreCase(b.title);
        });
        List<Recommendations.Card> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Game game : picked) {
            if (cards.size() >= MAX_CARDS) break;
            String key = game.title.toLowerCase(Locale.ROOT).replaceFirst("^live:\\s*", "");
            if (!seen.add(key)) continue;
            boolean live = game.start <= now && now < game.stop;
            String when = live ? "Live" : CLOCK.format(Instant.ofEpochMilli(game.start).atZone(ZoneId.systemDefault()));
            String headline = game.title.replaceFirst("(?i)^Live:\\s*", "");
            cards.add(new Recommendations.Card(
                    game.channel + "  ·  " + when + "\n" + headline, "", 0f, game.art, null));
        }
        return cards;
    }

    private static List<Game> fetch(String url, long now) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "TvHome");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            try (InputStream in = new Limited(conn.getInputStream(), MAX_BYTES)) {
                return parse(in, now);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static List<Game> parse(InputStream in, long now) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, "UTF-8");
        Map<String, Channel> channels = new HashMap<>();
        List<Game> games = new ArrayList<>();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if ("channel".equals(parser.getName())) readChannel(parser, channels);
                else if ("programme".equals(parser.getName())) readProgramme(parser, channels, games, now);
            }
            event = parser.next();
        }
        return games;
    }

    private static void readChannel(XmlPullParser parser, Map<String, Channel> channels) throws Exception {
        String id = parser.getAttributeValue(null, "id");
        String name = "";
        String art = null;
        while (parser.next() != XmlPullParser.END_TAG || !"channel".equals(parser.getName())) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if ("display-name".equals(parser.getName()) && name.isEmpty()) name = text(parser);
            else if ("icon".equals(parser.getName()) && art == null) art = http(parser.getAttributeValue(null, "src"));
        }
        if (id != null) {
            int paren = name.indexOf(" (");
            if (paren > 0) name = name.substring(0, paren);
            channels.put(id, new Channel(name, art));
        }
    }

    private static void readProgramme(XmlPullParser parser, Map<String, Channel> channels,
                                      List<Game> games, long now) throws Exception {
        long start = xmltvTime(parser.getAttributeValue(null, "start"));
        long stop = xmltvTime(parser.getAttributeValue(null, "stop"));
        String channelId = parser.getAttributeValue(null, "channel");
        String title = "";
        String art = null;
        Set<String> categories = new HashSet<>();
        while (parser.next() != XmlPullParser.END_TAG || !"programme".equals(parser.getName())) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if ("title".equals(tag) && title.isEmpty()) title = text(parser);
            else if ("category".equals(tag)) categories.add(text(parser).toLowerCase(Locale.ROOT));
            else if ("icon".equals(tag) && art == null) art = http(parser.getAttributeValue(null, "src"));
        }
        String lower = title.toLowerCase(Locale.ROOT);
        boolean home = wisconsin(lower);
        long horizon = home ? WISCONSIN_MS : UPCOMING_MS;
        if (start == 0 || stop <= now || start >= now + horizon) return;
        if (Collections.disjoint(categories, SPORT) || !Collections.disjoint(categories, SKIP_CATEGORY)) return;
        if (lower.contains(" - playing ") || lower.contains("highlight") || lower.contains("replay")
                || lower.contains("on the fly") || lower.contains("gameday")) return;
        boolean matchup = lower.contains(" vs ") || lower.contains(" vs. ");
        boolean listed = lower.startsWith("live:") && !studio(lower);
        if (!matchup && !listed) return;
        Channel channel = channels.get(channelId);
        String name = channel != null && !channel.name.isEmpty() ? channel.name : "Sports";
        if (art == null && channel != null) art = channel.art;
        games.add(new Game(start, stop, title, name, art, matchup, home));
    }

    /** A Wisconsin team in the title. Word boundaries, so "bucks" does not match inside another word. */
    private static boolean wisconsin(String lower) {
        for (String word : WISCONSIN) {
            int from = 0;
            while (from <= lower.length() - word.length()) {
                int at = lower.indexOf(word, from);
                if (at < 0) break;
                int end = at + word.length();
                boolean before = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
                boolean after = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
                if (before && after) return true;
                from = at + 1;
            }
        }
        return false;
    }

    /** A "Live:" title that is a desk show rather than the event itself. */
    private static boolean studio(String lower) {
        return lower.contains("pregame") || lower.contains("postgame") || lower.contains("halftime")
                || lower.contains("showtime") || lower.contains("gameday");
    }

    private static String text(XmlPullParser parser) throws Exception {
        String value = parser.nextText();
        return value == null ? "" : value.trim();
    }

    /** Guide artwork is often an http address. The same file is served over https, which the TV allows. */
    private static String http(String uri) {
        if (uri == null) return null;
        if (uri.regionMatches(true, 0, "http://", 0, 7)) uri = "https://" + uri.substring(7);
        return uri.regionMatches(true, 0, "https://", 0, 8) ? uri : null;
    }

    /** XMLTV time, {@code 20261024170000 +0000}. Zero when it does not parse. */
    private static long xmltvTime(String value) {
        if (value == null) return 0;
        try {
            String[] parts = value.trim().split(" ");
            LocalDateTime time = LocalDateTime.parse(parts[0], DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String offset = parts.length > 1 ? parts[1] : "Z";
            if (offset.length() == 5 && (offset.charAt(0) == '+' || offset.charAt(0) == '-')) {
                offset = offset.substring(0, 3) + ":" + offset.substring(3);
            }
            return time.toInstant(ZoneOffset.of(offset)).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static final class Limited extends FilterInputStream {
        private int left;

        Limited(InputStream in, int max) {
            super(in);
            left = max;
        }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(buffer, offset, Math.min(length, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    private SportsGuide() { }
}
