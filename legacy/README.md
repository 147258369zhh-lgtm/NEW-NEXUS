# Legacy archive

This directory keeps legacy source snapshots that are useful for understanding earlier Eta behavior or for selective migration research.

## Contents

- `pre-upstream-20261105/` contains the pre-upstream source snapshot copied from the historical ETA workspace.

## Rules

- Legacy code is not built as part of Nexus.
- Legacy dependencies do not flow into `:app` or `kernel-api`.
- Do not copy a legacy implementation into Nexus without mapping it to the current Kernel, permission, data-ownership and presentation contracts.
- The primary product baseline remains Eta 4.11.8 at commit `8f005e3`; this archive is supplementary.
- Do not modify the separately installed legacy Eta workspace as part of routine Nexus development.

