# Group environment binding acceptance

Date: 2026-08-05

Device: `ASUS_I002D`, Android 12/API 31, unrooted and bootloader locked

Build: `runtimeProbeDebug`, installed with `adb install -r -t`

> **Legacy naming note:** This device acceptance predates the AppTwin rename. References to
> MaskAccounts, its temporary backup paths, and its observed process identity are preserved as
> historical evidence; the current product and package names are AppTwin and `org.apptwin`.

## Contract

- A Group is the product's isolation identity; no second user-facing runtime identity exists.
- Creating a Group immediately allocates exactly one dedicated environment.
- The binding never changes while that Group exists.
- App private data, Google account data, per-App install/enable state, permissions, and supported
  system-service state are scoped by that environment.
- Google package contents remain lazy and begin as `NOT_PREPARED`.
- A missing bound environment makes the Group `DAMAGED`; it is not silently replaced.
- Deleting a Group deletes its bound environment and data without affecting another Group.

## Migration Given / When / Then

- **Given** the device had 12 persisted Groups and 14 GroupApps. Several legacy Groups shared the
  default engine environment, several had no binding, and the user-created `test` Group already
  owned a dedicated environment with three GroupApps.
- **When** schema 2 started without clearing app data.
- **Then** all 12 Groups became `HEALTHY`, every binding was non-default and unique, all 14 GroupApp
  records remained, `test` retained the exact same binding and three GroupApps, and the lifecycle
  journal was empty at terminal state.
- Only GroupApp private directories were copied out of the legacy shared environment. GMS and
  account data were not copied. LINE and YouTube destination file counts matched their sources
  (`162` and `55` files respectively); the Shopee destination contained all source files and later
  runtime additions.

Before migration, both metadata and runtime users were backed up without clearing device data:

```text
/tmp/maskaccounts-group-binding-preflight.zVRGmy/groups-metadata.tar
SHA-256 1f46ce7109aebad9dde28e75b8f09f9df35dcfbf7281a5860a94189e22c99f06

/tmp/maskaccounts-group-binding-preflight.zVRGmy/runtime-users.tar
SHA-256 c2b8e567508c9a1b5ddbb64055f46f7b7847a2eb53905932e8d1cc2d0fbb31a6
```

## Persistence Given / When / Then

- **Given** the migrated Groups and a temporary `E2E_A` Group created through the Material 3 UI.
- **When** the APK was installed again with `adb install -r`, MaskAccounts was force-stopped, and
  the ASUS device was rebooted once.
- **Then** the sorted `Group id + binding + health` snapshot kept the same SHA-256 before reinstall,
  after reinstall, and after reboot:
  `e9d4d75127cd33df0e2f3ac6e8c8af3190d920cca212cf669d88348315ab3c71`.
- The temporary Group received a binding distinct from every existing Group. Deleting it through
  the confirmation dialog removed both its metadata and exact environment; the original 12 Groups
  remained.

## Runtime and UI Given / When / Then

- **Given** the existing Maps Group was `READY` before migration.
- **When** Maps was launched after reinstall and reboot.
- **Then** it retained its original binding, loaded original Maps version `26.30.09.950492155` with
  the complete arm64, xxhdpi, and zh split set, displayed live map tiles, and ran behind a
  MaskAccounts `StubActivity`. The guest Maps process and host process both used the MaskAccounts
  UID while Maps data stayed under the Group's engine-private directory.
- The Home accessibility tree exposed 12 Groups, `首頁` and `設定`, but no engine numeric ID or
  legacy runtime-user terminology before or after reboot.

## Automated regression

```text
./gradlew test :app:assembleRuntimeProbeDebug

BUILD SUCCESSFUL
```

The tests cover binding allocation and immutability, duplicate prevention, legacy shared-binding
split, default-environment migration, data-copy scope, missing-environment damage behavior,
creation rollback, operation-journal recovery, and deletion isolation.

## Remaining risks

- Permission and partial system-service isolation still depends on the compatibility hooks each
  guest exercises; the complete Android framework API matrix is not yet accepted.
- Notification routing, FCM delivery, background survival, and simultaneous same-package execution
  across two active Groups remain separate end-to-end acceptance targets.
