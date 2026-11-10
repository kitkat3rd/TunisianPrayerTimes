"""Round 4: the chosen icon's own parts (arch, Kairouan minaret, mushaf), stacked on one axis.

Usage: python -I gen.py

Round 3's 15-final-teal sets four small things side by side. Here the same parts
are nested on the icon's centre line, so each one can be drawn much larger:

- 01/02 bab-minaret: the minaret seen through a solid arch.
- 03/04 arch-mushaf: the ribbon arch over an open mushaf, with a crescent.
- 05/06 one-axis: arch, minaret and mushaf together.
- 07 bab-minaret-silent: 01 with the mute badge of 15.

The arch curve is round 3b's (gen2), the mushaf and the mute badge are the owner's
(gen4, gen2), and the minaret follows gen4's, with the base as squat as the real one.
"""

import sys
from pathlib import Path

from shapely import affinity
from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "round3" / "_gen"))
import gen2  # noqa: E402
import gen4  # noqa: E402  (sets gen2 to canvas units)
from gen2 import bezier, chain, crescent, merged, path, radial_bg, stroke, svg  # noqa: E402

CX = 54.0

TEAL, TEAL_DARK = "#00695C", "#004D40"
CREAM, CREAM_SHADE = "#F5EFE5", "#E6DAC5"
SAND, SAND_DARK, HOLE = "#C98E50", "#A46A33", "#6E4320"
SAND_LIGHT, SAND_LIGHT_DARK = "#E6B97F", "#C48A4E"
GOLD = "#E3BC5A"
PAGE = "#FFFEFA"


# ---------------------------------------------------------------- the arch


def arch_half(apex_y, s, foot_y, side):
    """Centre line of one half of the onion arch, apex to foot (gen2's curve, scaled by s)."""
    p = lambda dx, dy: (CX + side * dx * s, apex_y + dy * s)
    return chain(
        (CX, apex_y),
        ("C", *p(4.5, 20), *p(29.5, 23), *p(29.5, 41)),
        ("C", *p(29.5, 51), *p(25, 56), *p(25, 66)),
        ("L", CX + side * 25 * s, foot_y),
    )


def doorway(apex_y, s, foot_y):
    """The arch as a solid opening: (left half, right half)."""
    right = Polygon(arch_half(apex_y, s, foot_y, 1) + [(CX, foot_y)]).buffer(0)
    left = affinity.scale(right, xfact=-1, origin=(CX, 0))
    return left, right


def ribbon(apex_y, s, foot_y, w):
    """The arch as a ribbon that folds at the apex: (left half, right half).

    It is w wide in the legs and thins towards the apex, as in the owner's drawing.
    """
    def width(t):
        return w if t < 0.62 else w * (1 - 0.86 * ((t - 0.62) / 0.38) ** 1.4)
    band = stroke(arch_half(apex_y, s, foot_y, 1)[::-1], width, extend_end=6.0)
    right = band.intersection(box(CX, 0, 108, 108))
    left = affinity.scale(right, xfact=-1, origin=(CX, 0))
    return left, right


# ---------------------------------------------------------------- the minaret


def arched(cx, top, w, h):
    return gen2.arched_window(cx, top, w, h)


def crest(cx, y, width, tooth, h):
    count = max(3, round((width + tooth * 0.6) / (tooth * 1.6)))
    return gen4.crest(cx, y, width, count, tooth, h)


