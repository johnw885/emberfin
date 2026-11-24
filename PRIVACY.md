# Emberfin privacy policy

_Last updated: 24 November 2026_

Emberfin is an open-source app for Android TV and Google TV that plays media from a Jellyfin server
you run or have access to. This policy covers the Emberfin app on Google Play and the builds
published on GitHub (<https://github.com/johnw885/emberfin>).

## The short version

**Emberfin does not collect, sell or share any data.** The developer runs no servers for Emberfin
and receives nothing from the app: no analytics, no crash reports, no advertising ids, no tracking.

## What the app talks to

Emberfin connects only to the Jellyfin server address you type in. Everything it sends goes to that
server, which you or the server's owner control:

- your username and password, or a Quick Connect code, to sign in;
- requests for your libraries, artwork and media;
- playback progress, watched state, favourites and ratings, so the server can keep your resume
  points and lists (every Jellyfin client does this);
- a device name ("Emberfin") and a random device id, so the server can list and manage the session.

What that server does with this information is up to its owner, not Emberfin. Connections are
encrypted when your server address starts with `https://`. Plain `http://` is allowed because many
home servers only offer it on the local network.

## What is stored on your device

- Server addresses and, per profile, the access token the server issued at sign-in. Tokens are
  excluded from Android backups and device-to-device transfers.
- Your settings, an optional profile PIN (stored only as a salted hash), a kids-mode flag per
  profile, the audio and subtitle choice per series, and a cache of home-screen rows so the app
  opens quickly.

All of this stays on the device. Signing out removes the profile's token; uninstalling the app
removes everything.

## Permissions

Emberfin uses the internet and network-state permissions to reach your server, and a wake lock to
keep the screen on during playback. Voice search uses the system's speech recognizer; Emberfin
itself never records audio.

## Children

Emberfin isn't directed at children and collects no data from anyone. Kids mode only changes which
rows the app shows; the server's parental controls decide what a profile can watch.

## Changes and contact

Changes to this policy are published in this file, with the date above. Questions: open an issue at
<https://github.com/johnw885/emberfin/issues>.
