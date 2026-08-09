# M1–M3 fixture acceptance

Date: 2026-08-09

Device: `ASUS_I002D`, Android 12/API 31, unrooted and bootloader locked

Build: `runtimeProbeDebug`; test data is the self-owned `org.apptwin.fixture` package. AppTwin
private data and the fixture package were cleared/reinstalled between scenarios. LINE and Shopee
data were not modified.

## Accepted fixture stories

| Story | ASUS result | Evidence |
| --- | --- | --- |
| Same package in two spaces | Passed | Distinct virtual users, private sentinels, counters, and installed-user state survived host restart. |
| Remove one clone only | Passed | Its private directories were removed; the second space, shared code, revision, and data survived and relaunched. |
| Permission-query decisions | Passed | New guest processes observed camera-only in 工作 and microphone-only in 私人; the other permission remained denied. |
| Notification identity and origin | Passed | Same guest notification id remained present for both spaces and exposed `AppTwin · 工作` / `AppTwin · 私人`. |
| Deep-link virtual-user routing | Passed | Both exact candidates resolved and selecting 私人 incremented only its guest counter. |
| Exported cold-start deep link | Passed | A real `ACTION_VIEW` cold-launched `MainActivity`; after onboarding the chooser exposed 工作/私人, and selecting 私人 advanced only virtual user 2 from launch 3→4. |
| Source uninstall/reinstall | Passed | Missing source retained membership, immutable revision, environment, and data; same-signer reinstall resumed it. |
| Fixture v1→v2 update | Passed | Virtual PM advanced to v2 while the original environment and v1 sentinel survived. |

Each phase was invoked separately with an instrumentation argument and returned `OK (1 test)`.
The generic connected suite keeps these destructive scenarios opt-in so ordinary test runs skip
them unless the matching phase argument is supplied.

## Explicitly outside this acceptance

- CameraManager/AudioRecord hardware access and active-session revocation. The accepted permission
  result is the decision visible to guest permission queries, not complete service-level isolation.
- Real push delivery, notification click behavior, and third-party background services.
- Real Play-delivered split APK v1→v2 migration.
- Android 16 on a locked physical device, including its notification permission dialog.
- Real LINE/Shopee account login, MFA, camera, microphone, notifications, and deep links.
- Physical launcher confirmation/click of a newly pinned desktop shortcut.

These boundaries must remain visible in product copy and diagnostics; fixture success must not be
reported as arbitrary-app or security-boundary compatibility.
