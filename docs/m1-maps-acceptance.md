# M1 Google Maps virtual GMS acceptance

## Contract

- Device: unrooted, bootloader-locked `ASUS_I002D` on Android 12 / API 31.
- Guest: the original installed `com.google.android.apps.maps` APK and its complete split set.
- No APK patching, resigning, Android Work Profile, Private Space, secondary Android user, or
  equivalent OS-level container is used.
- Each Maps instance receives an in-runtime virtual user. This is MaskAccounts runtime state, not
  an Android system user or profile.

## Given / When / Then acceptance

Given the device has original Maps, Google Services Framework, Google Play services, and Google
Play Store installed, and a new `地圖 1` MaskAccounts instance exists:

1. When MaskAccounts imports Maps, then the active revision must contain `base.apk` plus every
   source split and retain the original signer lineage.
2. When `地圖 1` starts, then GSF, GMS and Play Store must be installed for the same runtime user
   before Maps is launched.
3. When Maps reaches its initial screen, then the visible Android activity must be a MaskAccounts
   `StubActivity`, its guest process must run under MaskAccounts' UID, and its data directory must
   be `virtual/data/user/<virtual-user>/com.google.android.apps.maps` rather than the main-system
   Maps data directory.
4. When Maps asks for location, deny it. Maps must remain usable and must not receive location
   after the denial.
5. When a public place is searched, then network map tiles and result content must render.
6. When the Google account chooser appears, complete the designated test-account sign-in only
   under explicit device-testing authorization. Passwords and verification codes must not appear
   in source, shell commands, logs, screenshots, or this acceptance record.
7. When MaskAccounts is force-stopped and the Maps instance is launched again, then its runtime
   user and guest data directory must remain unchanged.

## 2026-08-04 M1 evidence and result

### Passed evidence

- ASUS_I002D reported `ro.boot.flash.locked=1` and `ro.boot.verifiedbootstate=green`.
- Main-system Maps was version `26.30.09.950492155`, version code `1068694917`, with base plus
  `config.arm64_v8a`, `config.xxhdpi`, and `config.zh` splits.
- MaskAccounts copied the original Maps revision and its virtual PackageManager reported all three
  split names.
- `maps-m1-virtual-user-created` created runtime user `1` for `地圖 1`.
- GSF, GMS and Play Store were each reported ready for runtime user `1`.
- The actual Android activity was `org.maskaccounts/.../StubActivity$C2`; the guest Maps process
  ran as UID `10959` (the MaskAccounts UID) and logged data directory
  `/data/user/0/org.maskaccounts/virtual/data/user/1/com.google.android.apps.maps`.
- The original guest GMS completed Checkin against `https://android.googleapis.com/checkin` and
  logged `Checkin Operation finished with result: SUCCESS`.
- Google account recovery/MFA was completed on-device. The verification code was transferred
  directly between the host Gmail UI and the guest field without being printed or retained.
- GMS logged `Account added successfully to AccountManager`, `Finished adding account`, and
  `Add account completed successfully`. The virtual account file was then updated under
  `org.maskaccounts` private storage.
- After a force-stop and cold launch, `VAccountManagerService` logged
  `Reading account : com.google`, and Maps exposed “目前的登入身分” plus “管理你的 Google 帳戶”.
- Maps rendered live map tiles and returned the public `Taipei 101` search result with place cards,
  routes, ratings, and map markers.
