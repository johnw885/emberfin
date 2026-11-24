# Contributing to Emberfin

Thanks for helping. Emberfin is a small project, so a short issue before a big pull request saves
everyone time.

## Reporting bugs and asking for features

Use the issue templates. For playback problems, include the device, the Jellyfin server version and
the file's codecs (video, HDR type, audio) from Jellyfin's media info. **Never post your server
address, access tokens, user ids or screenshots of your own library**; describe the file instead.

Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## Building

You need JDK 17 and the Android SDK (compileSdk 37). See the README for the build output folder and
how to install on a TV.

```
./gradlew lintDebug testDebugUnitTest assembleDebug
```

CI runs exactly that on every pull request; please make sure it passes locally first.

## Pull requests

- Keep each pull request to one change, with a clear description of what it does and how you tested
  it (which device, which server version).
- Follow the existing code style (Kotlin official style). Plain-Kotlin logic in `:playback` and
  `:core:data` should come with unit tests.
- Everything must work with the D-pad alone: every screen reachable and leavable, focus always visible.
- No Google Play Services, Firebase, analytics, ads or tracking of any kind. Emberfin only talks to
  the user's own Jellyfin server, and stays buildable for F-Droid.
- New dependencies need a good reason and an open-source licence compatible with Apache-2.0, and
  must not pull in any of the above.
- Don't copy code from GPL-licensed projects (including the official Jellyfin Android TV app);
  Emberfin is Apache-2.0.
- Emberfin follows familiar streaming-app *patterns* with its own brand: no other service's name,
  logo, colours, fonts or sounds, and no Jellyfin logo or claims of being an official Jellyfin app.
- Audio: Dolby TrueHD and DTS tracks are never picked or listed. Changes to `AudioTrackPolicy` keep
  that rule.

## Releases

Maintainers push a tag like `v1.2.3`. The Release workflow builds a signed AAB and APK, attaches
the APK to a GitHub Release, and keeps the AAB and R8 mapping file as workflow artifacts for Google
Play. Version code = major × 10000 + minor × 100 + patch.

By contributing you agree that your contribution is licensed under the Apache License 2.0, like the
rest of the project.
