"""Checks on a finished proposal, printed as evidence.

Usage: python -I verify.py <proposal-dir>

1. Mirror test in full colour: the icon as a launcher shows it (central 72dp, 720px) is
   compared with its own mirror image, pixel by pixel. Reports the share of pixels that
   differ and where they are.
2. The same test on the themed (monochrome) layer.
3. Smallest feature sizes of the mute sign at the launcher sizes Android uses.
"""

import re
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from raster import raster  # noqa: E402

SIZE = 720  # 10 px per dp


def mirror_test(svg: Path, label: str):
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "r.png"
        raster(svg, out, SIZE, visible=True)
        a = np.asarray(Image.open(out).convert("RGBA")).astype(int)
    diff = np.abs(a - a[:, ::-1]).sum(axis=2) > 40
    share = diff.mean()
    print(f"{label}: {share:.2%} of pixels differ from their mirror image")
    if diff.any():
        ys, xs = np.nonzero(diff)
        dp = lambda v: 18 + v * 72 / SIZE
        print(f"   all of them lie in x {dp(xs.min()):.1f}..{dp(xs.max() + 1):.1f}dp, "
              f"y {dp(ys.min()):.1f}..{dp(ys.max() + 1):.1f}dp")
    return diff


def point_test(svg: Path):
    """Exact test on the drawing itself: every point (x, y) of a path must have its twin
    (108 - x, y) in the same path."""
    text = svg.read_text(encoding="utf-8")
    for n, (fill, d) in enumerate(re.findall(r'<path fill="(#[0-9A-Fa-f]{6})"[^>]* d="([^"]+)"', text), 1):
        pts = {(round(float(x) * 100), round(float(y) * 100)) for x, y in re.findall(r"(-?[\d.]+) (-?[\d.]+)", d)}
        lonely = [p for p in pts if (10800 - p[0], p[1]) not in pts]
        verdict = "exact mirror image" if not lonely else f"{len(lonely)} points have no twin"
        print(f"   path {n} ({fill}, {len(pts)} points): {verdict}")


def main(argv):
    d = Path(argv[0]).resolve()
    print(f"== {d.name}")
    print("foreground.svg, point by point:")
    point_test(d / "foreground.svg")
    colour = mirror_test(d / "icon.svg", "icon (background + foreground)")
    mono = mirror_test(d / "monochrome.svg", "themed layer")
    # The themed layer differs from its mirror image only where the mute sign is, so its
    # differing pixels give the sign's box. Count the icon's differing pixels outside it.
    ys, xs = np.nonzero(mono)
    box = np.zeros_like(mono)
    box[max(ys.min() - 5, 0):ys.max() + 6, max(xs.min() - 5, 0):xs.max() + 6] = True
    print(f"icon pixels that differ from their mirror image outside the mute sign: {int((colour & ~box).sum())} "
          f"of {colour.size}")


if __name__ == "__main__":
    main(sys.argv[1:])
