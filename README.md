# MaskAccounts

MaskAccounts is an experimental, open-source Android app-level multi-account container. M0 targets
an unrooted, bootloader-locked `ASUS_I002D` running Android 12 / API 31. The target remains Android
16 compatibility (`compileSdk` and `targetSdk` 36).

> **M0 status:** this repository can import immutable revisions from installed base/split APKs and
> persist isolated instance records/data roots. It does **not** yet contain an app virtualization
> runtime and cannot launch a clone.

## M0 architecture

```text
Google Play updates main-system app
                 |
                 v
      PackageSourceSnapshot
 base APK + exact split set + signer lineage + ABI
                 |
          stage and verify
                 v
        shared PackageRevision
          /       |       \
   instance-1 instance-2 instance-3   (future isolated data roots)
```

One Android package version will be shared by every virtual instance. Package code and instance
data are separate concepts: activating a verified revision must never replace instance data.

## Modules

- `app`: minimal View-based launcher showing installed packages, all-files access status, package
  revision sync, and persistent instance records/data roots.
- `package-source`: pure Kotlin immutable models and completeness validation for base/split APKs,
  signature lineage, and supported ABIs.
- `revision-store`: pure Kotlin revision state machine for staging and atomic activation. The app
  persists immutable revisions and an atomic active pointer in host-private storage.
- `instance-store`: pure Kotlin instance identity model and state store. The app persists each
  instance under its own host-private data root.

No third-party virtualization core or hook framework is included. The completed candidate review
is recorded in [`docs/core-engine-audit.md`](docs/core-engine-audit.md); none of the audited trees is
safe to import as a modern production baseline.

## Build and test

Requirements:

- JDK 17
- Android SDK platform 36
- Android SDK Build Tools

```shell
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew test assembleDebug
```

Install the sideload-only debug build:

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Sideload-only permissions

The M0 manifest declares `QUERY_ALL_PACKAGES` to enumerate the main system's installed apps and
`MANAGE_EXTERNAL_STORAGE` to directly browse shared photos and Download. Both permissions are
highly restricted by Google Play policy; M0 is intentionally distributed by sideload only.

On Android 11 and newer, all-files access is a special setting rather than a normal runtime
permission. The launcher links to the system screen where the user can explicitly enable it.

## License

Copyright © 2026 MaskAccounts contributors.

MaskAccounts is licensed under the GNU General Public License, version 3 or (at your option) any
later version. See [LICENSE](LICENSE).
