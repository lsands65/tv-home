# Scenic home

The living-room home is the CleanYourTV launcher (`com.gathin.tvhome`) with the Couch TV night
composition drawn in the app itself. It is not the Couch TV website, and it is not a WebView.
`scenic: true` in a theme turns it on. The dock, Keep watching, and the sports row stay the
launcher's own views.

It was built for a Hisense 75U75N (`hengshan`, Google TV 12). The look itself is the theme plus
this launcher build. The Hisense rules at the end are specific to that set.

## What you see

A landscape fills the screen. A large frosted clock sits in the sky, and the foreground (the
treeline, the peak) is drawn over the lower part of the digits. The date is centered above the
clock. There is no greeting and no top-right Inputs, network, or Settings pills.

Keep watching sits low, as glass cards of one fixed height: a thumbnail, the app name, the
title, the episode (`S4 E10`, or the episode name when the app did not publish both numbers),
and a white bar for how far through the title is. A card with no episode or no position keeps
those slots empty, so the row does not grow and shrink. Up to three cards. The row is the
focused dock app's own recommendations, and the heading goes away with it when that app has
published nothing. OK opens that title.

The dock is a short pill of the apps' own icons, centered, with a small gap. It grows and
shrinks with the number of icons, up to about 680dp, and the icons shrink (down to about 40dp)
if a full row would be wider than that. Settings is one of the icons. The last icon is a More
apps folder: everything installed that is not already on the dock and not hidden. OK on a folder
icon launches it. The folder is not the App library; the library's OK adds an app to Home.

After `idle_seconds` with no key, the chrome fades out and a painting or an aerial fills the
screen. Pressing Back twice within two seconds, while nothing else is open, starts it
immediately. The first key brings the clock down from above and the dock up from below. It does
not open an app.

## Build and install

The scenic home is code in this tree (`Scenic`, `NativeIcon`, `MoreApps`, the scenic branch of
the dock and of `RecRow`). A stock CleanYourTV APK will ignore `scenic` and the idle keys.
Build this tree.

Use JDK 17. Gradle 8.9 does not build on JDK 25. `ANDROID_HOME` is the Android SDK.

```powershell
cd launcher
$env:JAVA_HOME = '<jdk 17>'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleRelease --console=plain
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 install -r app\build\outputs\apk\release\app-release.apk
adb -s <tv-ip>:5555 shell am force-stop com.gathin.tvhome
adb -s <tv-ip>:5555 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity
```

`pm install -r` keeps the theme file already on the TV. Push the theme after install, or any
time the JSON changes. Pushing restarts Home, which is what refreshes the cached theme:

```powershell
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv-ip> themes\living-room
```

