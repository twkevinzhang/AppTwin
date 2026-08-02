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
        host-private virtual instance data
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
- Each virtual instance has persistent identity metadata and a separate host-private `data/` root.
- The runtime installs the active immutable revision into its own virtual package registry and
  launches the guest through a host `StubActivity` without adding another Android package.
- Guest code runs in a MaskAccounts-owned process/UID. Native path redirection maps guest private
  paths into the host-private virtual data tree.
- On Android 12 and lower, AndroidX's synthetic non-exported dynamic-receiver permission is granted
  virtually and rewritten to a MaskAccounts-owned signature permission before the OS call. This
  preserves receiver privacy while avoiding a permission owned by the original app's signature.
- Package broadcasts refresh the foreground UI; foreground/startup reconciliation remains the
  source of truth because Android does not guarantee background delivery to a killed host.
- `MANAGE_EXTERNAL_STORAGE` is explicitly user-granted. The launcher probes direct visibility of
  `Download`, `DCIM`, and `Pictures` without recording file names.

## M0 device-validated boundary

- One LINE 15.5.4 instance launches through the virtual PackageManager/ActivityManager path on an
  unrooted ASUS_I002D running Android 12/API 31 and reaches the fresh login screen.
- The guest process uses the MaskAccounts UID, while its process label and window resources remain
  LINE's. The original LINE package and data directory remain separate.
- This milestone validates ordinary private-data separation for one clone; it is not a security
  boundary against a hostile guest app.

## Not implemented or not accepted yet

- Assigning a distinct virtual user/data tree to every persisted instance record; the runtime
  probe currently launches virtual user 0.
- A complete per-instance permission policy across all Android framework services.
- Notification routing, FCM/GMS integration, deep links, camera, microphone, voice/video, and
  background survival.
- Guest update migration tests across two real Play versions.
- Android 16 runtime acceptance on a physical locked device.

See [`m0-line-acceptance.md`](m0-line-acceptance.md) for the exact accepted path and evidence.
