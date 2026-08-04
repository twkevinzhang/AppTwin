# Group home acceptance

Date: 2026-08-04

Device: `ASUS_I002D`, Android 12/API 31, unrooted and bootloader locked

Build: `runtimeProbeDebug`, installed with `adb install -r -t`

## Given / When / Then

### Legacy data migration

- **Given** the installed build contained 11 legacy records across Maps, YouTube, LINE, Shopee,
  Discord, and BlackBox packages.
- **When** the Group-based build started without clearing application data.
- **Then** it created 11 `groups/<groupId>/group.properties` records and 11 GroupApp metadata
  records. The homepage rendered a Group count of 11.

### Home and App picker

- **Given** a migrated Group containing BlackBox.
- **When** the final `加入 App` grid tile was selected.
- **Then** the picker identified the target as `BlackBox 1`, excluded BlackBox from the available
  list, and showed other packages with their cross-Group usage count.
- The phone layout rendered three grid columns and the bottom navigation exposed only `首頁` and
  `設定`. Both the top app-bar back icon and Android system Back returned from the picker to Home.

### Group CRUD

- **Given** 11 persisted Groups.
- **When** the temporary Group `E2E_Group` was created, renamed to `E2E_Renamed`, and permanently
  deleted through the Material 3 confirmation dialog.
- **Then** UI and disk counts changed `11 -> 12 -> 11`; no temporary metadata remained.

### Lazy Google runtime and cleanup

- **Given** a newly created empty Group with runtime state `NOT_PREPARED`.
- **When** LINE was added.
- **Then** the Group advanced to `READY`, created virtual user 2, and installed GSF, GMS, and Play
  Store for that user. Deleting the temporary Group removed its metadata and
  `virtual/data/user/2` directory.

### Legacy user-0 promotion

- **Given** migrated `LINE 1` still mapped to legacy shared virtual user 0 with 162 private files.
- **When** its Google runtime was prepared for the first time.
- **Then** it received a dedicated virtual user 2, all 162 LINE private files were copied, the
  mapping recorded `legacyDataMigrated=true`, and the homepage showed `Google 服務就緒`.
- Only GroupApp data is copied. Legacy user-0 GMS data is intentionally not copied, preventing two
  Groups from inheriting the same Google account environment.

## Automated checks

```text
./gradlew :group-store:test :app:testRuntimeProbeDebugUnitTest \
  :app:assembleRuntimeProbeDebug

BUILD SUCCESSFUL
```

The pure Kotlin tests cover per-Group package uniqueness, the same package across separate Groups,
rename/state transitions, deletion isolation, and one-time promotion of legacy user-0 mappings.

## Remaining boundary

This acceptance proves Group persistence, navigation, lazy GMS preparation, virtual-user cleanup,
and one real legacy LINE data promotion. It does not yet prove two newly-created Groups running the
same guest package concurrently, nor notification/FCM isolation across those Groups.
