"""Trace the arch frame and the minaret out of the concept render V1-seal-badge.png.

Usage: python -I trace.py     (writes _gen/traced.json, then run gen.py)

The render is a flat picture. This turns its two subjects into exact, mirror-symmetrical
outlines in source pixels:

  frame    the ivory horseshoe arch with its bottom bar (the render's badge removed
           and the bar rebuilt behind it)
  tower    the minaret's silhouette up to the top of its dome, windows cut out (the
           part of the base that the render's badge covered is rebuilt)
  light    the lighter stone on the tower (battlements, dome)
  dark     the shadow lines on the tower

Every mask is made symmetrical before tracing by mirroring its left half about the
frame's own axis.
"""

import json
from pathlib import Path

import cv2
import numpy as np
import potrace
from shapely.geometry import Polygon, mapping
from shapely.ops import unary_union

HERE = Path(__file__).resolve().parent
SRC = HERE.parent / "V1-seal-badge.png"

TEAL, IVORY = (2, 93, 84), (246, 237, 216)
SAND, LIGHT, DARK = (216, 156, 89), (240, 189, 117), (172, 123, 70)


def dist(rgb, colour):
    return np.sqrt(((rgb - np.array(colour, float)) ** 2).sum(axis=2))


def keep_big(mask, min_area):
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=8)
    out = np.zeros_like(mask)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] >= min_area:
            out |= labels == i
    return out


def opened(mask, k):
    return cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_OPEN, np.ones((k, k), np.uint8)).astype(bool)


