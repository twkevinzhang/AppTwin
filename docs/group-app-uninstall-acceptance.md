# GroupApp uninstall acceptance

Date: 2026-08-05

Device: `ASUS_I002D`, Android 12/API 31, unrooted

Build: `runtimeProbeDebug`

> **Legacy naming note:** This device acceptance predates the AppTwin rename. The
> `org.maskaccounts` target named below is preserved as evidence of the package that was actually
> installed and tested; the renamed app and package are AppTwin and `org.apptwin`.

## Contract

- Long-pressing a GroupApp tile opens a one-item menu containing **解除安裝**; a normal tap still
  launches the app.
- Confirmation removes only that package membership from the selected Group environment.
- The selected Group's credential-encrypted data, Direct Boot data, virtual private storage, and
  GroupApp metadata are permanently removed.
- The host-system app, another Group's membership and private data, and shared runtime code remain.
- A persisted operation journal converges runtime-first removal after process death or a metadata
  write failure without deleting a newly re-added membership.

## Given / When / Then

- **Given** disposable Groups `E2E_REMOVE_A` and `E2E_REMOVE_B` each owned a different runtime user
  and had launched LINE successfully, with independent CE and Direct Boot sentinel files.
- **When** LINE in Group A was long-pressed, the menu appeared without launching LINE. Opening the
  confirmation dialog and selecting **取消** preserved both Groups' metadata and sentinels.
- **When** the flow was repeated and **解除安裝** was confirmed, Group A's LINE tile, metadata, CE
  directory, and Direct Boot directory disappeared. The journal reached zero pending records.
- **Then** Group B kept its metadata and both sentinels, shared LINE code remained, the host-system
  LINE package remained installed, and Group B launched LINE from its original runtime user.
- **When** the physical device rebooted, Group A's removed state persisted while Group B retained
  both sentinels and launched LINE again from the same runtime user.
- **Finally**, both disposable Groups were deleted through the product UI. Their metadata and
  runtime-user directories were removed, leaving zero Group files and zero removal-journal files.

The device run exposed and fixed a Direct Boot cleanup gap: per-user runtime uninstall already
deleted CE and virtual private storage, but did not delete `virtual/data/user_de/<user>/<package>`.
The final run verified that both CE and Direct Boot package directories are removed.

## Automated checks

- App unit tests: 33 passed.
- Virtual runtime unit tests: 76 passed.
- Group store unit tests: 9 passed.
- Compose instrumentation: 3 passed, covering short tap, long-press menu, cancel, confirm callback,
  and busy-state disabling.
- `runtimeProbeDebug` AndroidTest Kotlin compilation and APK assembly succeeded.

## Instrumentation runner safety

During this acceptance run, `connectedRuntimeProbeDebugAndroidTest` removed the legacy target
`org.maskaccounts` package during its post-test cleanup on this toolchain, which also removed
target-app private data. In the renamed project, the task targets `org.apptwin`. Run that Gradle
task only when AppTwin data on the connected device is disposable or separately backed up. For
data-preserving manual acceptance, build the APK and use `adb install -r -t`, then drive the
installed app without invoking the connected-test cleanup task.
