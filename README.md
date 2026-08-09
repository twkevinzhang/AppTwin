# AppTwin

AppTwin is an experimental, open-source Android app-level multi-account container. M0 targets
an unrooted, bootloader-locked `ASUS_I002D` running Android 12 / API 31. The target remains Android
16 compatibility (`compileSdk` and `targetSdk` 36).

> **Current status:** the sideload-only `runtimeProbe` build imports installed base/split APKs into a
> GPL-3.0 runtime and launches accepted LINE and Shopee GroupApps with host-private data.
> LINE 15.5.4 reached its fresh login screen, while Shopee Taiwan 3.79.27 reached its live home and
> native login screens on the ASUS_I002D acceptance device. Local fixtures also validate two-space
> data isolation, source uninstall/reinstall, same-signer updates, deep-link user routing,
> notification identity/labels, and per-space camera/microphone permission-query decisions. This is
> not general Android 16, arbitrary-app, push-service, or camera/microphone hardware acceptance.
> The optional per-space Google-services compatibility layer uses a pinned microG GmsCore and
> Companion/FakeStore pair; it is not Google GMS. Availability and local isolation are ASUS-fixture
> validated. FCM, Maps, Sign-In, Cast, and Nearby still require real-service acceptance, while Play
> Billing and Play Integrity are deliberately unsupported.

## Product architecture

```text
System updates main-system app
                 |
                 v
      PackageSourceSnapshot
 base APK + exact split set + signer lineage + ABI
                 |
          stage and verify
                 v
        shared PackageRevision
                 |
                 v
       Virtual PackageManager
                 |
       host StubActivity / guest process
                 |
      isolated GroupApp data
```

One Android package version is shared by every GroupApp. Creating a Group immediately allocates
one dedicated isolation environment and permanently binds that environment to the Group. App
private data, accounts, per-App enabled state, permissions, and supported system-service state live
inside that boundary, while package code remains shared. Activating a verified revision must never
replace Group data.

## Modules

- `app`: Material 3 launcher showing independent 分身空間, installed package import, explicit
  compatibility/repair states, diagnostics, shortcuts, and persistent clone data roots.
- `application-core`: pure Kotlin lifecycle/use-case, durable operation, repair, compatibility,
  and diagnostics policies.
- `package-source`: pure Kotlin immutable models and completeness validation for base/split APKs,
  signature lineage, and supported ABIs.
- `revision-store`: pure Kotlin revision state machine for staging and atomic activation. The app
  persists immutable revisions and an atomic active pointer in host-private storage.
- `group-store`: pure Kotlin `Group`/`GroupApp` model and state store. One package can appear at
  most once in a Group, while separate Groups may each contain it.
- `virtual-runtime`: downstream Android 12/arm64 port of VirtualXposed 0.22.0's GPL-3.0
  `VirtualApp/lib`. It supplies virtual package/component routing, guest process startup, and
  native path redirection. Provenance and downstream changes are recorded in
  [`virtual-runtime/UPSTREAM.md`](virtual-runtime/UPSTREAM.md).
- `gms-compat-core`: pure Kotlin per-Group compatibility profiles, lifecycle operations,
  capability evidence, diagnostics, and explicit unsupported-feature policy.
- `microg-artifact-source`: verifies the exact reviewed microG GmsCore and Companion/FakeStore
  release pair before either artifact can be staged. APK bytes are build inputs and are not stored
  in Git.
- `gms-runtime-adapter`: binds the verified pair to one Group environment through host-only runtime
  operations. Generic package installation cannot request signature replacement.
- `gms-capability-fixture`: local/ASUS fixture for availability and isolation evidence. It contains
  no Play Billing or Play Integrity client dependency or success path.

The historical candidate review and the reason for selecting the exact GPL release tree are
recorded in [`docs/core-engine-audit.md`](docs/core-engine-audit.md). Device acceptance evidence is
recorded for [LINE](docs/m0-line-acceptance.md) and
[Shopee Taiwan](docs/m0-shopee-acceptance.md).
Group-scoped long-press uninstall and cross-Group data-preservation evidence is recorded in
[`docs/group-app-uninstall-acceptance.md`](docs/group-app-uninstall-acceptance.md).
The current fixture acceptance matrix is recorded in
[`docs/m1-m3-fixture-acceptance.md`](docs/m1-m3-fixture-acceptance.md).
The exact per-Group microG boundary and destructive ASUS fixture procedure are recorded in
[`docs/microg-asus-fixture-acceptance.md`](docs/microg-asus-fixture-acceptance.md).

For Shopee, select the imported package and use **開啟登入** to enter Shopee's declared native
login activity. The normal GroupApp action continues to open Shopee's home activity. AppTwin
does not bypass Shopee traffic verification or device-integrity decisions.

## Build and test

Requirements:

- JDK 17
- Android SDK platform 36
- Android SDK Build Tools

```shell
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew test :app:assembleRuntimeProbeDebug
```

Install the sideload-only debug build:

```shell
adb install -r -t app/build/outputs/apk/runtimeProbe/debug/app-runtimeProbe-debug.apk
```

`runtimeProbe` intentionally targets Android API 23 behavior while compiling with SDK 36. The
default product target remains API 36; the legacy-target probe must not be submitted to Google
Play.

## Sideload-only permissions

The M0 manifest declares `QUERY_ALL_PACKAGES` to enumerate the main system's installed apps and
`MANAGE_EXTERNAL_STORAGE` to directly browse shared photos and Download. Both permissions are
highly restricted by Google Play policy; M0 is intentionally distributed by sideload only.

On Android 11 and newer, all-files access is a special setting rather than a normal runtime
permission. The launcher links to the system screen where the user can explicitly enable it.

## License

Copyright © 2026 AppTwin contributors.

AppTwin is licensed under the GNU General Public License, version 3 or (at your option) any
later version. See [LICENSE](LICENSE).
