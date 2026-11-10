#!/usr/bin/env python3
"""Fail if Nexus installation/runtime identity collides with legacy Eta."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP_GRADLE = ROOT / "app" / "build.gradle.kts"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
STRINGS = (
    ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml",
    ROOT / "app" / "src" / "main" / "res" / "values-b+zh+Hans" / "strings.xml",
    ROOT / "app" / "src" / "main" / "res" / "values-b+zh+Hant" / "strings.xml",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def main() -> None:
    gradle = text(APP_GRADLE)
    manifest = text(MANIFEST)

    require('applicationId = "io.github.zhh_lgtm.nexus"' in gradle, "Unexpected Nexus applicationId")
    require('output.outputFileName.set(output.versionName.map { "Nexus-v$it.apk" })' in gradle, "APK output name is not Nexus")
    require(
        'android:name="io.github.zhh_lgtm.nexus.permission.CONTROL_ACCESSIBILITY_PROTECTION"' in manifest,
        "Custom permissions are not in the Nexus namespace",
    )
    require(
        'android:authorities="io.github.zhh_lgtm.nexus.accessibility.health"' in manifest,
        "Accessibility provider authority is not in the Nexus namespace",
    )
    require('android:scheme="nexus"' in manifest, "Nexus OAuth scheme is missing")

    forbidden_manifest_fragments = (
        'android:name="io.github.mangi.eta.permission.',
        'android:authorities="io.github.mangi.eta.',
        'android:scheme="eta"',
    )
    for fragment in forbidden_manifest_fragments:
        require(fragment not in manifest, f"Legacy install identity remains in manifest: {fragment}")

    for strings_path in STRINGS:
        strings = text(strings_path)
        require('<string name="app_name">Nexus</string>' in strings, f"Nexus app label missing in {strings_path}")

    print("Nexus identity verification passed")


if __name__ == "__main__":
    main()