def kairouan(base, u, wx):
    """Great Mosque of Kairouan's minaret on the centre line, standing on y = base.

    u scales heights and details, wx scales widths (gen4's drawing is u = wx = 1).
    Returns (stone, shading, moon, openings); shading and openings go over the stone.
    """
    cx = CX
    mw = 10.4 * wx
    t1_top = base - 22.8 * u
    t2_top = t1_top - 4.1 * u
    t3_top = t2_top - 4.3 * u
    w1_top, w2, w3 = mw * 0.92, 6.5 * wx, 4.2 * wx
    t1 = Polygon([(cx - mw / 2, base), (cx - w1_top / 2, t1_top), (cx + w1_top / 2, t1_top), (cx + mw / 2, base)])
    t2 = box(cx - w2 / 2, t2_top, cx + w2 / 2, t1_top)
    t3 = box(cx - w3 / 2, t3_top, cx + w3 / 2, t2_top)
    c1h, c2h = 1.4 * u, 1.1 * u
    crests = unary_union([crest(cx, t1_top, w1_top, 0.9 * u, c1h), crest(cx, t2_top, w2, 0.85 * u, c2h)])
    cornice = box(cx - w3 / 2 - 0.3 * u, t3_top - 0.5 * u, cx + w3 / 2 + 0.3 * u, t3_top + 0.1)
    dw, dh, dy = 3.5 * wx, 2.4 * u, t3_top - 0.5 * u
    dome = Polygon(
        [(cx - dw / 2, dy)]
        + bezier((cx - dw / 2, dy), (cx - dw / 2, dy - dh * 0.75), (cx - dw * 0.15, dy - dh * 0.9), (cx, dy - dh))[1:]
        + bezier((cx, dy - dh), (cx + dw * 0.15, dy - dh * 0.9), (cx + dw / 2, dy - dh * 0.75), (cx + dw / 2, dy))[1:]
    )
    stem = box(cx - 0.35 * u, dy - dh - 1.3 * u, cx + 0.35 * u, dy - dh + 0.2)
    moon = crescent(cx, dy - dh - 3.0 * u, 2.1 * u, 0.9 * u, -0.8 * u, 1.8 * u)
    stone = unary_union([t1, t2, t3, crests, cornice, dome, stem])
    openings = unary_union(
        [box(cx - 0.65 * u, t1_top + y * u, cx + 0.65 * u, t1_top + (y + 2.0) * u) for y in (4.0, 8.5, 13.0)]
        + [arched(cx + dx * wx, t2_top + 0.45 * u, 1.4 * u, 2.2 * u) for dx in (-2.05, 0.0, 2.05)]
        + [arched(cx, t3_top + 0.4 * u, 1.6 * u, 2.6 * u)]
        + [arched(cx + dx * wx, t3_top + 0.7 * u, 0.55 * u, 2.2 * u) for dx in (-1.45, 1.45)]
    )
    shade = unary_union([
        box(cx - w2 / 2, t1_top - c1h, cx + w2 / 2, t1_top),
        box(cx - w3 / 2, t2_top - c2h, cx + w3 / 2, t2_top),
    ]).difference(crests)
    lines = unary_union([
        box(cx - w1_top / 2, t1_top - 0.05, cx + w1_top / 2, t1_top + 0.35 * u),
        box(cx - w2 / 2, t2_top - 0.05, cx + w2 / 2, t2_top + 0.3 * u),
    ])
    return stone, unary_union([shade, lines]), moon, openings


# ---------------------------------------------------------------- the mushaf

BOOK_CENTRE = (49.55, 60.45)  # of the owner's drawing in gen4


def mushaf(cy, k):
    """The owner's open mushaf, k times its size in 15, centred on (CX, cy): (book, cover, pages, lines)."""
    def place(g):
        g = affinity.scale(g, xfact=k, yfact=k, origin=BOOK_CENTRE)
        return affinity.translate(g, CX - BOOK_CENTRE[0], cy - BOOK_CENTRE[1])
    return place(gen4.BOOK), place(gen4.COVER), place(gen4.PAGES), place(gen4.text_lines(0.8))


# ---------------------------------------------------------------- proposals

DOOR = dict(apex_y=25.0, s=0.72, foot_y=80.0)
BADGE_GAP = 1.2

# The silent versions: 15's mute badge sits on the arch's left foot, and the arch moves
# a little to the right to make room for a bigger badge. As in round 3, the badge
# touches the arch only, never the minaret or the mushaf.
DOOR_SILENT = dict(shift=(2.5, -0.8), badge=(36.5, 71.0, 8.0))
RIBBON_SILENT = dict(shift=(1.0, -0.6), badge=(35.5, 71.6, 6.8))


def move(geoms, shift):
    return [affinity.translate(g, *shift) for g in geoms]


def mute(badge):
    """(the badge with the speaker cut out of it, the clear disc around it)."""
    disc, glyph = gen2.mute_badge(*badge)
    return disc.difference(glyph), Point(badge[:2]).buffer(badge[2] + BADGE_GAP, 96)


def report(name, gap, sacred, ink):
    reach = max(Point(54, 54).distance(Point(p)) for g in getattr(ink, "geoms", [ink]) for p in g.exterior.coords)
    print(f"{name}: badge clears the minaret/mushaf by {gap.distance(sacred):.2f}dp, ink reaches {reach:.2f}dp of 33")


def bab_minaret(c, silent=False):
    left, right = doorway(**DOOR)
    stone, trim, moon, openings = kairouan(DOOR["foot_y"], 1.15, 1.5)
    badge = []
    if silent:
        left, right, stone, trim, moon, openings = move([left, right, stone, trim, moon, openings], DOOR_SILENT["shift"])
    ink = unary_union([left, right]).difference(unary_union([stone, moon]).buffer(0.01))
    ink = unary_union([ink, openings])
    if silent:
        sign, gap = mute(DOOR_SILENT["badge"])
        left = left.difference(gap)
        badge = [path(sign, c["badge"])]
        ink = unary_union([ink.difference(gap), sign])
        report("bab-minaret", gap, unary_union([stone, moon]), unary_union([left, right, sign]))
    fg = [path(left, c["door_dark"]), path(right, c["door"]), path(stone, c["stone"]),
          path(trim, c["trim"]), path(openings, c["hole"]), path(moon, c["moon"])] + badge
    return fg, ink


RIBBON = dict(apex_y=24.0, s=0.78, foot_y=78.0, w=5.4)


