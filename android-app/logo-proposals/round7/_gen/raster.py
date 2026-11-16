"""Rasterise icon SVGs with headless Chrome.

Usage: python -I raster.py <file.svg> <out.png> [size=864] [--visible]

Draws the 108dp canvas at `size` px on a transparent page. --visible draws only the
central 72dp (what a launcher shows) at `size` px instead.
"""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "round2" / "_tools"))
from render_preview import chrome, data_uri  # noqa: E402


def raster(svg: Path, out: Path, size: int = 864, visible: bool = False) -> None:
    full = size * 108 / 72 if visible else size
    off = -size * 18 / 72 if visible else 0
    work = Path(tempfile.mkdtemp(prefix="iconraster-"))
    try:
        page = work / "page.html"
        page.write_text(
            '<!doctype html><html><body style="margin:0;background:transparent;overflow:hidden">'
            f'<img src="{data_uri(svg)}" style="position:absolute;left:{off}px;top:{off}px;'
            f'width:{full}px;height:{full}px"></body></html>', encoding="utf-8")
        subprocess.run(
            [chrome(), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
             "--default-background-color=00000000", f"--user-data-dir={work / 'profile'}",
             f"--window-size={size},{size}", f"--screenshot={out}", page.as_uri()],
            check=True, capture_output=True, timeout=90)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--visible"]
    raster(Path(args[0]).resolve(), Path(args[1]).resolve(), int(args[2]) if len(args) > 2 else 864,
           "--visible" in sys.argv)
    print(Path(args[1]).resolve())
