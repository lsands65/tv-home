# Install this home on another TV

This is the procedure for putting **this** launcher on someone else's Android TV or Google TV.
It is the living-room home in this repo: scenic clock, packed dock, Keep watching, More apps,
and the Screensaver app. A stock [CleanYourTV](https://github.com/Gathin23/cleanyourtv) APK
does not have it. Do not follow [PROMPT.md](../PROMPT.md) for this job. That prompt debloats a
TV and then tells you to turn debugging off. This install does not debloat, and it leaves
debugging on unless the person asks you to turn it off.

Read [scenic-home.md](scenic-home.md) for how the screen behaves. This file is only how to get
that screen onto a set you have not seen before.

Do not commit a TV's IP, Wi-Fi name, XMLTV address, or a GitHub token. Do not push to
`Gathin23/cleanyourtv`.

## Before you touch the TV

Ask the person, once:

- Which apps belong on the dock, in order. Settings should be one of them
  (`com.android.tv.settings` on most Google TV sets) so the corner pills can stay off.
- Whether they have an XMLTV address for the sports row. If they do not, leave `sports_epg`
  empty. If they do, put it only in the theme file on the TV, never in git.
- That this set may become the default Home. Do not disable the stock launcher until this one
  is installed and you have seen it focused.

You need JDK 17 (Gradle 8.9 does not build on JDK 25) and the Android SDK in `ANDROID_HOME`.
The TV must be on the same network, awake, and on Android 8 or newer (`ro.build.version.sdk`
at least 26).

## 1. Connect

On the TV: Settings → Device Preferences → About → OK on Build seven times → Developer options
→ USB debugging on. If there is a Network debugging or ADB over network switch, turn that on
too.

```powershell
adb connect <tv-ip>:5555
adb -s <tv-ip>:5555 shell getprop ro.product.manufacturer
adb -s <tv-ip>:5555 shell getprop ro.product.model
adb -s <tv-ip>:5555 shell getprop ro.product.device
adb -s <tv-ip>:5555 shell getprop ro.build.version.sdk
```

If connect times out, the set is asleep or debugging is off. Ask the person to wake it with
the remote. Do not invent a wake-on-LAN address from another house.

Write down `ro.product.device`. If it is `hengshan`, or the manufacturer is Hisense and
`pm path com.vt.tv.ai_sense` exists, the limits in the last section apply. Do not apply those
package names to a different brand.

## 2. Theme for this house

Copy `themes/living-room/config.json` to a new file you will push. Change only what this house
needs:

- `dock_apps`: their packages, in the order they asked for. Look the names up with
  `adb shell pm list packages` and the labels from `dumpsys package`. Do not guess.
- `sports_epg`: their XMLTV address, or `""`.
- `hidden_apps`: start empty. The list in the example is one Hisense set. Hiding a package
  that is not installed does nothing. Do not disable those packages. Hiding is not disabling.
- `live_tv_package`: set it only when that package is installed and is actually Live TV.

Leave the scenic keys as they are: `scenic` true, `rows_below_dock` 0, `dock_title` false,
`show_greeting` false, both glow keys false, both pill keys false, `uniform_tiles` true,
`focus_overshoot` false, `wallpaper` false, `idle_show` `"art"`, `idle_seconds` 60. That is
the look. `columns` must be at least the number of dock apps. Scenic adds the More apps tile
itself.

## 3. Build and install

From `launcher\`:

```powershell
$env:JAVA_HOME = '<jdk 17>'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleRelease --console=plain
adb -s <tv-ip>:5555 install -r app\build\outputs\apk\release\app-release.apk
adb -s <tv-ip>:5555 shell am force-stop com.gathin.tvhome
adb -s <tv-ip>:5555 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity
```

`install -r` keeps a theme that is already on the set. If the build says `classes.dex` is
locked, run `.\gradlew.bat --stop` in `launcher\` and build again. Do not use
`build-and-install.ps1` or `tools/b64push.py` for the APK. That upload times out.

Push the theme you edited. Pushing restarts Home, which is what reloads the file:

```powershell
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv-ip> <your theme folder>
```

On macOS or Linux the same job is `TV_HOST=<tv-ip> tools/theme.sh <your theme folder>`.

Turn the system screensaver off so it does not cover the in-app one. Leave the screen-off
timeout alone:

```powershell
adb -s <tv-ip>:5555 shell settings put secure screensaver_enabled 0
```

## 4. Confirm it is Home

```powershell
adb -s <tv-ip>:5555 shell dumpsys activity activities | findstr mResumedActivity
```

The resumed activity should be `com.gathin.tvhome/.MainActivity`. Only then, and only if the
person wants the stock Google TV launcher out of the way, disable
`com.google.android.apps.tv.launcherx` with `pm disable-user --user 0`. Do not uninstall it.
Do not leave the set with no enabled Home. If this launcher is not focused, stop and say so.

Take a screenshot and look at it:

```powershell
$env:TV_HOST = '<tv-ip>'
$env:TV_PORT = '5555'
tools\venv\Scripts\python.exe -I tools\tv.py --screenshot shots\home.png
```

You should see a landscape, a glass clock with the foreground cutting the lower digits, the
date centered, no greeting, and no Inputs or network pills. The dock is a short pill of the
apps' own icons, Settings among them, More apps last. Keep watching, when the focused app has
published anything, is up to three glass cards of one height, with the episode and a progress
bar. When that app has published nothing, the row and the heading are both gone.

Then check the pieces that are easy to miss:

- OK on the last dock icon opens More apps. The first icon is Screensaver, a clock tile.
  The focus ring is on the icon, with a gap, not around the name. OK launches the app.
  Back closes the folder and does not open an app.
- Screensaver offers Art, Aerials, Photos, and All. Photos and All show a QR code while that
  screen stays open. A phone on the same network can send jpeg, png, or webp. Closing the
  screen stops the page. Do not commit the photos.
- Back twice within two seconds, with nothing else open, fills the screen with a painting.
  The next key wakes the home and does not open an app.
- Up from the dock enters Keep watching. Up again does nothing.

`shots/` is gitignored. Leave the screenshots there.

## 5. What you do not change

- Do not install Couch TV, and do not put this UI in a WebView. The scene is drawn by `Scenic`.
- Do not turn certificate checks off, and do not copy Apple's aerial videos into the app.
  Aerials are streamed.
- Do not vendor new paintings under 4096px on the long edge. The ones in
  `launcher/app/src/main/assets/art/` are the set.
- Updates, if the person wants them, read GitHub releases of `lsands65/tv-home`. The tag is
  the version code (`v9`) and the release includes the APK. A private repo needs a read token
  in `update-token` next to the theme on the TV. That token is not the person's full `gh`
  login, and it is not committed. Until they ask, skip this.
- The sports filter in `SportsGuide.java` prefers Wisconsin teams. Change that list only when
  the person names a different region.

## Hisense `hengshan`

Apply this only after step 1 showed that board, or the same `com.vt.tv.*` packages:

- Do not disable `com.vt.tv.ai_sense` or the smartsense / AIoT packages. On this family that
  has dropped the set into Recovery.
- Do not re-enable `com.google.android.apps.tv.launcherx` once this home is in place, and do
  not remove this home until another app claims HOME. This firmware can boot-loop with none.
- Do not enable `HomeKeyService` or `InputKeyService` if a volume key should keep repeating.
- Keep USB debugging on.
- More of that set is in
  [examples/hisense-u75-hengshan/README.md](../examples/hisense-u75-hengshan/README.md).
