# M0 architecture

```text
Installed source package
  base.apk + exact split set + signing lineage + ABI
                       |
                       v
             host-private staging
       copy + SHA-256 + fsync + source re-query
                       |
              atomic directory rename
                       v
             active PackageRevision
                       |
                       v
          Virtual PackageManager record
                       |
             guest process (host UID)
                       |
       host-private GroupApp data
```

## Implemented boundaries

- `package-source`, `revision-store`, and `group-store` own the domain models and transition
  policies. Pure Group lifecycle/removal and runtime revision orchestration live in the JVM-only
  `application-core`; Android package, filesystem, UI, and VirtualCore adapters remain in `app`.
- The installed-app browser only exposes enabled apps with a launcher activity.
- `QUERY_ALL_PACKAGES` makes arbitrary installed package discovery possible in the sideload M0.
- A revision copies the base APK and every `splitSourceDirs` entry as one immutable unit.
- The source package is queried before and after the copy. A concurrent Play update makes the
  operation fail rather than activating a mixed revision.
- Every copied APK receives SHA-256 metadata and is made read-only before activation.
- The active pointer is replaced using an atomic filesystem move.
- Version rollback and signing-lineage replacement are rejected by one shared transition policy
  used by both the reference store and the production Android importer.
- Each Group has persistent identity metadata, a unique GroupApp set, a host-private `data/` root,
  and exactly one immutable isolation-environment binding allocated during Group creation.
- Group creation and deletion run through a durable operation journal. A restart rolls forward or
  cleans up an interrupted transition; an already-healthy Group is never silently rebound.
- Corrupt or unsupported Group/GroupApp metadata is reported explicitly and preserved instead of
  being interpreted as a missing Group or membership.
- Apps in one Group share its account environment; another Group cannot see those app or account
  data.
- App private data, account state, per-App install/enable state, runtime permissions, and
  the supported subset of system-service state are scoped by the same Group environment.
- If a healthy Group's bound environment disappears, the Group becomes `DAMAGED`. Recovery must
  be explicit; allocating a replacement would violate the Group's identity contract.
- The runtime installs the active immutable revision into its own virtual package registry and
  launches the guest through a host `StubActivity` without adding another Android package.
- Every launch first synchronizes the installed source into an active revision, compares the
  active base/split digests with VirtualCore's copied artifacts, and transactionally updates shared
  guest code while retaining every Group user's installed flag and private data.
- Virtual package code/settings changes use a fsynced PREPARED/COMMITTED journal. Startup recovery
  runs before package settings are loaded or the package service is published, so a process death
  restores the previous app directory, odex, settings, and installed-user set before serving calls.
- Guest code runs in an AppTwin-owned process/UID. Native path redirection maps guest private
  paths into the host-private virtual data tree.
- On Android 12 and lower, AndroidX's synthetic non-exported dynamic-receiver permission is granted
  virtually and rewritten to an AppTwin-owned signature permission before the OS call. This
  preserves receiver privacy while avoiding a permission owned by the original app's signature.
- On Android 12, the guest delegate class path includes the available platform Apache HTTP legacy
  shared library. This preserves compatibility with installed apps that declare
  `org.apache.http.legacy` but load code through the virtual class loader.
- The Android 12 guest identity is bound before creating the initial package context, preventing a
  cached host-identity `LoadedApk` from rejecting later guest `createPackageContext(INCLUDE_CODE)`
  calls.
- Package broadcasts refresh the foreground UI; foreground/startup reconciliation remains the
  source of truth because Android does not guarantee background delivery to a killed host.
- ViewModel work uses lifecycle-owned coroutines on a serialized I/O dispatcher. SavedStateHandle
  and saveable Compose state retain navigation, picker, dialog, and draft state across recreation.
- `MANAGE_EXTERNAL_STORAGE` is explicitly user-granted. The launcher probes direct visibility of
  `Download`, `DCIM`, and `Pictures` without recording file names.

## Per-Group microG compatibility boundary

```text
reviewed release manifest
  + pinned GmsCore APK
  + pinned Companion/FakeStore APK
          |
   digest + signer + package + version verification
          |
          v
 host-only trusted install transaction
          |
          +------ Group A virtual user: microG packages, accounts, tokens, data
          |
          `------ Group B virtual user: absent unless separately consented/enabled
