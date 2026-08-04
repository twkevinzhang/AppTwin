# Group-scoped Play Store acceptance

## Contract

- Every MaskAccounts Group owns one independent virtual runtime environment.
- Google Services Framework, Google Play services, Play Store accounts, installed-app state, and
  app data are scoped to that environment.
- Play-installed APK code may be shared by the runtime, but installation membership, app data,
  removal, and account state remain per Group.
- No Android Work Profile, Private Space, secondary Android user, APK patching, or resigning is
  used.

## Given / When / Then

Given two healthy Groups on the unrooted ASUS Android 12 test device:

1. When each Group opens Play Store, its GSF, GMS, and Play Store packages must be installed for
   that Group's runtime user and Google Checkin must use that user's private data directory.
2. When Play Store queries modern staged-install APIs, the runtime must return a compatible empty
   result instead of terminating Play Store.
3. When Play Store binds its local Firebase Messaging service, the runtime must use the supported
   no-push fallback instead of passing a cross-process `BinderProxy` that Firebase rejects.
4. When Play installs or removes an app, only that Group's installation membership and metadata
   may change; shared code must remain while another Group still has the app installed.
5. When MaskAccounts refreshes, newly installed launchable packages must appear as `PLAY_STORE`
   apps and stale `PLAY_STORE` entries must disappear without changing `SYSTEM_IMPORT` entries.

## 2026-08-05 device evidence

### Passed

- Installed the runtime-probe APK with `adb install -r -t`; application data was not cleared.
- Existing 12 Groups migrated to schema 3 without rollback files or lost apps.
- Created `E2E_STORE_A` and `E2E_STORE_B`; they received different runtime users (13 and 14).
- Both Groups installed/started independent GSF, GMS, and Play Store instances and completed
  Google Checkin successfully against `https://android.googleapis.com/checkin`.
- Both Checkin token files were 39 bytes and had different SHA-256 values, proving that the two
  Groups did not share one Checkin identity.
- Both Play Store data directories were under their own runtime-user paths.
- Reproduced and fixed two current Play Store compatibility failures:
  `PackageInstaller.getStagedSessions()` no longer terminates the background process, and the
  Play Store foreground process no longer crashes with `Binding only allowed within app`.
- After the final compatibility build, Group A stayed on Play Store's unauthenticated screen with
  no fatal exception through the observed launch window.

### Account result and remaining acceptance gap

- Credentials were entered only through the device UI and were not stored in source, shell
  commands, screenshots, or this document.
- Google accepted the first account identifier but rejected the supplied password twice. The
  password field contained the expected 15 characters before each submission.
- Google rejected the second account identifier as not found in both Group flows.
- Therefore an actual Play download, same-package install into both Groups, single-Group removal,
  and post-reboot persistence could not be executed with the supplied accounts. The implementation
  is complete for those paths and is covered by unit tests, but this document does not claim that
  the account-dependent device acceptance passed.

## Automated coverage

- Group schema/origin migration and persistence.
- Play Store inventory reconciliation and `SYSTEM_IMPORT` preservation.
- Per-user first install, equal-version binding, update membership preservation, removal, and
  last-user behavior.
- Update rollback for package code, odex, package settings, and package cache.
- Installer commit lifecycle idempotence and terminal callback behavior.
- Package signature compatibility.
- Modern staged-session query compatibility.
- Narrow Play Store Firebase local-binder fallback policy.
