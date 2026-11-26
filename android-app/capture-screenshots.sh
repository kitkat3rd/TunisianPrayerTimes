#!/usr/bin/env bash
#
# Capture Play Store screenshots from the installed phone and tablet apps.
# A fresh install shows the place question. An established install starts at Today.
# Requires adb, Python 3, and a running device for each selected serial.
set -euo pipefail

if [ -n "${ANDROID_HOME:-}" ]; then
    sdk_dir="$ANDROID_HOME"
    if command -v cygpath >/dev/null; then sdk_dir=$(cygpath -u "$sdk_dir"); fi
    export PATH="$sdk_dir/platform-tools:$PATH"
elif [ -d "$HOME/Library/Android/sdk/platform-tools" ]; then
    export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
fi
# Android component names and on-device paths are arguments to adb, including in Git Bash.
export MSYS2_ARG_CONV_EXCL='*'

OUT_DIR="${OUT_DIR:-$(cd "$(dirname "$0")" && pwd)/screenshots}"
mkdir -p "$OUT_DIR"
PHONE_SERIAL="${PHONE_SERIAL:-emulator-5554}"
PHONE_PREFIX="${PHONE_PREFIX:-phone}"
TABLET_SERIAL="${TABLET_SERIAL:-emulator-5556}"
TABLET_PREFIX="${TABLET_PREFIX:-tablet}"
CAPTURE_PHONE="${CAPTURE_PHONE:-1}"
CAPTURE_TABLET="${CAPTURE_TABLET:-1}"

if command -v python3 >/dev/null && python3 -c 'import xml.etree.ElementTree' 2>/dev/null; then
    PYTHON=python3
else
    PYTHON=python
    "$PYTHON" -c 'import xml.etree.ElementTree; import sys; assert sys.version_info.major == 3'
fi

detect_package() {
    local serial="$1" installed pkg
    installed=$(adb -s "$serial" shell pm list packages com.tunisianprayertimes | tr -d '\r')
    local candidates=(com.tunisianprayertimes.dev com.tunisianprayertimes)
    if [ -n "${SCREENSHOT_PACKAGE:-}" ]; then candidates=("$SCREENSHOT_PACKAGE"); fi
    for pkg in "${candidates[@]}"; do
        if grep -Fxq "package:$pkg" <<< "$installed"; then
            echo "$pkg"
            return 0
        fi
    done
    echo "No matching installed package on $serial." >&2
    return 1
}

capture() {
    local serial="$1" name="$2" file
    file=$(mktemp "$OUT_DIR/.capture.XXXXXX")
    if ! adb -s "$serial" exec-out screencap -p > "$file" || [ ! -s "$file" ]; then
        rm -f "$file"
        return 1
    fi
    mv -f "$file" "$OUT_DIR/$name"
    echo "  ✓ $name"
}

