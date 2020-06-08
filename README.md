# Tailscale Android Client

https://tailscale.com

Private WireGuard® networks made easy

## Overview

This repository contains the open source Tailscale Android client.

## Using

Available on [Play Store](https://play.google.com/store/apps/details?id=com.tailscale.ipn).

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

We only guarantee to support the latest Go release and any Go beta or
release candidate builds (currently Go 1.14) in module mode. It might
work in earlier Go versions or in GOPATH mode, but we're making no
effort to keep those working.

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
