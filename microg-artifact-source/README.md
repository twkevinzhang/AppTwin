# microG artifact source

This module owns the reviewed production release pair for AppTwin's microG compatibility layer:
GmsCore and the matching microG Companion Store. It does
not discover releases, accept runtime URLs, or resolve a mutable `latest` channel.

The APK is intentionally not committed. Prepare the exact reviewed release with either:

```sh
./gradlew :microg-artifact-source:preparePinnedMicrogArtifact
```

or, for deterministic/offline use:

```sh
./gradlew :microg-artifact-source:preparePinnedMicrogArtifact \
  -PmicrogArtifactFile=/absolute/path/com.google.android.gms-250932030.apk \
  -PmicrogCompanionArtifactFile=/absolute/path/com.android.vending-84022630.apk
```

When only one local property is supplied, the task also checks the same directory for the other
fixed filename. Every path verifies both pinned SHA-256 values before a build can package the pair.
Production code then verifies each APK's digest, signature, signer, package name, version code,
staged copy, and exposed compatibility certificate. Missing or mismatched bytes fail the whole
release closed. Neither APK is committed to Git or installed in the physical PackageManager.

## Upstream and license

The reviewed pair is from the upstream microG GmsCore release
[`v0.3.15.250932`](https://github.com/microg/GmsCore/releases/tag/v0.3.15.250932). microG is an
independent open-source implementation and is not Google Play services. Preserve the upstream
Apache-2.0 license and notices when distributing a build that embeds these APK assets; AppTwin's
own GPL license does not change the license or provenance of the unmodified upstream artifacts.