```

- `gms-compat-core` owns the product profile, lifecycle state machine, operation records,
  capability evidence, and diagnostics policy. `microg-artifact-source` owns the reviewed release
  pair; `gms-runtime-adapter` owns the VirtualCore bridge; Android UI and durable file adapters stay
  in `app`.
- Signature replacement is accepted only for the exact pinned `com.google.android.gms` and
  `com.android.vending` artifacts after independent APK digest and real-signer verification.
  Generic APK metadata, legacy signature caches, and guest Binder callers cannot authorize it.
- Enable, suspend-preserving-data, reset, and Group deletion are user-scoped, durable, and
  fail-closed. Account state, runtime permissions, jobs, notifications, pending-intent epochs,
  device/location/virtual-storage state, CE/DE data, and private external data are retired before a
  virtual user id may be reused.
- Server-side Binder authority derives from a registered virtual caller. Host administration is
  limited to the engine and exact AppTwin main process; unknown or stale guest processes never
  inherit host authority. User/package/session ownership is rechecked at service entry points.
- Physical Google packages are never a fallback for a Group where the trusted pair is absent.
  Play Billing and Play Integrity components are removed from fresh and cached Companion package
  models and remain product-policy `UNSUPPORTED`.
- Google Play services availability and the local per-Group lifecycle/isolation matrix are
  accepted on the ASUS API 31 fixture. FCM, Maps, Sign-In, Cast, and Nearby remain real-service
  `UNTESTED`; fixture evidence cannot promote those capabilities to production support.

## M0 device-validated boundary

- One LINE 15.5.4 GroupApp launches through the virtual PackageManager/ActivityManager path on an
  unrooted ASUS_I002D running Android 12/API 31 and reaches the fresh login screen.
- The guest process uses the AppTwin UID, while its process label and window resources remain
  LINE's. The original LINE package and data directory remain separate.
- One Shopee Taiwan 3.79.27 GroupApp launches on the same device, loads the live home screen, and
  opens Shopee's declared native login activity. The login screen remained in the foreground for
  more than 75 seconds, survived a background/foreground cycle, and also passed a force-stop cold
  launch.
- A same-signature local fixture was installed as host version 1, launched in a Group, replaced by
  version 2, and launched through the production application path on the ASUS Android 12 device.
  Virtual PM advanced to version 2 while the Group/environment binding, launch counter, and
  version-1 private-data sentinel were preserved; GroupApp removal then deleted all guest private
  directories without deleting the shared revision cache.
- This milestone validates ordinary private-data separation for one GroupApp; it is not a security
  boundary against a hostile guest app.

## Fixture-validated product boundary

- Two newly-created spaces run the same fixture package with distinct environment IDs, files,
  launch counters, installed-user state, and permission-query decisions. Restart and removal of one
  membership preserve the other space.
- Notifications using the same guest package/id receive distinct stable host identities and expose
  the originating space name. Deep-link resolution retains the requested virtual user and an exact
  selected space launches the matching guest environment.
- Removing the source package preserves membership, immutable revision, and guest data; reinstalling
  the same-signer source resumes the original environment.
- A same-signer fixture v1→v2 update advances shared guest code while preserving the environment and
  version-1 private-data sentinel.

## Not accepted yet

- A complete per-space permission policy across Android services. Camera/microphone
  `checkSelfPermission` decisions are fixture-validated, but actual Camera/AudioRecord service
  enforcement, active-session revocation, voice, and video are not.
- Real push-service delivery and notification click routing with third-party services.
- Pinned-launcher shortcut confirmation on a physical launcher remains a manual acceptance item.
  Exported cold-start deep-link entry, exact chooser candidates, virtual-user resolution, and the
  selected guest launch are accepted on the ASUS fixture device.
- Guest update migration tests across two real Play versions.
- Android 16 runtime acceptance on a physical locked device.
- Real-service FCM registration/delivery, Maps, Google Sign-In, Cast, and Nearby acceptance for the
  pinned microG release. Play Billing and Play Integrity are intentionally out of scope rather
  than pending acceptance.

See [`m0-line-acceptance.md`](m0-line-acceptance.md) and
[`m0-shopee-acceptance.md`](m0-shopee-acceptance.md) for the exact accepted paths and evidence.
See [`m1-m3-fixture-acceptance.md`](m1-m3-fixture-acceptance.md) for the current fixture matrix.
See [`microg-asus-fixture-acceptance.md`](microg-asus-fixture-acceptance.md) for the exact microG
artifact pins, destructive device procedure, capability claims, and executed evidence.
