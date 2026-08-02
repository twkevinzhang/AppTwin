# Virtualization core audit

MaskAccounts does not currently vendor a third-party app virtualization engine. The candidate
review found no modern source tree that is both technically suitable and supported by a clear,
continuous license provenance.

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
  is not enough evidence for MaskAccounts to copy or cherry-pick the runtime.

## Decision

The production tree remains clean-room GPL-3.0-or-later. Historical projects can be used to learn
concepts and Android service boundaries, but code must not be copied until its exact source and
license chain are documented. A future runtime needs independently implemented arm64 hooks,
virtual PackageManager/ActivityManager routing, component lifecycle, provider/binder identity,
permission mediation, and I/O redirection.
