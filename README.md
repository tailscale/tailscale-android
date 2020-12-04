# Tailscale Android Client

https://tailscale.com

Private WireGuard® networks made easy

## Overview

This repository contains the open source Tailscale Android client.

## Using

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.tailscale.ipn/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=com.tailscale.ipn)

## Building

[Go](https://golang.org), the [Android
SDK](https://developer.android.com/studio/releases/platform-tools), 
the [Android NDK](https://developer.android.com/ndk) are required.

```
$ make tailscale-debug.apk
$ adb install -r tailscale-debug.apk
```

The `dockershell` target builds a container with the necessary
dependencies and runs a shell inside it.

```
$ make dockershell
# make tailscale-debug.apk
```

Use `make tag_release` to bump the Android version code, update the version
name, and tag the current commit.

We only guarantee to support the latest Go release and any Go beta or
release candidate builds (currently Go 1.14) in module mode. It might
work in earlier Go versions or in GOPATH mode, but we're making no
effort to keep those working.

## Google Sign-In

Google Sign-In support relies on configuring a [Google API Console
project](https://developers.google.com/identity/sign-in/android/start-integrating)
with the app identifier and [signing key
hashes](https://developers.google.com/android/guides/client-auth).
The official release uses the app identifier `com.tailscale.ipn`;
custom builds should use a different identifier.

## Bugs

Please file any issues about this code or the hosted service on
[the tailscale issue tracker](https://github.com/tailscale/tailscale/issues).

## Contributing

`under_construction.gif`

PRs welcome, but we are still working out our contribution process and
tooling.

We require [Developer Certificate of
Origin](https://en.wikipedia.org/wiki/Developer_Certificate_of_Origin)
`Signed-off-by` lines in commits.

## About Us

We are apenwarr, bradfitz, crawshaw, danderson, dfcarney,
from Tailscale Inc.
You can learn more about us from [our website](https://tailscale.com).

WireGuard is a registered trademark of Jason A. Donenfeld.