If `classes.dex` is locked during the build, stop the Gradle daemons (`.\gradlew.bat --stop`
from `launcher\`) and build again. Stream the APK with `adb install`. The base64 upload in
`build-and-install.ps1` times out on this APK.

Copy `themes/living-room/config.json` and change `dock_apps` before you push it to a different
TV. `sports_epg` in this repo is empty. A household's guide address stays on that TV and is
not committed. The install steps for another set are in [install.md](install.md).

## Theme

`themes/living-room/config.json` is the working example. These keys matter for the scenic home.
Anything else is an ordinary CleanYourTV theme key (`themes/README.md`).

| Key | Living room | What it does |
|---|---|---|
| `scenic` | `true` | Scene, glass clock, packed dock, glass Keep watching, idle fade |
| `dock_apps` | the apps, in order | Dock icons. Settings is `com.android.tv.settings` so the top-right Settings pill can go |
| `columns` | `6` | At least the dock count. Scenic then adds one More apps tile and uses that many columns |
| `rows_below_dock` | `0` | No grid row under the dock. Those apps live in the folder |
| `dock_title` | `false` | No giant app name above the dock |
| `show_greeting` | `false` | No greeting over the scene |
| `show_network_pill`, `show_inputs_pill` | `false` | Top-right pills removed. Scenic hides that bar anyway |
| `uniform_tiles` | `true` | Banners stay off. Scenic draws each app's own icon full-bleed instead of a tinted plate |
| `focus_overshoot` | `false` | Focus settles without the bounce |
| `ambient_glow` | `false` | No full-screen tint in the focused app's colour. Left on, it washes the landscape |
| `app_glow` | `false` | The focus ring stays white instead of taking the app's colour |
| `dock_opacity` | `62` | The dock pill |
| `idle_show` | `art`, `aerial`, or `off` | What covers the home after the wait |
| `idle_seconds` | `60` | Wait before that cover. `0` never idles |
| `sports_epg` | an XMLTV URL | Games for the TiviMate tile. Empty leaves that row empty |
| `wallpaper` | `false` | The scene is the background |
| `hidden_apps` | packages to leave out | Hidden from the dock and from the More apps folder. Not uninstalled |

Changing `dock_apps` drops a saved icon order, because the saved order is keyed by that list.

The scenic dock does not get its spacing from grid padding. `GridLayoutManager` keeps wide
columns, and the launcher slides the icons together after layout (about 64dp icons, 8dp apart).

## Keep watching

Cards come from Android's Watch Next and preview programs (`READ_TV_LISTINGS`). The scenic card
reads `season_display_number`, `episode_display_number`, and `episode_title`, and draws
`last_playback_position_millis / duration_millis` as the bar. No position or no duration leaves
the bar off. A film with no episode is just the title. Up from the dock enters the row. Up again
does nothing, because the top bar is gone.

## Sports

TiviMate does not publish a recommendation row. With the TiviMate tile focused, the row is live
and upcoming sport from the `sports_epg` XMLTV file. The guide is cached for 15 minutes. OK opens
TiviMate. It does not tune a channel.

The filter is in `SportsGuide.java`, not the theme. It keeps matchups and titles that start with
`Live:`, skips studio shows, talk, news, documentaries, highlights, replays, and "Playing at"
placeholders, and lists Wisconsin teams first (Badgers, Packers, Brewers, Bucks, Marquette,
Admirals, and "Wisconsin" or "Green Bay" in the title). Everyone else is shown for 12 hours
ahead. Wisconsin games are shown for 36 hours, so a Sunday kickoff is still there on Saturday.
Change the `WISCONSIN` list for another region. A channel named Green Bay is not treated as a
Wisconsin game.

## Idle

Art is the landscapes in `launcher/app/src/main/assets/art/`. They are National Gallery of Art
open-access images, fetched at the gallery's 4096px cap and cropped to fill the screen
(`CENTER_CROP`). They cycle about every 45 seconds. Credits are in
`launcher/app/src/main/assets/art/CREDITS.md`. 1080px crops are too soft on this panel; do not
swap in the Couch TV art pack or the Art Institute's public 1686px cap.

More apps has Screensaver. It chooses Art, Aerials, Photos, or All, and it sets the wait
before the idle screen, how often the home landscape changes, and how often a painting or
photo changes. Those choices stay on the TV and win over the theme. Defaults are 1 minute,
20 seconds, and 45 seconds. Hold keeps the current landscape or picture. Never means the home
does not idle on its own. Double Back still starts it.

Art is the paintings. Aerials are Apple's TV aerials: the published 1080p landscape films,
full screen, with the place name in the corner. Each one plays through, then the next.
Photos are pictures from a phone. All takes turns: a painting or a photo, then one of those
films, then another still. Aerials mode does not use the picture timer.

Photos and All show a QR code. Leave that screen open, scan it, and the phone page can send
jpeg, png, or webp files. They stay on the TV, and the pictures themselves show on that screen
as they arrive. Closing the screen stops the page. The address
has a new token each time, so an old scan does not keep working. Remove photos is on that screen.

A theme can still drop the same files in `themes/living-room/screensaver/` and push them. That
folder is not part of the repo. Screensaver has to be on Photos or All before those files play.
Phone pictures are turned upright and cropped to fill the screen. A theme push with no
`screensaver` folder leaves the photos already on the TV alone. `-Reset` clears them.

`idle_show` in the theme is the choice until someone picks one in Screensaver. `aerial` plays
Apple's published 1080p H.264 aerials, streamed, through the launcher's
own HTTP proxy (`AerialProxy`), because the system player checks those certificates in another
process. Do not turn certificate checks off, do not use the cleartext phobos URLs, and do not
copy the video files into the app.

Turn the system screensaver off or it covers this idle:

```powershell
adb -s <tv-ip>:5555 shell settings put secure screensaver_enabled 0
```

Leave the screen-off timeout alone. The in-app idle is the painting. The panel still sleeps on
the firmware's own timer.

An open More apps folder, a card prompt, or reordering blocks the idle.

## Scenes

`launcher/app/src/main/assets/scenes/` holds five scenes: `night`, `golden`, `water`, `fog`,
`dawn`. Each has a photo, a blurred glass fill for the clock, and (except water) a foreground
cut-out. They are the Couch TV night scenes, made for that project with an image model and
shipped here at 1920x1080. Couch TV's note is that they are not real places and you may use them
however you like: `couch-tv` `tv-home/web/backgrounds/CREDITS.md`. The `@2x` upscales are not
bundled. Scenes change about every 20 seconds and crossfade for 1.6 seconds.

## Updates

On each return to Home, and at most every 12 hours, the launcher asks GitHub for the latest
release of `lsands65/tv-home` (a theme can set `update_repo` to another `owner/name`). The
release tag is the version code, such as `v9`, and one of the files is the APK. A higher tag
than the installed build opens a prompt. Install downloads it and hands it to the TV, which
still asks before replacing Home. Not now hides that version until a newer one exists.

The repo is private, so the request needs a token that can read it. Put that token, and nothing
else, in `update-token` next to the theme file on the TV
(`/sdcard/Android/data/com.gathin.tvhome/files/update-token`), or in the theme as `update_token`.
A public repo needs neither. Do not commit the token.

```powershell
gh release create v9 app\build\outputs\apk\release\app-release.apk --repo lsands65/tv-home --title "2.8"
```

Build with `versionCode` 9 and `versionName` `2.8` before cutting that release. The tag has to
match the code, or Home ignores the release.

## Hisense 75U75N

Board `hengshan`, the panel draws a 1920x1080 UI on a 4K screen, density 320 (density 2). Leave
`wm size` and density as the firmware set them. The rest of this set (inputs, the remote, why a
key helper costs the volume ramp) is in
[`examples/hisense-u75-hengshan/README.md`](../examples/hisense-u75-hengshan/README.md).

On this firmware:

- Leave `com.vt.tv.ai_sense` and the smartsense / AIoT packages enabled. Disabling them has
  thrown this family into Recovery.
- Leave `com.google.android.apps.tv.launcherx` disabled, and do not uninstall this home until
  another app claims HOME. This firmware can boot-loop with no home.
- Do not turn on `HomeKeyService` or `InputKeyService` if volume-key repeat matters. With a CEC
  soundbar, the helper swallows the repeat and volume steps once per press.
- The living-room theme hides `com.vt.tv.tvcenter` (Live TV, also the HDMI player),
  `com.vt.tv.singlelisten`, `com.vt.tv.ai_sense`, and Projectivity
  (`com.spocky.projengmenu`). Hiding is not disabling. `live_tv_package` stays
  `com.vt.tv.tvcenter`.
- Keep USB debugging on.
