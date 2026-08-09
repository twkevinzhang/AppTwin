# Group home acceptance

Date: 2026-08-04

Device: `ASUS_I002D`, Android 12/API 31, unrooted and bootloader locked

Build: `runtimeProbeDebug`, installed with `adb install -r -t`

## Given / When / Then

### Home and App picker

- **Given** a Group containing BlackBox.
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

## Automated checks

```text
./gradlew :group-store:test :app:testRuntimeProbeDebugUnitTest \
  :app:assembleRuntimeProbeDebug

BUILD SUCCESSFUL
```

The pure Kotlin tests cover per-Group package uniqueness, the same package across separate Groups,
rename/state transitions, and deletion isolation.

## Remaining boundary

This acceptance proves Group persistence, navigation, and environment cleanup. It does not yet
prove two newly-created Groups running the same guest package concurrently, nor notification
isolation across those Groups.
