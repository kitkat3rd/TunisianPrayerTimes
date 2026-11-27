"""Copy the latest completed walk report from an authorized Android debug device."""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from tempfile import NamedTemporaryFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="Directory for the retrieved HTML")
    parser.add_argument("-s", "--serial", help="ADB serial when several devices are connected")
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    package = "com.tunisianprayertimes.dev"
    app = ["run-as", package]
    directory = "no_backup/silence-walk-reports"

    def read(*command):
        result = subprocess.run(adb + list(command), capture_output=True)
        if result.returncode:
            error = result.stderr or result.stdout
            raise SystemExit(error.decode(errors="replace").strip() or "ADB command failed")
        return result.stdout

    response = read("shell", "am", "broadcast", "--user", "current", "--include-stopped-packages", "--receiver-foreground",
                    "-n", package + "/com.tunisianprayertimes.mosque.trace.LatestWalkReportReceiver",
                    "-a", package + ".LATEST_WALK_REPORT").decode(errors="replace")
    match = re.search(r'\bdata="(none|silence-walk-(\d+)\.html)"', response)
    if not match:
        raise SystemExit("The debug app could not identify the latest report.\n" + response.strip())
    if match[1] == "none":
        raise SystemExit("There is no completed walk on this device yet.")
    latest = int(match[2])
    remote = directory + "/" + match[1]
    deadline = time.monotonic() + 60
    while subprocess.run(adb + ["shell", *app, "test", "-f", remote], capture_output=True).returncode:
        if time.monotonic() >= deadline:
            raise SystemExit(f"The app has not finished saving walk {latest}.")
        time.sleep(1)
    document = read("exec-out", *app, "cat", remote)
    data = re.search(rb'<script type="application/geo\+json" id="trace">(.*?)</script>', document, re.S)
    if not data or not document.rstrip().endswith(b"</html>"):
        raise SystemExit("The retrieved HTML report is incomplete.")
    session = json.loads(data[1])["session"]
    if session["id"] != latest or session["endedAt"] is None:
        raise SystemExit("The retrieved report does not match the completed recording.")

    args.destination.mkdir(parents=True, exist_ok=True)
    path = args.destination / f"silence-walk-{int(session['startedAt'])}-{latest}.html"
    temp = NamedTemporaryFile(dir=args.destination, delete=False)
    partial = Path(temp.name)
    try:
        with temp:
            temp.write(document)
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    print(path.resolve())


if __name__ == "__main__":
    main()
