# Virtual runtime upstream

This module is a downstream port of the `VirtualApp/lib` directory shipped in
[VirtualXposed 0.22.0](https://github.com/android-hacker/VirtualXposed).

- Repository: `https://github.com/android-hacker/VirtualXposed.git`
- Upstream commit: `122beb371519cb2d221ce06756361aaa30e2674f`
- Upstream release commit message: `0.22.0`
- Upstream author: tiann `<twsxtd@gmail.com>`
- Imported path: `VirtualApp/lib`
- Imported on: 2026-08-03
- Upstream license: GNU GPL version 3; see `UPSTREAM-LICENSE.txt`

## Downstream changes

- Migrated the library from AGP 3.2.1 / compile SDK 28 to the MaskAccounts AGP
  build and compile SDK 36.
- Restricted native output to `arm64-v8a` and raised the native platform floor
  to API 21.
- Removed `exposed-core`, Xposed API, and EPIC dependencies and deleted the
  Xposed module-loading branch from `VClientImpl`.
- Kept FreeReflection 3.0.1 as vendored source from upstream commit `f1a175e`;
  its MIT license is in `licenses/FreeReflection-LICENSE.txt`.
- Preserved upstream Java package names and copyright headers to keep the port
  auditable against its origin.

This is an initial compile/runtime probe, not a claim of Android 16 functional
compatibility. Hidden-API and framework-internal behavior still requires device
validation.

