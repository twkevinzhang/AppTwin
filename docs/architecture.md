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
                 /      |      \
          instance-1 instance-2 instance-3
              data/      data/      data/
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
- Package broadcasts refresh the foreground UI; foreground/startup reconciliation remains the
  source of truth because Android does not guarantee background delivery to a killed host.
- `MANAGE_EXTERNAL_STORAGE` is explicitly user-granted. The launcher probes direct visibility of
  `Download`, `DCIM`, and `Pictures` without recording file names.

## Not implemented yet

- Loading guest code into an isolated process and launching guest activities.
- Virtual PackageManager, ActivityManager, services, providers, jobs, alarms, and Binder identity.
- Native I/O redirection from a guest package path to an instance `data/` root.
- Per-instance permission policy and Android framework API mediation.
- Notification routing, FCM/GMS integration, deep links, camera, microphone, voice/video, and
  background survival.
- Guest update migration tests across two real Play versions.

Until those runtime items exist, an instance record is a reserved isolated data root, not a working
clone. The UI and README must continue to state this limitation.
