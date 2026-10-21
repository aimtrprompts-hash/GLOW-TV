# Glow – a calm home screen for Fire TV (Fire OS 5/6/7, Android 5.1+)

A quiet launcher: your apps, a slowly moving photo, the time, and a "now playing" card for your music.
It runs as a normal app (Fire OS does not allow replacing the Amazon home screen).
The look is inspired by [CleanFrame](https://github.com/nthnclrk/CleanFrame).

## Features
- **Calm home:** light clock and date, glass dock, soft focus ring. Greeting and weather are optional (Settings > Greeting & weather).
- **Dock styles:** Icons (default) or Tiles (banners). Hold OK on an app to pin, move or hide it.
- **Moving wallpapers:** photos slowly zoom and pan and dissolve into the next one; or drifting colour glows. Turn motion off in Settings.
- **Now playing:** works with Spotify, Amazon Music, YouTube Music and any player that shows a media session. Album art, source app,
  equaliser, progress, previous / play-pause / next, and an accent colour taken from the artwork.

## Your own wallpapers and fonts
- Add pictures to `app/src/main/assets/wallpapers/` (see the README there). Two or more unlock "Rotate through all my photos".
- Optional Manrope font: see `app/src/main/assets/fonts/README.txt`.

## Build (Mac)
1. Install Android Studio. Open this folder (File > Open). Accept any "upgrade Gradle/AGP" prompt.
2. Build > Build Bundle(s) / APK(s) > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
3. Firestick: Settings > My Fire TV > Developer options > ADB debugging ON + Install unknown apps ON.
   Note the IP: Settings > My Fire TV > About > Network.
4. `brew install --cask android-platform-tools`, then:
   `adb connect <firestick-ip>:5555`
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
5. Open Glow from Your Apps & Channels.

## Now playing
Needs notification access. Settings > "Now playing" shows the steps (settings screen or one ADB command):
`adb shell pm grant app.glow.tv android.permission.WRITE_SECURE_SETTINGS`

## Build with GitHub instead of Android Studio
Push this folder to a GitHub repo. Actions > "Build APK" builds it (also runs on every push to main).
Download `glow-apk` from the finished run, or fetch `glow.apk` from the Release with the Downloader app on the Firestick.
