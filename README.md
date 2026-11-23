# Reelfin

A big-screen client for [Jellyfin](https://jellyfin.org) on Android TV / Google TV, built with
Kotlin, Compose for TV and Media3 ExoPlayer.

**Status:** early development (scaffold + playback spike). Not ready for use.

Reelfin is an independent project and is not affiliated with the Jellyfin project.

## Building

Requires JDK 17 and the Android SDK (compileSdk 37).

```
./gradlew assembleDebug
```

Build output goes to `%USERPROFILE%\ReelfinBuild` (override with `-PbuildRoot=...`).

For debug builds you can prefill the server address by adding a line to the git-ignored
`local.properties`:

```
DEV_SERVER_URL=http://your-server:8096
```

Release builds never contain a server address.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
