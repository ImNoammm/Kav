---
title: Download
description: Download Kav for Android, and every version so far.
aside: false
editLink: false
---

# Download Kav

For Android 8.0 or newer. Open the APK to install it; Kav then keeps itself up to date from GitHub, with no store and no
update service in between.

<div class="kav-download">
  <DownloadButton caption />
</div>

## Check it's the real one

GitHub builds every APK from the code in the repository and signs a record of it. With the
[GitHub CLI](https://cli.github.com) you can check the file you downloaded came from there:

```sh
gh attestation verify kav-<version>.apk --repo ImNoammm/kav
```

## Version history

<ReleaseList />
