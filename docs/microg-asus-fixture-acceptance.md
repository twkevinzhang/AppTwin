# microG per-Group ASUS fixture acceptance

This gate is destructive only to AppTwin and fixture data on the selected test device. It does not
call external FCM, Maps, Sign-In, Cast, Nearby, Billing, or Play Integrity services.

## Boundary

- Device: ASUS Android 12 / API 31.
- Trusted bundle: pinned microG GmsCore `250932030` plus pinned Companion/FakeStore `84022630`.
- Group A grants network consent and enables the trusted bundle; Group B does neither.
- Billing and Play Integrity remain product-policy `UNSUPPORTED`.
- FCM, Maps, Sign-In, Cast, and Nearby remain external-service `UNTESTED`; local fixture contracts
  must never upgrade those claims beyond `ASUS_FIXTURE`.
- Physical `com.google.android.gms`, `com.android.vending`, and `com.google.android.gsf` are never a
  fallback for a virtual Group.

## Build and run

```sh
./gradlew \
  :app:assembleRuntimeProbeDebug \
  :app:assembleRuntimeProbeDebugAndroidTest \
  :gms-capability-fixture:assembleDebug \
  -PmicrogArtifactFile=/absolute/path/com.google.android.gms-250932030.apk \
  -PmicrogCompanionArtifactFile=/absolute/path/com.android.vending-84022630.apk

adb -s "$SERIAL" install -r \
  gms-capability-fixture/build/outputs/apk/debug/gms-capability-fixture-debug.apk
adb -s "$SERIAL" install -r \
  app/build/outputs/apk/runtimeProbe/debug/app-runtimeProbe-debug.apk
adb -s "$SERIAL" install -r \
  app/build/outputs/apk/androidTest/runtimeProbe/debug/app-runtimeProbe-debug-androidTest.apk
adb -s "$SERIAL" shell pm clear org.apptwin
adb -s "$SERIAL" shell am instrument -w -r \
  -e microgFixturePhase 1 \
  -e class org.apptwin.runtime.MicrogGroupCompatibilityE2eTest \
  org.apptwin.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell pm clear org.apptwin
adb -s "$SERIAL" shell am instrument -w -r \
  -e crossUserBinderPhase 1 \
  -e class org.apptwin.runtime.CrossUserBinderIsolationE2eTest \
  org.apptwin.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell pm clear org.apptwin
```

Without `microgFixturePhase=1`, generic connected instrumentation runs skip this class.

## Business assertions

1. Only Group A sees both compatibility packages at the pinned versions. Its virtual PM exposes
   the Google compatibility certificate, while archive inspection retains the official microG
   signer provenance.
2. Group B sees neither compatibility package. Its local availability probe reports missing even
   on a physical ASUS image that contains official Google packages.
3. Group A reports `GoogleApiAvailability.SUCCESS` and both Groups retain distinct fixture state.
4. Disable hides both compatibility packages, preserves private data, and clears package-owned
   jobs, notifications, and pending-intent state. Re-enable restores access to the same data.
5. Reset-to-disabled removes compatibility package data, package-owned accounts and auth tokens,
   while Group B is unchanged.
6. Deleting Group A removes all user-keyed package/account/background state. After composition
   restart/reconciliation, the monotonic allocator gives a replacement Group a fresh id and it does
   not inherit deleted state. Local cleanup tests separately force same-id reuse and prove the same
   non-inheritance invariant.
7. Generic installation cannot authorize signature replacement, and a tampered pinned source is
   rejected with `ARTIFACT_APK_DIGEST_MISMATCH`.
8. Group B cannot read or change Group A account, token, package, provider, or package-management
   state through direct virtual-service clients. Same-Group account writes and package/provider
   queries remain available, proving the authority boundary does not disable legitimate operation.

## Executed record — 2026-08-10

- ASUS API 31 normal per-Group flow: `OK (1 test)` in 23.615 seconds. This covered the pinned
  two-APK bundle, disabled-Group physical-package isolation, distinct Group fixture state,
  disable/re-enable preservation, reset, Group deletion, server restart, monotonic replacement,
  tamper rejection, and an external symlink sentinel that survived Group cleanup.
- ASUS API 31 Group authority regression: `OK (1 test)` in 13.26 seconds. Cross-Group reads,
  writes, provider access, and package-management changes were rejected; same-Group controls
  succeeded and the target Group's host-observed state remained unchanged.
- GMS Compose UI instrumentation reported `OK (3 tests)` in 3.072 seconds. Fixture instrumentation
  reported `OK (3 tests)` in 0.073 seconds. Billing and Integrity SDK artifacts are absent from the
  fixture dependency graph, and source inspection found no related client imports or calls.
- Generic invocation without `microgFixturePhase`: assumption skip, `OK (1 test)` with no fixture
  mutation.
- During acceptance, ASUS execution exposed and closed bootstrap and process-observation recursion
  gaps in the new authority boundary before the terminal passes.
- Final cleanup: both `org.apptwin` and `org.apptwin.gms.fixture` data were cleared, their process
  checks returned empty, and their data directories contained no test files. AppTwin and fixture
  APKs intentionally remain installed. No external-service request or billable cloud resource was
  created.
