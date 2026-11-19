---
title: "The source template"
editLink: false
---

<div v-pre>

# The source template

A GitHub repository ready to be a source: one extension, a README for riders, and a workflow that builds the bundle and attaches it to each release. It comes with the developer kit, in `template/`.

Extensions for [Kav](https://kavapp.link), the open-source transit app. Made and published by me, not by Kav.

[![Add to Kav](/img/add-to-kav.svg)](https://kavapp.link/add-source?github=OWNER/REPO)

To add this source by hand: in Kav open Settings, Extensions, Sources, the plus, and enter `OWNER/REPO`. Its
extensions come in off: switch on the ones you want.

## What is here

| Extension | What it does |
|---|---|
| My extension | Say in a sentence what it does for a rider. |

Problems with these extensions go to this repository's issues, not to Kav.

## For developers

This repository was started from the Kav extension kit's template. Replace `OWNER/REPO` and the table above, and
delete this section when you no longer need it.

Each extension is a folder under `extensions/`: its `manifest.json` and its Kotlin or Java under `src/`. The kit's
`kavx` builds them all into one bundle (`.kavb`), and each GitHub Release of this repository carries it. Kav reads the
latest release's bundle.

```
kavx check extensions/my-extension     builds one extension and checks it the way Kav does
kavx bundle . --out my.kavb            builds the bundle; add it in Kav with Settings, Extensions, Sources, Add a bundle file
```

### Releasing

Raise `release` and `version` in the manifest of each extension that changed, commit, then tag and push
(`git tag v1 && git push origin v1`). `.github/workflows/release.yml` builds the bundle and attaches it to a release
for the tag. By hand: `kavx bundle . --out my.kavb`, then `gh release create v1 my.kavb`.

## License

GPL-3.0. See [LICENSE](/docs/license).


## The files

### `extensions/my-extension/manifest.json`

```json
{
  "format": 1,
  "name": "my-extension",
  "displayName": "My extension",
  "version": "1.0.0",
  "release": 1,
  "minKav": 45,
  "api": 2,
  "description": "A card on Kav's Home and a screen of its own, written in Kotlin.",
  "native": {
    "dex": "classes.dex",
    "entry": "org.example.hello.Hello"
  },
  "contributions": [
    {
      "id": "card",
      "capability": "slot.home"
    },
    {
      "id": "screens",
      "capability": "page.provide"
    }
  ],
  "pages": [
    {
      "id": "about",
      "title": {
        "en": "Hello Kotlin",
        "he": "שלום קוטלין"
      }
    }
  ],
  "issues": "https://github.com/OWNER/REPO/issues"
}
```

### `.github/workflows/release.yml`

```yaml
# Builds the bundle of every extension under extensions/ and attaches it to a GitHub Release when a tag is pushed
# (git tag v1 && git push origin v1). Kav reads the latest release's bundle at its next check.
#
# The extensions are Kotlin or Java: they need a JDK and the Android SDK, which GitHub's Ubuntu runner has, and the Kotlin
# compiler, which this downloads. It checks out the Kav extension kit from github.com/ImNoammm/kav-extension-kit beside
# this repository and runs its kavx (the kit has no dependencies). Until the kit is published there, this workflow
# fails and the bundle is built and released by hand (README, "Releasing").
#
# UNTESTED ON GITHUB: its commands were run by hand on a computer, but it has not run on GitHub yet. Read its first
# run's log.
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          path: source

      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          repository: ImNoammm/kav-extension-kit
          path: kav-extension-kit

      - uses: actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7.1.0
        with:
          node-version: 24

      - name: Get the Kotlin compiler
        run: |
          curl -fsSL -o "$RUNNER_TEMP/kotlin.zip" https://github.com/JetBrains/kotlin/releases/download/v2.2.10/kotlin-compiler-2.2.10.zip
          unzip -q "$RUNNER_TEMP/kotlin.zip" -d "$RUNNER_TEMP"
          echo "KOTLINC=$RUNNER_TEMP/kotlinc/bin/kotlinc" >> "$GITHUB_ENV"

      - name: Build the bundle
        working-directory: source
        run: |
          node ../kav-extension-kit/bin/kavx.js bundle . --name "${{ github.event.repository.name }}" --out "$RUNNER_TEMP/${{ github.event.repository.name }}.kavb"
          node ../kav-extension-kit/bin/kavx.js verify "$RUNNER_TEMP/${{ github.event.repository.name }}.kavb"

      - name: Release it
        working-directory: source
        env:
          GH_TOKEN: ${{ github.token }}
        run: gh release create "$GITHUB_REF_NAME" "$RUNNER_TEMP/${{ github.event.repository.name }}.kavb" --verify-tag --title "$GITHUB_REF_NAME" --generate-notes
```

</div>
