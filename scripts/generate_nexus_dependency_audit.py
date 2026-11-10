#!/usr/bin/env python3
"""Generate a per-file import audit for the Nexus migration baseline."""

from __future__ import annotations

import csv
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = (
    ROOT / "app" / "src" / "main" / "kotlin" / "io" / "github" / "mangi" / "eta",
    ROOT / "kernel-api" / "src" / "main" / "kotlin" / "io" / "github" / "zhh_lgtm" / "nexus",
    ROOT / "application-api" / "src" / "main" / "kotlin" / "io" / "github" / "zhh_lgtm" / "nexus",
)
OUTPUT = ROOT / "docs" / "nexus" / "07_BASELINE_IMPORTS.csv"

PROJECT_IMPORT = re.compile(r"^\s*import\s+io\.github\.(?:mangi\.eta|zhh_lgtm\.nexus(?:\.kernel)?)\.([A-Za-z0-9_]+)")
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)")

LAYER = {
    "agent": "runtime",
    "data": "data",
    "fork": "feature",
    "hook": "system-adapter",
    "ui": "presentation",
    "core": "shared-infrastructure",
    "config": "configuration",
    "systemizer": "system-adapter",
}

FORBIDDEN = {
    ("runtime", "presentation"),
    ("data", "presentation"),
    ("data", "runtime"),
    ("data", "feature"),
    ("feature", "presentation"),
    ("system-adapter", "presentation"),
    ("system-adapter", "feature"),
}


def classify(source_layer: str, target_layer: str) -> str:
    if source_layer == target_layer:
        return "same-layer"
    if (source_layer, target_layer) in FORBIDDEN:
        return "forbidden-reverse-dependency"
    if source_layer == "presentation" and target_layer in {"runtime", "data", "feature"}:
        return "baseline-ui-direct-dependency"
    return "cross-layer-review"


def project_section(package_name: str) -> str:
    if package_name.startswith("io.github.mangi.eta."):
        return package_name.removeprefix("io.github.mangi.eta.").split(".", 1)[0]
    if package_name.startswith("io.github.zhh_lgtm.nexus.kernel."):
        return "kernel-api"
    if package_name.startswith("io.github.zhh_lgtm.nexus.application."):
        return "application-api"
    return package_name.split(".", 1)[0] if package_name else ""


def main() -> None:
    rows: list[dict[str, object]] = []
    for source_root in SOURCE_ROOTS:
        if not source_root.exists():
            continue
        for file in sorted(source_root.rglob("*.kt")):
            lines = file.read_text(encoding="utf-8").splitlines()
            package_name = next((match.group(1) for line in lines if (match := PACKAGE.match(line))), "")
            source_section = project_section(package_name)
            source_layer = {
                "kernel-api": "kernel-contract",
                "application-api": "application-contract",
            }.get(source_section, LAYER.get(source_section, "unknown"))
            for line_number, line in enumerate(lines, start=1):
                match = PROJECT_IMPORT.match(line)
                if not match:
                    continue
                target_section = match.group(1)
                target_layer = LAYER.get(target_section, "unknown")
                rows.append(
                    {
                        "source_file": file.relative_to(ROOT).as_posix(),
                        "line": line_number,
                        "source_package": package_name,
                        "source_section": source_section,
                        "source_layer": source_layer,
                        "target_section": target_section,
                        "target_layer": target_layer,
                        "classification": classify(source_layer, target_layer),
                        "imported_project_package": line.strip().removeprefix("import ").strip(),
                    }
                )

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]) if rows else [
            "source_file", "line", "source_package", "source_section", "source_layer",
            "target_section", "target_layer", "classification", "imported_project_package",
        ])
        writer.writeheader()
        writer.writerows(rows)

    print(f"Wrote {len(rows)} imports to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
