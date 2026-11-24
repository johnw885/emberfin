# Baseline Profile

`app/src/main/baseline-prof.txt` lists the classes and methods Emberfin uses at startup and while
browsing. Release builds ship it (R8 rewrites it to the obfuscated names), and ART compiles that
code ahead of time, so the first runs after an install scroll as smoothly as later ones.

## How it's made

`generate.ps1` runs on a real Android TV device (Android 13 or newer, no root):

1. installs the `profiling` build type (release without R8, so the profile uses source names),
2. resets the app's ART profile (`cmd package compile --reset`),
3. drives the main journeys with D-pad key events (Home rows, Details, Shows, Movies, My List,
   Search, the billboard trailer),
4. asks the app to save its JIT profile (ProfileInstaller's `SAVE_PROFILE` broadcast) and dumps it
   with `pm dump-profiles --dump-classes-and-methods`.

That is the same mechanism Macrobenchmark's `BaselineProfileRule` uses on non-rooted Android 13+
devices, without the extra test libraries and Gradle plugin a `com.android.test` module needs.

```powershell
.\gradlew.bat assembleProfiling
.\baselineprofile\generate.ps1 -Device <tv-ip>:5555
.\gradlew.bat assembleRelease --no-configuration-cache
```

The device must be signed in to a server with some movies and series. Regenerate after big UI
changes and commit the new `baseline-prof.txt`.

## Checking it on a device

A Play install applies the profile at install time. For a sideloaded APK, ProfileInstaller writes
it on first launch and the device compiles it during its next idle maintenance. To get the
Play-like result straight away, install the APK together with the `.dm` file AGP builds next to it:

```powershell
adb install-multiple -r app-release.apk baselineProfiles\0\app-release.dm
adb shell dumpsys package com.johnw.emberfin | findstr "status="   # expect speed-profile, reason install-dm
```

`baselineProfiles\0` is for Android 12+ (API 31+), `baselineProfiles\1` for Android 9–11
(`output-metadata.json` says which). The wrong one is rejected (`EXTENDED_BAD_EXTERNAL_PROFILE` in
logcat) and the app is only verified, not compiled.

Measured on the Google TV Streamer (2026-11-24, scripted D-pad scroll over Home, release build):
plain `adb install -r`, first run 4.8 % janky frames; installed with the `.dm`, first run 0.4 %
and second run 0.8 %.