def mirror_left(mask, axis2):
    """Keep the half left of x = axis2 / 2 and mirror it onto the right."""
    out = np.zeros_like(mask)
    w = mask.shape[1]
    for x in range(0, axis2 // 2 + 1):
        out[:, x] = mask[:, x]
        if 0 <= axis2 - x < w:
            out[:, axis2 - x] = mask[:, x]
    return out


def best_axis2(mask):
    xs = np.nonzero(mask)[1]
    guess = int(round(2 * xs.mean()))
    w = mask.shape[1]

    def score(a2):
        lo, hi = max(0, a2 - (w - 1)), min(w - 1, a2)
        a = mask[:, lo:hi + 1]
        return (a & a[:, ::-1]).sum()

    return max(range(guess - 30, guess + 31), key=score)


def trace(mask):
    """Mask to a shapely geometry in pixel units (potrace curves, even-odd)."""
    # This Bitmap takes True as paper and False as ink.
    plist = potrace.Bitmap(~mask).trace(turdsize=12, alphamax=1.0, opticurve=True, opttolerance=0.2)
    geom = Polygon()
    rings = []
    for curve in plist:
        pts = [(curve.start_point.x, curve.start_point.y)]
        for seg in curve.segments:
            if seg.is_corner:
                pts += [(seg.c.x, seg.c.y), (seg.end_point.x, seg.end_point.y)]
            else:
                p0, p1, p2, p3 = pts[-1], (seg.c1.x, seg.c1.y), (seg.c2.x, seg.c2.y), (seg.end_point.x, seg.end_point.y)
                for i in range(1, 13):
                    t = i / 12
                    u = 1 - t
                    pts.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                                u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
        if len(pts) >= 4:
            rings.append(Polygon(pts).buffer(0))
    for ring in sorted(rings, key=lambda r: -r.area):
        geom = geom.symmetric_difference(ring)
    return geom


def main():
    rgb = cv2.cvtColor(cv2.imread(str(SRC)), cv2.COLOR_BGR2RGB).astype(float)
    h, w = rgb.shape[:2]
    d = {name: dist(rgb, c) for name, c in dict(teal=TEAL, ivory=IVORY, sand=SAND, light=LIGHT, dark=DARK).items()}
    warm_d = np.minimum(np.minimum(d["sand"], d["light"]), d["dark"])

    # The minaret: warm pixels, without the thin warm fringe that edges of ivory on teal pick up.
    warm = keep_big(opened((warm_d < d["teal"]) & (warm_d < d["ivory"]), 5), 4000)
    ivory = (d["ivory"] < d["teal"]) & ~cv2.dilate(warm.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    ivory = keep_big(opened(ivory, 3), 4000)

    # The render's badge: the ivory below the frame's bar is all badge. Fit its circle.
    rows = np.nonzero(ivory.any(axis=1))[0]
    widths = ivory.sum(axis=1)
    leg_row = np.nonzero(ivory[int(rows.max() - 0.3 * np.ptp(rows))])[0]  # a row through the two legs
    frame_w = np.ptp(leg_row) + 1
    # The bar: rows where the ivory runs from leg to leg (the badge's glyph leaves holes in it).
    bar_rows = [y for y in rows if y > h / 2 and widths[y] > frame_w * 0.6
                and np.ptp(np.nonzero(ivory[y])[0]) > frame_w * 0.95]
    bar_top, bar_bottom = min(bar_rows), max(bar_rows)
    below = np.argwhere(ivory[bar_bottom + 3:]) + [bar_bottom + 3, 0]
    cx = below[:, 1].mean()
    lowest = below[:, 0].max()
    half = np.ptp(below[below[:, 0] == below[:, 0].min()][:, 1]) / 2
    k = lowest - (bar_bottom + 3)
    radius = (half**2 + k**2) / (2 * k)
    cy = lowest - radius
    yy, xx = np.mgrid[0:h, 0:w]
    disc = (xx - cx) ** 2 + (yy - cy) ** 2 <= (radius + 6) ** 2
    print(f"badge in the render: centre ({cx:.0f}, {cy:.0f}), radius {radius:.0f}px; bar rows {bar_top}..{bar_bottom}")

    # Frame: drop the badge, rebuild the bar behind it.
    x0 = np.nonzero(ivory[bar_top + 5])[0].min()
    x1 = np.nonzero(ivory[bar_top + 5])[0].max()
    frame = ivory & ~disc
    frame[bar_top:bar_bottom + 1, x0:x1 + 1] = True

    # Tower: rebuild the base behind the badge by filling each row between its outer edges.
    tower = warm.copy()
    for y in range(int(cy - radius) - 8, bar_top):
        xs = np.nonzero(warm[y] & ~disc[y])[0]
        if len(xs):
            tower[y, xs.min():xs.max() + 1] = True
    tower[bar_top:] = False
    # Cut the finial off at the top of the dome: the first row, going up, narrower than 22px.
    tw = tower.sum(axis=1)
    top = np.nonzero(tw)[0].min()
    dome_top = next(y for y in range(top + 200, top, -1) if tw[y] < 22) + 1
    tower[:dome_top] = False

    inside = tower  # detail masks live inside the tower only
    light = opened(inside & (d["light"] < d["sand"]) & (d["light"] < d["dark"]) & ~disc, 3)
    dark = opened(inside & (d["dark"] < d["sand"]) & (d["dark"] < d["light"]) & ~disc, 3)
    # Edge pixels of the tower mix sand with teal and land on "dark": keep dark lines off the outline.
    core = cv2.erode(cv2.morphologyEx(tower.astype(np.uint8), cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8)),
                     np.ones((7, 7), np.uint8)).astype(bool)
    holes = cv2.dilate((~tower & core).astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
    dark &= core & ~holes

    axis2 = best_axis2(frame)
    print(f"axis x = {axis2 / 2}px (picture centre {(w - 1) / 2})")
    out = {"axis": axis2 / 2, "bar_top": int(bar_top), "bar_bottom": int(bar_bottom), "dome_top": int(dome_top)}
    for name, mask in dict(frame=frame, tower=tower, light=light, dark=dark).items():
        geom = trace(mirror_left(mask, axis2))
        out[name] = mapping(geom)
        print(f"{name}: {geom.geom_type}, area {geom.area:.0f}px2, bounds {[round(v) for v in geom.bounds]}")
        cv2.imwrite(str(HERE / f"mask-{name}.png"), mirror_left(mask, axis2).astype(np.uint8) * 255)
    (HERE / "traced.json").write_text(json.dumps(out), encoding="utf-8")


if __name__ == "__main__":
    main()
