"""Round 5: three launcher icons designed from the brief alone, not from round 3's icon.

Usage: python -I gen.py

The brief: prayer times for Tunisia; the phone goes quiet while you pray.

- 01-five-stones: a horseshoe arch of five stones (the five prayers) with the muted
  speaker standing in the doorway.
- 02-pause-dome: a dome on two pillars; the pillars are the pause sign.
- 03-kairouan-moon: Kairouan's stepped minaret in the mouth of a crescent, which is
  also the phone's quiet-mode moon.

Only the SVG writers and the speaker glyph come from round 3's gen2.
"""

import math
import sys
from pathlib import Path

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "round3" / "_gen"))
import gen2  # noqa: E402

gen2.SCALE, gen2.CENTRE_LOCAL = 1.0, (54.0, 54.0)  # draw in canvas units
from gen2 import bezier, crescent, merged, path, svg  # noqa: E402

CX = 54.0
TEAL, TEAL_DARK = "#00695C", "#004D40"
CREAM, GOLD = "#F5EFE5", "#E3BC5A"


def polar(c, r, deg):
    a = math.radians(deg)
    return (c[0] + r * math.cos(a), c[1] - r * math.sin(a))


def sector(c, r_in, r_out, a0, a1, steps=96):
    outer = [polar(c, r_out, a0 + (a1 - a0) * i / steps) for i in range(steps + 1)]
    inner = [polar(c, r_in, a1 + (a0 - a1) * i / steps) for i in range(steps + 1)]
    return Polygon(outer + inner)


def rounded(x0, y0, x1, y1, r):
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, 16)


def radial_bg(inner, outer):
    return gen2.radial_bg(inner, outer)


def flat_bg(colour):
    return svg(f'<rect width="108" height="108" fill="{colour}"/>')


# ---------------------------------------------------------------- 01 five stones


def five_stones():
    """A pointed horseshoe arch in five stones (two legs, two shoulders, a keystone) and the speaker."""
    cy, d, r_in, r_out, gap = 51.0, 6.0, 19.0, 27.5, 1.7
    spring, foot = cy + 4.0, 78.0  # the arch returns inward below cy: a horseshoe

    def pointed(r):  # two arcs of radius r, their centres d either side of the axis
        return Point(CX + d, cy).buffer(r, 128).intersection(Point(CX - d, cy).buffer(r, 128))

    arch = pointed(r_out).difference(pointed(r_in)).intersection(box(0, 0, 108, spring))
    x_in = math.sqrt(r_in**2 - (spring - cy) ** 2) - d
    x_out = math.sqrt(r_out**2 - (spring - cy) ** 2) - d
    leg = box(CX + x_in, spring - 0.05, CX + x_out, foot)
    whole = unary_union([arch, leg, affinity.scale(leg, xfact=-1, origin=(CX, 0))])
    cuts = [LineString([polar((CX, cy), 5, a), polar((CX, cy), 40, a)]).buffer(gap / 2, cap_style=2)
            for a in (24, 68, 112, 156)]
    # Left to right: the angle from straight up, negative on the left.
    stones = sorted(whole.difference(unary_union(cuts)).geoms,
                    key=lambda g: math.atan2(g.centroid.x - CX, cy - g.centroid.y))
    stones = [s.buffer(-0.6, 16).buffer(0.6, 16) for s in stones]
    _, glyph = gen2.mute_badge(CX - 0.7, 62.0, 12.5)
    return stones, glyph


def five_stones_icon():
    stones, glyph = five_stones()
    assert len(stones) == 5
    tones = [CREAM, GOLD, CREAM, GOLD, CREAM]
    fg = [path(s, t) for s, t in zip(stones, tones)] + [path(glyph, CREAM)]
    return radial_bg("#007567", TEAL_DARK), fg, unary_union(stones + [glyph])


# ---------------------------------------------------------------- 02 pause dome


