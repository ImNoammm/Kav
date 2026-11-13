---
title: Building from source
description: Build the Kav APK and the timetable yourself, and how official releases are made.
---

# Building from source

Kav's app is Kotlin and Jetpack Compose, built with Gradle. Everything below runs without root and without installing
system packages.

## What you need

- Linux on x86-64 for the toolchain script below. On another system, install JDK 21 and the Android SDK (platform 36,
  build tools 35.0.0) yourself and point `JAVA_HOME` and `ANDROID_HOME` at them.
- Git, and Python 3 for the timetable.

## Get the code

```sh
git clone https://github.com/ImNoammm/kav.git
cd kav
```

## The toolchain

`tools/android_toolchain.sh` downloads JDK 21 and the Android SDK into `~/Android`, and nothing anywhere else.
`rm -rf ~/Android` undoes it.

```sh
tools/android_toolchain.sh
```

## Build the APK

`tools/android_build.sh` runs Gradle with that toolchain; anything after it goes to Gradle.

```sh
tools/android_build.sh :app:assembleRelease
```

The APK is `android/app/build/outputs/apk/release/app-release.apk`. Install it on a phone with USB debugging on:

```sh
~/Android/Sdk/platform-tools/adb install -r android/app/build/outputs/apk/release/app-release.apk
```

::: warning Your build has your signature
A release build is signed with your own machine's debug key (`~/.android/debug.keystore`), not Kav's. Android won't
install it over the official app, or the official app over it, without uninstalling first, and Kav's links won't open
it straight from the browser, because those are verified for Kav's own key.
:::

::: info Kav's server answers official builds only
The app signs its requests to Kav's server with a key that isn't in the repository. A build of your own works with
every other source: Bus Nearby, EGG and [a server of your own](/docs/self-hosting).
:::

## The timetable

The APK carries no timetable. A nightly GitHub Actions run compiles the Ministry of Transport's GTFS feed and Tel Aviv's
weekend lines into the file Kav downloads (about 6 MB), signs its manifest, and publishes it to the `timetable` release.
To compile one yourself:

```sh
tools/fetch.sh
KAV_FROM=today KAV_REGION=il KAV_BBOX=national KAV_MANIFEST=1 python3 tools/export_web_bundle.py
```

`tools/fetch.sh` downloads the ministry's feed into `.cache/` (about 180 MB). The result lands in `.cache/timetable/`,
or wherever `KAV_OUT` points.

## How releases are made

Pushing a version tag (`v4.0`) starts [the release workflow](https://github.com/ImNoammm/kav/blob/main/.github/workflows/release.yml)
on GitHub. It builds the APK from that exact commit, signs it with Kav's key, checks the certificate is the one every
release so far has used, records a build attestation, and attaches the APK to the tag's release. Anyone can check a
download came from there:

```sh
gh attestation verify kav-4.0.apk --repo ImNoammm/kav
```

## The server

Kav's server is Python (`server/live.py`), OpenTripPlanner and a predictor, all in Docker. Building its images from
source and releasing them are in the self-hosting guide, under
[For developers](/docs/self-hosting#for-developers).

## Contributing

Bugs and ideas are welcome as [issues](https://github.com/ImNoammm/kav/issues), and fixes as pull requests. Kav is
GPL-3.0, and so is anything merged into it.
