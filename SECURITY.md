# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub: open the repository's **Security** tab and
choose **Report a vulnerability**. Don't open a public issue.

Include what you found, how to reproduce it and which version you tested. Never include real server
addresses, passwords or access tokens; use placeholders.

You can expect an answer within a week. Fixes go out in the next release, and the advisory is
published once a fixed build is available.

## Supported versions

Only the latest release gets security fixes.

## Scope

Emberfin is a client: it stores a Jellyfin access token per profile on the device (excluded from
backups and device transfer) and talks only to the server the user signs in to. Problems in the
Jellyfin server itself belong to the [Jellyfin project](https://github.com/jellyfin/jellyfin/security).