def pause_dome():
    bar = rounded(CX + 6.0, 59.0, CX + 17.0, 80.0, 1.6)
    bars = unary_union([bar, affinity.scale(bar, xfact=-1, origin=(CX, 0))])
    base, apex = 55.0, 30.5
    left = bezier((CX - 19.5, base), (CX - 21.5, 44.0), (CX - 9.0, 39.5), (CX, apex))
    dome = Polygon(left + [(2 * CX - x, y) for x, y in left[::-1]]).buffer(0)
    stem = box(CX - 0.6, 27.2, CX + 0.6, apex + 1.5)
    moon = crescent(CX, 25.0, 3.1, 1.25, -1.05, 2.6)
    return dome, bars, unary_union([stem, moon])


def pause_dome_icon():
    dome, bars, finial = pause_dome()
    amber = "#D39A35"
    fg = [path(dome, TEAL), path(finial, amber), path(bars, amber)]
    return flat_bg(CREAM), fg, unary_union([dome, bars, finial])


# ---------------------------------------------------------------- 03 Kairouan moon


def teeth(cx, y, width, count, h):
    """Square merlons standing on y, flush with both ends."""
    w = width / (2 * count - 1)
    return unary_union([box(cx - width / 2 + 2 * w * i, y - h, cx - width / 2 + 2 * w * i + w, y + 0.05)
                        for i in range(count)])


def arched(cx, top, w, h):
    return gen2.arched_window(cx, top, w, h)


def kairouan(cx, base):
    """Three stepped tiers and a dome, in blocks: (tower, its five openings)."""
    y1, y2, y3 = base - 25.0, base - 33.0, base - 39.5
    t1 = Polygon([(cx - 9.0, base), (cx - 8.0, y1), (cx + 8.0, y1), (cx + 9.0, base)])
    t2 = box(cx - 5.6, y2, cx + 5.6, y1)
    t3 = box(cx - 3.6, y3, cx + 3.6, y2)
    dome = Point(cx, y3).buffer(3.6, 48).intersection(box(0, 0, 108, y3 + 0.05))
    finial = unary_union([box(cx - 0.5, y3 - 5.8, cx + 0.5, y3 - 3.0), Point(cx, y3 - 6.0).buffer(1.0, 24)])
    tower = unary_union([t1, t2, t3, dome, finial, teeth(cx, y1, 16.0, 5, 1.7), teeth(cx, y2, 11.2, 4, 1.5)])
    openings = unary_union(
        [rounded(cx - 1.1, y1 + dy, cx + 1.1, y1 + dy + 3.0, 0.3) for dy in (5.0, 11.0, 17.0)]
        + [arched(cx, y2 + 2.8, 3.0, 4.0), arched(cx, y3 + 1.6, 2.4, 3.6)]
    )
    return tower, openings


def kairouan_moon():
    moon = crescent(48.0, 50.0, 25.0, 9.5, -5.5, 21.0)
    tower, openings = kairouan(60.5, 80.0)
    # The tower stands in front of the moon's lower horn; nothing of the horn shows past it.
    moon = max(moon.difference(tower.buffer(1.5)).geoms, key=lambda g: g.area)
    return moon, tower.difference(openings)


def kairouan_moon_icon():
    moon, tower = kairouan_moon()
    fg = [path(moon, GOLD), path(tower, CREAM)]
    return radial_bg("#0A6259", "#053A36"), fg, unary_union([moon, tower])


VARIANTS = {
    "01-five-stones": five_stones_icon,
    "02-pause-dome": pause_dome_icon,
    "03-kairouan-moon": kairouan_moon_icon,
}


def main():
    for slug, build in VARIANTS.items():
        bg, fg_paths, ink = build()
        reach = max(Point(54, 54).distance(Point(p)) for g in getattr(ink, "geoms", [ink]) for p in g.exterior.coords)
        d = ROOT / slug
        d.mkdir(exist_ok=True)
        fg = svg("".join(fg_paths))
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(svg(path(ink, "#000000")), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(bg, fg), encoding="utf-8", newline="\n")
        print(f"{slug}: ink reaches {reach:.1f}dp of 33")


if __name__ == "__main__":
    main()
