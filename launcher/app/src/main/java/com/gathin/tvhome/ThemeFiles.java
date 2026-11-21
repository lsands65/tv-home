package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** Photos the scenic screensaver plays instead of the bundled paintings. */
    static final String SCREENSAVER = "screensaver";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);
    /** Screensaver photos may keep the spaces and parentheses a camera or a file copy leaves on the name. */
    private static final Pattern SCREENSAVER_IMAGE = Pattern.compile(
            "[A-Za-z0-9 _().+-]+\\.(jpg|jpeg|png|webp)", Pattern.CASE_INSENSITIVE);

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        if (segments.size() == 2 && SCREENSAVER.equals(segments.get(0)) && screensaverImage(segments.get(1))) {
            return SCREENSAVER + "/" + segments.get(1);
        }
        return null;
    }

    /** True for "wallpapers" or "screensaver" alone: a delete of it clears that folder's images. */
    static boolean isCollection(List<String> segments) {
        return collection(segments) != null;
    }

    /** "wallpapers" or "screensaver" when the URI names that folder and nothing under it. */
    static String collection(List<String> segments) {
        if (segments == null || segments.size() != 1) return null;
        String name = segments.get(0);
        return WALLPAPERS.equals(name) || SCREENSAVER.equals(name) ? name : null;
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }

    static boolean screensaverImage(String name) {
        return name != null && !name.startsWith(".") && !name.contains("..")
                && SCREENSAVER_IMAGE.matcher(name).matches();
    }
}
