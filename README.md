# Glow – a calm home screen app for Fire TV (Fire OS 5/6/7, Android 5.1+)

Favourites row, all-apps grid, drifting wallpapers, clock/greeting, weather + location, "now playing" widget.
It runs as a normal app (Fire OS does not allow replacing the Amazon home screen).

## Build (Mac)
1. Install Android Studio. Open this folder (File > Open). Accept any "upgrade Gradle/AGP" prompt.
2. Build > Build Bundle(s) / APK(s) > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk
3. Firestick: Settings > My Fire TV > Developer options > ADB debugging ON + Install unknown apps ON.
   Note the IP: Settings > My Fire TV > About > Network.
4. `brew install --cask android-platform-tools`, then:
   adb connect <firestick-ip>:5555
   adb install -r app/build/outputs/apk/debug/app-debug.apk
5. Open Glow from Your Apps & Channels.

## Now playing
Needs notification access. Settings tab > "Now playing" shows the steps (settings screen or one ADB command).

## Build with GitHub instead of Android Studio
Push this folder to a GitHub repo. Actions > "Build APK" builds it (also runs on every push to main).
Download `glow-apk` from the finished run, or push a tag (`git tag v1.0 && git push --tags`) to get
a Release with a direct `glow.apk` link you can fetch on the Firestick with the Downloader app.
