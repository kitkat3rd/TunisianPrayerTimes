"""Check a production APK or app bundle for debug walk recorder components, assets and runtime markers."""

import argparse
import re
import sys
from pathlib import Path
from zipfile import ZipFile


def find_markers(data, markers):
    # Android binary XML and resource tables may use UTF-8 or UTF-16LE string pools.
    return [marker for marker in markers if any(
        marker.encode(encoding) in data for encoding in ("utf-8", "utf-16le")
    )]


def inspect(artifact):
    components = ("SilenceWalkActivity", "SilenceWalkShortcutActivity", "SilenceWalkRecorderService",
                  "DebugTunisianPrayerTimesApplication")
    implementations = components + ("SilenceTraceStore", "SilenceWalkExport", "SilenceWalkSensors", "silence-walks.db",
                                    "silence-walk://recordings", "silence_walk_recorder", "debug_silence_walk",
                                    "START_SILENCE_WALK", "STOP_SILENCE_WALK",
                                    "silence_walk_start", "silence_walk_stop")
    failures = []
    with ZipFile(artifact) as archive:
        names = archive.namelist()
        bundle = "BundleConfig.pb" in names
        manifest = "base/manifest/AndroidManifest.xml" if bundle else "AndroidManifest.xml"
        resources = "base/resources.pb" if bundle else "resources.arsc"
        asset = "assets/mosques/silence-walk.html"
        dex = [name for name in names if re.fullmatch(r"(?:[^/]+/dex/)?classes(?:[0-9]+)?\.dex", name)]
        if not dex:
            raise ValueError("No Android classes.dex in " + str(artifact))
        failures.extend(name for name in names if name == asset or name.endswith("/" + asset))
        resource_markers = ("silence_walk_title", "silence_walk_notification", "trace_mosque_sampling_precise",
                            "trace_prayer_observe_alarm", "shortcut_stop_walk_recording")
        checks = {
            manifest: components,
            resources: resource_markers,
        }
        if bundle:
            checks.update({name: components for name in names if name.endswith("/manifest/AndroidManifest.xml")})
            checks.update({name: resource_markers for name in names if name.endswith("/resources.pb")})
        checks.update({name: implementations for name in dex})
        for filename, markers in checks.items():
            for marker in find_markers(archive.read(filename), markers):
                failures.append(f"{filename} contains {marker}")
    return failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, help="Path to the production APK or AAB")
    path = parser.parse_args().artifact
    found = inspect(path)
    if found:
        print("\n".join(found), file=sys.stderr)
        sys.exit(1)
    print(f"Passed: {path.name} contains no walk recorder components, assets or runtime markers.")