# Look up the actual accessible node, within the app's window only. Each key has
# a visible-label fallback for installs that don't export Compose test tags.
# Output is the matched key and the centre of the node's current visible bounds.
find_node() {
    local serial="$1" pkg="$2"
    shift 2
    local dump="/data/local/tmp/tpt-capture-$$.xml"
    adb -s "$serial" shell "uiautomator dump $dump >/dev/null && cat $dump && rm -f $dump" |
        "$PYTHON" -c '
import re
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.fromstring(sys.stdin.buffer.read())
except ET.ParseError:
    sys.exit(1)
package, *keys = sys.argv[1:]
labels = {
    "start": "مواقيت الصلاة في منطقتك",
    "manual": "اختيار مدينة",
    "picker": "ابحث عن مدينة أو حيّ…",
    "today": "الصلاة القادمة",
    "prayers": "الصلاة",
    "worship": "عباداتي",
}
tags = {"manual": "onboarding_location_pick", "picker": "locality_search"}
place_sources = ("حسب الموقع المختار", "حسب آخر موقع معروف", "حسب موقعك الحالي")
for key in keys:
    for node in root.iter("node"):
        a = node.attrib
        if a.get("package") != package or a.get("visible-to-user") == "false":
            continue
        label, desc = a.get("text", ""), a.get("content-desc", "")
        tag = a.get("resource-id", "").rsplit("/", 1)[-1]
        if key == "row":
            matches = a.get("enabled") == "true" and a.get("clickable") == "true" and (
                tag.startswith(("locality_row_", "city_row_")) or a.get("checkable") == "true"
                or a.get("class") == "android.widget.RadioButton"
            )
        elif key == "direction":
            matches = label.startswith(place_sources) or desc.startswith(place_sources)
        else:
            matches = labels[key] in (label, desc) or (key in tags and tag == tags[key])
        if matches:
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
            if bounds:
                left, top, right, bottom = map(int, bounds.groups())
                if right > left and bottom > top:
                    print(key, (left + right) // 2, (top + bottom) // 2)
                    sys.exit(0)
sys.exit(1)
' "$pkg" "$@"
}

wait_node() {
    local serial="$1" pkg="$2" attempt node
    shift 2
    for ((attempt=0; attempt<8; attempt++)); do
        if node=$(find_node "$serial" "$pkg" "$@"); then
            printf '%s\n' "$node"
            return 0
        fi
        sleep 1
    done
    echo "No visible $* control in $pkg on $serial." >&2
    return 1
}

tap_node() {
    local serial="$1" node="$2" key x y
    read -r key x y <<< "$node"
    adb -s "$serial" shell input tap "$x" "$y"
}

capture_device() {
    local serial="$1" prefix="$2" pkg first manual row worship
    pkg=$(detect_package "$serial")
    echo "Capturing $pkg on $serial."
    adb -s "$serial" shell am start -W -n "$pkg/com.tunisianprayertimes.MainActivity" >/dev/null
    first=$(wait_node "$serial" "$pkg" start picker today prayers)

    case "${first%% *}" in
        start)
            capture "$serial" "${prefix}-onboarding-place.png"
            manual=$(wait_node "$serial" "$pkg" manual)
            tap_node "$serial" "$manual"
            ;;
        prayers)
            # A running, configured app may have reopened another tab.
            tap_node "$serial" "$first"
            ;;
    esac
    if [[ $first == start\ * || $first == picker\ * ]]; then
        wait_node "$serial" "$pkg" picker >/dev/null
        row=$(wait_node "$serial" "$pkg" row)
        capture "$serial" "${prefix}-onboarding-city-picker.png"
        tap_node "$serial" "$row"
    fi
    wait_node "$serial" "$pkg" today >/dev/null
    capture "$serial" "${prefix}-main-screen.png"

    # The compass can use the selected locality or an existing device fix.
    # An unavailable direction is not a publishable compass screenshot.
    worship=$(wait_node "$serial" "$pkg" worship)
    tap_node "$serial" "$worship"
    if wait_node "$serial" "$pkg" direction >/dev/null; then
        capture "$serial" "${prefix}-qibla.png"
    else
        echo "  Skipping Qibla screenshot because a location is unavailable on $serial." >&2
    fi
}

devices=$(adb devices | tr -d '\r')
pids=()
capture_if_connected() {
    local enabled="$1" serial="$2" prefix="$3"
    if [[ $enabled != 0 ]] && awk -v id="$serial" '$1==id && $2=="device" { found=1 } END { exit !found }' <<< "$devices"; then
        capture_device "$serial" "$prefix" &
        pids+=($!)
    fi
}
capture_if_connected "$CAPTURE_PHONE" "$PHONE_SERIAL" "$PHONE_PREFIX"
capture_if_connected "$CAPTURE_TABLET" "$TABLET_SERIAL" "$TABLET_PREFIX"
if (( ${#pids[@]} == 0 )); then
    echo "No selected device is connected." >&2
    exit 1
fi
status=0
for pid in "${pids[@]}"; do
    wait "$pid" || status=1
done
exit "$status"
