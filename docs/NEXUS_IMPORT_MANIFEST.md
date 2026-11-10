# Nexus source import manifest

## Imported from legacy Eta

- `app/src` including production and unit-test source.
- Gradle wrapper, version catalog and build configuration.
- `proguard-rules.pro`, native source assets and local module resources required by the app.
- `.github` workflows and release documentation as a starting point.
- Existing scripts and technical reports.
- Legacy pre-upstream source snapshot under `legacy/pre-upstream-20261105`.
- License and attribution files.

## Deliberately excluded

- Build directories, Gradle caches, generated APKs, local SDK paths and temporary logs.
- External research checkouts and unrelated local projects.
- Signing material, plaintext credentials and user data.
- The separate `D:\AI\Nexus` project, which is unrelated to this Android product.

## Current source transformation

- Product identity and install isolation changed for Nexus.
- New `kernel-api` module added.
- Core contracts, Phase 0 documents, migration schema and dependency audits added.
- Business execution behavior remains the Eta baseline until the shadow ledger and later refactors are implemented and verified.

## Provenance

- Product baseline: Eta 4.11.8.
- Baseline commit: `8f005e3`.
- New repository: `https://github.com/147258369zhh-lgtm/NEW-NEXUS.git`.
- The legacy Eta checkout remains the source of provenance and must not be deleted or overwritten.
