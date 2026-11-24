# Emberfin

[![CI](https://github.com/johnw885/emberfin/actions/workflows/ci.yml/badge.svg)](https://github.com/johnw885/emberfin/actions/workflows/ci.yml)

A big-screen, streaming-service-style client for [Jellyfin](https://jellyfin.org) on Android TV and
Google TV, built with Kotlin, Compose for TV and Media3 ExoPlayer.

Emberfin is an independent project. It is not affiliated with or endorsed by the Jellyfin project.

**Status:** MVP. Tested daily on a Google TV Streamer (4K) against Jellyfin 12.4. Not on Google Play yet;
build it yourself and sideload it.

## Features

- **Sign in** with Quick Connect or username and password; several profiles per TV ("Who's watching?").
- **Home**: a billboard with slow backdrop motion, Continue Watching, Next Up, Recently Added per
  library, genre rows and My List. Rows show from a local cache first, then refresh.
- **Shows and Movies** tabs with genre rows and an "All" grid (sort, unwatched and genre filters),
  plus a Home Videos grid.
- **Details** pages with seasons and episodes, More Like This, cast and filmographies.
- **Search** with an A–Z on-screen keyboard or your remote's microphone, with recently added titles as
  suggestions.
- **Player** built for the living room:
  - direct play first, with a device profile built from the TV's real decoders, HDR types and audio
    outputs; direct stream or transcode only when needed, and an automatic fallback chain;
  - Dolby Vision (profiles 5 and 8; profile 7 plays its HDR10 base layer), HDR10, HDR10+ and HLG;
  - Dolby Digital Plus / Atmos and Dolby Digital passthrough. Dolby TrueHD and DTS tracks are never
    picked or listed; the player chooses an E-AC-3, AC-3 or AAC track instead;
  - trickplay thumbnails while scrubbing, Skip Intro/Recap buttons and a next-episode countdown from
    Jellyfin's media segments;
  - an Episodes panel inside the player, "More like this" over a movie's credits and after the end,
    and "Are you still watching?" after three episodes or two hours without a key press;
  - each series remembers the audio and subtitle choice you made in any of its episodes;
  - subtitles (SRT, ASS/SSA, PGS, VobSub, WebVTT) in an adjustable caption style;
  - frame-rate matching (off, seamless only, or always; "always" waits for the TV to finish
    switching before playback starts);
  - resume points and playback progress reported to the server, and remote play/pause through a
    media session (HDMI-CEC works too).
- **Play on the TV from your phone**: while Emberfin is open, other Jellyfin apps (the phone app's cast
  button, Jellyfin web's "Play on") can start playback on the TV and control play/pause, seeking,
  next/previous and audio/subtitle tracks, and send messages to the screen. Rows refresh by themselves
  when watched state changes elsewhere.
- **Settings**: streaming quality cap, Atmos preference, frame-rate matching, what to do at intros,
  recaps, previews, commercials and credits, autoplay, subtitle size and background, profiles and
  sign-out, and open-source licences.
- Copes with a server that goes away (a retry banner while cached rows stay usable) and with a sign-in
  the server revoked (back to the sign-in screen).
- No analytics, no Google Play Services, no tracking: the app only talks to your own server.

## Requirements

- Android TV / Google TV device running Android 9 (API 28) or newer.
- Jellyfin server **12.0 or newer**.

## Building

You need JDK 17 and the Android SDK (compileSdk 37).

```
./gradlew assembleDebug        # debug build
./gradlew assembleRelease      # release build (debug-signed unless the EMBERFIN_KEYSTORE* variables are set)
./gradlew testDebugUnitTest    # unit tests
```

Build output goes to `%USERPROFILE%\EmberfinBuild` on Windows (or `~/EmberfinBuild`); override it with
`-PbuildRoot=...`. Release builds currently need `--no-configuration-cache`.

To install on a TV, turn on Developer options and network debugging on the TV, then:

```
adb connect <tv-ip>:5555
adb install -r app-release.apk
```

For debug builds you can prefill your server address by adding a line to the git-ignored
`local.properties`:

```
DEV_SERVER_URL=http://your-server:8096
```

Release builds never contain a server address.

## Project layout

| Module | What's in it |
|---|---|
| `:app` | Activity, navigation, screens (profiles, home, browse, details, search, settings, player) |
| `:core:data` | Jellyfin SDK wiring, sign-in and profiles, library queries, image URLs, Room cache |
| `:core:ui` | Theme, type scale, focus treatment, tiles, BlurHash placeholders |
| `:playback` | Device profile, audio-track policy, stream negotiation and fallback, progress reporting, media segments, trickplay, ExoPlayer controller |

## Screenshots

Screenshots will be added once there's a showcase library of Creative Commons films to show; the
developer's own library is copyrighted material.

## Contributing, security and privacy

See [CONTRIBUTING.md](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md). Report security
problems privately as described in [SECURITY.md](SECURITY.md). Emberfin collects no data; see the
[privacy policy](PRIVACY.md).

Signed release APKs are attached to [GitHub Releases](https://github.com/johnw885/emberfin/releases)
(built by CI from a `v*` tag).

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party licences are listed in the app under
Settings → Open-source licences.
