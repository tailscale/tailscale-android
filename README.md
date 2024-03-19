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


## Preparing a build environment

There are several options for setting up a build environment. The Android Studio
path is the most useful path for longer term development.

In all cases you will need:

- Go runtime
- Android SDK
- Android SDK components (`make androidsdk` will install them)

### Android Studio

1. Install a Go runtime (https://go.dev/dl/).
2. Install Android Studio (https://developer.android.com/studio).
3. Start Android Studio, from the Welcome screen select "More Actions" and "SDK Manager".
4. In the SDK manager, select the "SDK Tools" tab and install the "Android SDK Command-line Tools (latest)".
3. Run `make androidsdk` to install the necessary SDK components.

If you would prefer to avoid Android Studio, you can also install an Android
SDK. The makefile detects common paths, so `sudo apt install android-sdk` is
sufficient on Debian / Ubuntu systems. To use an Android SDK installed in a
non-standard location, set the `ANDROID_SDK_ROOT` environment variable to the
path to the SDK.

If you installed Android Studio the tools may not be in your path. To get the
correct tool path, run `make androidpath` and export the provided path in your
shell.

#### Code Formatting

The ktmft plugin on the default setting should be used to autoformat all Java, Kotlin
and XML files in Android Studio.  Enable "Format on Save".

### Docker

If you wish to avoid installing software on your host system, a Docker based development strategy is available, you can build and start a shell with:

```sh
make dockershell
```

### Nix

If you have Nix 2.4 or later installed, a Nix development environment can
be set up with:

```sh
alias nix='nix --extra-experimental-features "nix-command flakes"'
nix develop
```

## Building

```sh
make apk
make install
```

## Building a release

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

## Running in the Android emulator

By default, the android emulator uses an older version of OpenGL ES,
which results in a black screen when opening the Tailscale app. To fix
this, with the emulator running:

 - Open the three-dots menu to access emulator settings
 - To to `Settings > Advanced`
 - Set "OpenGL ES API level" to "Renderer maximum (up to OpenGL ES 3.1)"
 - Close the emulator.
 - In Android Studio's emulator view (that lists all your emulated
   devices), hit the down arrow by the virtual device and select "Cold
   boot now" to restart the emulator from scratch.

The Tailscale app should now render correctly.

Additionally, there seems to be a bug that prevents using the
system-level Google sign-in option (the one that pops up a
system-level UI to select your Google account). You can work around
this by selecting "Other" at the sign-in screen, and then selecting
Google from the next screen.

## Developing on a Fire Stick TV

On the Fire Stick:

* Settings > My Fire TV > Developer Options > ADB Debugging > ON

Then some useful commands:
```
adb connect 10.2.200.213:5555
adb install -r tailscale-fdroid.apk
adb shell am start -n com.tailscale.ipn/com.tailscale.ipn.IPNActivity
adb shell pm uninstall com.tailscale.ipn
```

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
