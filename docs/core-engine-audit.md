# Virtualization core audit and selection

The original candidate review found no modern drop-in tree that was both Android 12-ready and
supported by sufficiently clear license provenance. M0 therefore selected an older, exact GPL
release tree and ports only that auditable source forward.

## Selected M0 baseline

`virtual-runtime` is derived from `VirtualApp/lib` in VirtualXposed 0.22.0, upstream commit
`122beb371519cb2d221ce06756361aaa30e2674f`. That release includes the GNU GPL version 3 license.
The downstream port compiles with SDK 36, builds arm64 only, removes the Xposed/module-loading
branch, and includes Android 12 compatibility fixes required by the LINE probe. Exact provenance,
licenses, and the remaining third-party notice gap are recorded in
[`../virtual-runtime/UPSTREAM.md`](../virtual-runtime/UPSTREAM.md) and
[`../virtual-runtime/THIRD_PARTY_NOTICES.md`](../virtual-runtime/THIRD_PARTY_NOTICES.md).

## Rejected production baselines

### asLody/VirtualApp

- Last commit whose tree contains the complete GPL-3.0 `LICENSE.txt`:
  `fd29f19410caaf56060bc941a19299723b550970`.
- GPL-3.0 license introduced by:
  `38cc2086ea88dd69009093d4d28fe2d11ee445b9`.
- License file deleted by:
  `00f152f98a922ced0d858c31e1a9c2f0afb53ab6`.
- The GPL tree is an Android N-era project using AGP 2.3.1, Gradle 3.3, compile SDK 24, target SDK
  22, and 32-bit `armeabi`/`armeabi-v7a` native hooks. It does not provide an Android 12/16,
  arm64, or modern split-APK baseline.
- Its README contains extra distribution language that appears to conflict with GPL-3.0. Keep the
  commit as historical research evidence; obtain open-source legal review before copying code.

### ServenScorpion/VirtualApp

- Audited head: `0f9165454ef78c7be65ae06a69ec5ac0536a770e`.
- No root license or notice file was present.
- The repository contains opaque/commercial build inputs and cannot be used as an open-source
  production dependency.

### ALEX5402/NewBlackbox

- Audited head: `89b59836c66f173756a4ae258cf379a957649820`.
- A root Apache-2.0 file exists, but 1,635 inspected source files contain no per-file SPDX or GPL
  continuation notice while the README credits VirtualApp as an original framework.
- Its upstream lineage publicly acknowledges structural/IP disputes. The root declaration alone
  is not enough evidence for AppTwin to copy or cherry-pick the runtime.

## Decision

The M0 sideload probe accepts the exact VirtualXposed 0.22.0 GPL tree as a documented downstream
dependency; it does not copy from the later unlicensed or disputed candidate trees above. Binary
distribution remains contingent on resolving the fbjni notice gap listed in the third-party
notices. This selection is adequate for source development and device probing, not a conclusion
that the legacy runtime is production-ready or Android 16-compatible.
