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

- The installed-app browser only exposes enabled apps with a launcher activity.
- `QUERY_ALL_PACKAGES` makes arbitrary installed package discovery possible in the sideload M0.
- A revision copies the base APK and every `splitSourceDirs` entry as one immutable unit.
- The source package is queried before and after the copy. A concurrent Play update makes the
  operation fail rather than activating a mixed revision.
- Every copied APK receives SHA-256 metadata and is made read-only before activation.
- The active pointer is replaced using an atomic filesystem move.
- Version rollback and signing-lineage replacement are rejected by the transition model.
- Each Group has persistent identity metadata, a unique GroupApp set, a host-private `data/` root,
  and exactly one immutable isolation-environment binding allocated during Group creation.
- Group creation and deletion run through a durable operation journal. A restart rolls forward or
  cleans up an interrupted transition; an already-healthy Group is never silently rebound.
- Apps in one Group share its account environment; another Group cannot see those app or account
  data.
- App private data, account state, per-App install/enable state, runtime permissions, and
  the supported subset of system-service state are scoped by the same Group environment.
- If a healthy Group's bound environment disappears, the Group becomes `DAMAGED`. Recovery must
  be explicit; allocating a replacement would violate the Group's identity contract.
- The runtime installs the active immutable revision into its own virtual package registry and
  launches the guest through a host `StubActivity` without adding another Android package.
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
- `MANAGE_EXTERNAL_STORAGE` is explicitly user-granted. The launcher probes direct visibility of
  `Download`, `DCIM`, and `Pictures` without recording file names.

## M0 device-validated boundary

- One LINE 15.5.4 GroupApp launches through the virtual PackageManager/ActivityManager path on an
  unrooted ASUS_I002D running Android 12/API 31 and reaches the fresh login screen.
- The guest process uses the AppTwin UID, while its process label and window resources remain
  LINE's. The original LINE package and data directory remain separate.
- One Shopee Taiwan 3.79.27 GroupApp launches on the same device, loads the live home screen, and
  opens Shopee's declared native login activity. The login screen remained in the foreground for
  more than 75 seconds, survived a background/foreground cycle, and also passed a force-stop cold
  launch.
- This milestone validates ordinary private-data separation for one GroupApp; it is not a security
  boundary against a hostile guest app.

## Not implemented or not accepted yet

- Full device acceptance for multiple newly-created Groups running the same package concurrently.
- A complete per-Group permission policy across all Android framework services.
- Notification routing, push-service integration, deep links, camera, microphone, voice/video, and
  background survival.
- Guest update migration tests across two real Play versions.
- Android 16 runtime acceptance on a physical locked device.

See [`m0-line-acceptance.md`](m0-line-acceptance.md) and
[`m0-shopee-acceptance.md`](m0-shopee-acceptance.md) for the exact accepted paths and evidence.