def arch_mushaf(c, silent=False):
    left, right = ribbon(**RIBBON)
    # With the badge, the mushaf is smaller and higher so the badge stays clear of it.
    book, cover, pages, lines = mushaf(62.5, 1.25) if silent else mushaf(65.5, 1.5)
    moon = crescent(CX - 0.6, 43.0 if silent else 44.0, 5.6, 2.3, -1.9, 4.7)
    badge = []
    if silent:
        left, right, book, cover, pages, lines, moon = move(
            [left, right, book, cover, pages, lines, moon], RIBBON_SILENT["shift"])
        sign, gap = mute(RIBBON_SILENT["badge"])
        safe = Point(54, 54).buffer(33, 128)  # the move puts a hair of the apex's tip outside
        left, right = left.difference(gap).intersection(safe), right.intersection(safe)
        badge = [path(sign, c["badge"])]
        report("arch-mushaf", gap, book, unary_union([left, right, sign]))
    fg = [path(left, c["door_dark"]), path(right, c["door"]), path(moon, c["moon"]),
          path(pages, c["pages"]), path(cover, c["cover"]), path(lines, c["lines"])] + badge
    ink = unary_union([left, right, moon, cover, lines] + ([sign] if silent else []))
    return fg, ink


def one_axis(c):
    left, right = doorway(**DOOR)
    book, cover, pages, lines = mushaf(71.5, 1.3)
    # The minaret stands behind the mushaf: a thin gap around the book, and nothing under it.
    x0, _, x1, _ = book.bounds
    halo = unary_union([book.buffer(1.1), box(x0, book.centroid.y, x1, 108)])
    stone, trim, moon, openings = kairouan(DOOR["foot_y"], 1.1, 1.35)
    fg = [path(left, c["door_dark"]), path(right, c["door"]), path(stone.difference(halo), c["stone"]),
          path(trim.difference(halo), c["trim"]), path(openings.difference(halo), c["hole"]),
          path(moon, c["moon"]), path(pages, c["pages"]), path(cover, c["cover"]), path(lines, c["lines"])]
    ink = unary_union([left, right]).difference(unary_union([stone, moon, halo]).buffer(0.01))
    ink = unary_union([ink, openings.difference(halo), cover, lines])
    return fg, ink


def flat_bg(colour):
    return svg(f'<rect width="108" height="108" fill="{colour}"/>')


TEAL_BG = radial_bg("#007567", TEAL_DARK)
ON_TEAL = dict(bg=TEAL_DARK, door=CREAM, door_dark=CREAM_SHADE, stone=SAND, trim=SAND_DARK, hole=HOLE,
               moon=SAND_DARK, cover=TEAL, pages=PAGE, lines=TEAL_DARK, badge=CREAM)
ON_CREAM = dict(bg=CREAM, door=TEAL, door_dark=TEAL_DARK, stone=SAND_LIGHT, trim=SAND_LIGHT_DARK, hole="#7A4D25",
                moon=GOLD, cover=SAND_LIGHT, pages=PAGE, lines=TEAL_DARK, badge=TEAL_DARK)
# The ribbon arch has no cream field behind the mushaf, so the mushaf takes the gold.
RIBBON_TEAL = dict(ON_TEAL, door_dark="#C9E0D8", moon=GOLD, cover=GOLD)
RIBBON_CREAM = dict(ON_CREAM, moon=SAND, cover=TEAL)

VARIANTS = {
    "01-bab-minaret-teal": (TEAL_BG, bab_minaret(ON_TEAL)),
    "02-bab-minaret-cream": (flat_bg(CREAM), bab_minaret(ON_CREAM)),
    "03-arch-mushaf-teal": (TEAL_BG, arch_mushaf(RIBBON_TEAL)),
    "04-arch-mushaf-cream": (flat_bg(CREAM), arch_mushaf(RIBBON_CREAM)),
    "05-one-axis-teal": (TEAL_BG, one_axis(ON_TEAL)),
    "06-one-axis-cream": (flat_bg(CREAM), one_axis(ON_CREAM)),
    "07-bab-minaret-silent-teal": (TEAL_BG, bab_minaret(ON_TEAL, silent=True)),
    "08-bab-minaret-silent-cream": (flat_bg(CREAM), bab_minaret(ON_CREAM, silent=True)),
    "09-arch-mushaf-silent-teal": (TEAL_BG, arch_mushaf(RIBBON_TEAL, silent=True)),
    "10-arch-mushaf-silent-cream": (flat_bg(CREAM), arch_mushaf(RIBBON_CREAM, silent=True)),
}


def main():
    for slug, (bg, (fg_paths, ink)) in VARIANTS.items():
        d = ROOT / slug
        d.mkdir(exist_ok=True)
        fg = svg("".join(fg_paths))
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(svg(path(ink, "#000000")), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(bg, fg), encoding="utf-8", newline="\n")
        print(d)


if __name__ == "__main__":
    main()