- Both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` were explicitly revoked from
  MaskAccounts. Android reported `granted=false` for both, Maps stayed open, kept rendering tiles,
  and exposed “已停用定位服務” rather than receiving a location.

### Isolation proof

- The Android PackageManager still has one original `com.google.android.apps.maps`; the clone is
  launched through a MaskAccounts stub activity rather than a second Android package install.
- Guest Maps, GMS, and GSF processes run under the MaskAccounts UID, while the main-system GMS UID
  remains separate.
- Runtime user `1` owns the guest package data and the virtual account database is stored inside
  MaskAccounts private storage. The main-system Maps data directory is not mounted as guest data.

## 2026-08-04 Maps performance regression acceptance

### Reproduction and causes

Given `地圖 1` was open with fine and coarse location denied, the original runtime still exposed
host location providers and forwarded Android 12 `registerLocationListener` calls. Google services
therefore retried location work using the guest package name against the MaskAccounts UID. Two
additional runtime defects amplified the slowdown:

- the host keep-alive service could make a guest slot try to bind `org.maskaccounts` as a virtual
  package, terminating and restarting the slot;
- Google ProtoStore process-local broadcasts (`SIGNAL_ACTION` and `MULTI_APP`) were registered
  with the host ActivityManager, never completed, and caused 60-second background/foreground ANRs.

The final runtime returns permission-denied location fallbacks without host Binder calls, skips
location-only GMS service starts while preserving GMS bind/search paths, does not guest-bind the
host keep-alive service, and omits the complete ProtoStore process-local action prefix from host
broadcast registration.

### Device result

- Installed with `adb install -r`; no package data was cleared.
- The final APK stayed alive beyond the 60-second broadcast timeout boundary with stable Maps and
  GMS PIDs. No ProtoStore timeout, `ANR in org.maskaccounts`, `App not exist`, `invalid package`,
  `ILocationManager`, or fatal exception appeared in the final interaction log window.
- A fresh `dumpsys gfxinfo ... framestats` run covered 20 map pans and five double-tap zooms. For
  87 valid completion timestamps: P50 `7.079 ms`, P90 `41.868 ms`, P95 `42.846 ms`, P99
  `47.473 ms`, maximum `48.760 ms`. Two invalid Android/driver completion timestamps were excluded
  rather than treating the fixed `4950 ms` histogram bucket as a real five-second frame.
- `Taipei 101` public search rendered live tiles, markers, ratings, and place result cards after the
  performance changes.
- Three final-APK cold starts reached a MaskAccounts `StubActivity` and a Maps guest process under
  the MaskAccounts UID. The second and third MaskAccounts activity cold-start times were `1323 ms`
  and `1845 ms`; all three cycles had zero matching ANR/fatal/package-identity errors.
- The account menu showed `登入` during this performance run. This run did not enter credentials
  and therefore does not make a new claim that a signed-in session was present; the historical M1
  sign-in evidence above remains a separate acceptance observation.

### Residual risks

- Google services still probe privileged platform APIs such as `READ_DEVICE_CONFIG`, prioritized
  alarms, package usage, and fine location. The compatibility layer returns guest-safe fallbacks;
  those optional services are not equivalent to privileged system GMS.
- One earlier post-login launch observed an obfuscated Maps `Binding only allowed within app`
  process exit. It was not reproduced in three consecutive cold starts of the final APK; keep it
  as a soak-test regression target before treating this vertical slice as broadly production-stable.
- GMS persistent still initializes fused-location native work after a bind and used roughly
  `11-15%` of one CPU during the measured interaction. Blocking that bind made public Maps search
  hang, so it remains enabled until the runtime can return a compatible denied-location Binder.
- Guest APK profile loading still cannot read the host package's ART profiles under the
  MaskAccounts UID. Cold-start performance therefore remains below a native Maps installation even
  though the reproduced ANR/restart stalls are removed.
- A background GMS `ConfigUpdateIntentOperation` can still exit the GMS main process when a guest
  GSF provider reads `DeviceConfig` before its `Application` is available. Maps remains foreground
  and search works after the process is recreated. Returning an empty Gservices result prevented
  the crash but made public search hang, so that attempted fallback was deliberately not retained.

The M1 vertical slice is **accepted for the stated ASUS_I002D sideload contract**: original Maps is
logged in inside runtime user `1`, live tiles and public search work, location remains denied, and
the signed-in state survives a MaskAccounts cold restart.
