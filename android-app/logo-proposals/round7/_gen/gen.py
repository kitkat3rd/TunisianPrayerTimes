"""Round 7: mirror-symmetrical icons. The arch and minaret are traced from a concept
render (trace.py); the mute sign sits on the icon's centre line.

Usage: python -I trace.py   (once, slow)
       python -I gen.py

Everything except the mute sign itself is an exact mirror image about x = 54: each
traced shape is cut at the axis and its left half mirrored.
"""

import json
import sys
from pathlib import Path

from shapely import affinity, set_precision
from shapely.geometry import LineString, Point, Polygon, box, shape
from shapely.ops import transform, unary_union

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "round3" / "_gen"))
import gen2  # noqa: E402

gen2.SCALE, gen2.CENTRE_LOCAL = 1.0, (54.0, 54.0)  # draw in canvas units
from gen2 import merged, svg  # noqa: E402

CX = 54.0
T = json.loads((Path(__file__).resolve().parent / "traced.json").read_text(encoding="utf-8"))

TEAL, TEAL_DARK = "#00645A", "#004A40"
IVORY, SAND, LIGHT, DARK, GOLD = "#F6EDD8", "#D89C59", "#F0BD75", "#AC7B46", "#F2B84B"


def mirrored(g):
    """Exact mirror symmetry about x = CX: the left half and its reflection.

    The half is simplified before it is mirrored, and path() below writes points as they
    are, so the two sides get the same points. (gen2's path simplifies each ring from
    its own start point, which leaves the sides a fraction of a pixel apart.)
    """
    left = g.intersection(box(-500, -500, CX, 500)).simplify(0.03, preserve_topology=True)
    left = set_precision(left, 0.01)  # the grid path() writes on, so mirrored points round alike
    return unary_union([left, affinity.scale(left, xfact=-1, origin=(CX, 0))]).buffer(0)


def path(geom, fill):
    def ring(coords):
        return "M" + "L".join(f"{gen2.n(x)} {gen2.n(y)}" for x, y in list(coords)[:-1]) + "Z"
    polys = [geom] if geom.geom_type == "Polygon" else [g for g in geom.geoms if g.geom_type == "Polygon"]
    d = "".join(ring(p.exterior.coords) + "".join(ring(i.coords) for i in p.interiors) for p in polys if not p.is_empty)
    return f'<path fill="{fill}" fill-rule="evenodd" d="{d}"/>'


def traced(name):
    return shape(T[name]).buffer(0)


def shorten(g, y_cut, cut):
    """Take `cut` px out of a shape's straight legs, just below y_cut."""
    upper = g.intersection(box(-5000, -5000, 5000, y_cut))
    lower = affinity.translate(g.intersection(box(-5000, y_cut + cut, 5000, 5000)), 0, -cut)
    return unary_union([upper, lower]).buffer(0)


def place(g, s, origin_px, origin_dp):
    """Source px to canvas dp: scale by s, moving origin_px onto origin_dp."""
    g = affinity.translate(g, -origin_px[0], -origin_px[1])
    g = affinity.scale(g, xfact=s, yfact=s, origin=(0, 0))
    return affinity.translate(g, *origin_dp)


def finial(top_y, u):
    """A stem, a ball and a crescent with both horns up, on the dome's top at (CX, top_y)."""
    stem = box(CX - 0.3 * u, top_y - 2.6 * u, CX + 0.3 * u, top_y + 0.3)
    ball = Point(CX, top_y - 1.0 * u).buffer(0.62 * u, 32)
    cy = top_y - 4.0 * u
    moon = Point(CX, cy).buffer(1.9 * u, 64).difference(Point(CX, cy - 0.85 * u).buffer(1.65 * u, 64))
    return unary_union([stem, ball, moon])


def mute_glyph(cy, r, cross_size=0.64, stroke=0.42):
    """The muted speaker, balanced on the axis: the speaker sits left of x = CX and the x
    right of it, the same distance away. cross_size is the x's size as a share of the
    speaker's height (0.64 makes it as wide as the speaker); stroke is its arm thickness."""
    h = r * 1.25  # the speaker's height
    w, gap = 0.64 * h, 0.24 * h
    xl = CX - gap / 2 - w
    body = box(xl, cy - 0.21 * h, xl + 0.45 * w, cy + 0.21 * h)
    cone = Polygon([(xl + 0.4 * w, cy - 0.21 * h), (xl + w, cy - h / 2), (xl + w, cy + h / 2), (xl + 0.4 * w, cy + 0.21 * h)])
    speaker = unary_union([body, cone]).buffer(-0.035 * h, 16).buffer(0.035 * h, 16)
    k = 1.0
    cross = unary_union([LineString([(-k, -k), (k, k)]).buffer(stroke, 16), LineString([(-k, k), (k, -k)]).buffer(stroke, 16)])
    cx0, cy0, cx1, cy1 = cross.bounds
    size = cross_size * h
    cross = affinity.scale(cross, xfact=size / (cx1 - cx0), yfact=size / (cy1 - cy0), origin=(0, 0))
    cross = affinity.translate(cross, CX + gap / 2 + size / 2, cy)
    return unary_union([speaker, cross])


def balanced_sign(cy, h, cross_h=0.8, gap=0.2):
    """The muted speaker with its two halves matched: the x is cross_h times as tall as the
    speaker and its arms are as thick as it takes to give it the speaker's area. The sign
    is then placed with its centre of mass on x = CX."""
    w = 0.62 * h
    body = box(0, -0.21 * h, 0.45 * w, 0.21 * h)
    cone = Polygon([(0.4 * w, -0.21 * h), (w, -h / 2), (w, h / 2), (0.4 * w, 0.21 * h)])
    speaker = unary_union([body, cone]).buffer(-0.035 * h, 16).buffer(0.035 * h, 16)
    size = cross_h * h

    def cross(t):
        a = size / 2 - t
        return unary_union([LineString([(-a, -a), (a, a)]).buffer(t, 16), LineString([(-a, a), (a, -a)]).buffer(t, 16)])

    lo, hi = 0.02 * h, 0.3 * h
    for _ in range(40):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if cross(mid).area < speaker.area else (lo, mid)
    x = affinity.translate(cross(lo), w + gap * h + size / 2, 0)
    sign = unary_union([speaker, x])
    return affinity.translate(sign, CX - sign.centroid.x, cy)


def straighten(g, k, y0, y1):
    """Take a share k of the taper out of the minaret's base tier (source px, between rows
    y0 and y1): the foot is narrowed towards the axis, the top of the tier stays as it is."""
    def f(x, y, z=None):
        t = min(max((y - y0) / (y1 - y0), 0.0), 1.0)
        return T["axis"] + (x - T["axis"]) * (1 - k * t), y
    return transform(f, g)


def build(layout, width, apex_y, foot_y, tower_h, glyph_r, glyph_cy, with_band=True, thin=0.0,
          finial_u=0.95, cross_size=0.64, stroke=0.42, panel_h=0.0, sign_h=None, taper_out=0.0,
          cross_h=0.8, tower_wide=1.0, hollow=0.0):
    """The arch, the minaret and the mute sign, all on the centre line.

    layout="ground": the arch's legs run down to foot_y, where a full-width band begins
                     (with_band=False leaves the band out: the legs just end at foot_y).
    layout="framed": the arch keeps its bottom bar, whose lower edge is at foot_y.
    layout="panel":  the legs run down to foot_y and the lowest panel_h of the doorway is
                     solid, as wide as the legs; the sign is cut out of that panel.
    """
    fx0, fy0, fx1, _ = shape(T["frame"]).bounds
    s = width / (fx1 - fx0)
    bar_h = (T["bar_bottom"] + 1 - T["bar_top"]) * s if layout == "framed" else 0.0
    floor = foot_y - bar_h  # where the minaret stands
    # Shorten the legs so the arch is (floor - apex_y) tall from its apex to the floor.
    leg_cut = (T["bar_top"] - fy0) - (floor - apex_y) / s
    assert 0 <= leg_cut < 260, leg_cut
    frame = mirrored(place(shorten(traced("frame"), 720, leg_cut), s, (T["axis"], T["bar_top"] - leg_cut), (CX, floor)))
    band = None
    if layout in ("ground", "panel"):
        frame = frame.intersection(box(0, 0, 108, foot_y + 0.05))
        band = box(0, foot_y, 108, 108)
    if layout == "panel":
        x0, _, x1, _ = frame.intersection(box(0, foot_y - 1.0, 108, foot_y)).bounds
        frame = unary_union([frame, box(x0, foot_y - panel_h, x1, foot_y)])
        floor = foot_y - panel_h
    closed = unary_union([frame, box(0, foot_y, 108, 108)]) if layout == "ground" else frame
    opening = unary_union([Polygon(r) for r in max(getattr(closed, "geoms", [closed]), key=lambda g: g.area).interiors])
    if not with_band or layout == "panel":
        band = None
    if thin:
        # A lighter frame: the opening is scaled up about its own middle so that it grows by
        # `thin` on every side (scaling keeps its pointed tip), and the floor drops with it.
        ox0, oy0, ox1, oy1 = opening.bounds
        opening = mirrored(affinity.scale(opening, xfact=(ox1 - ox0 + 2 * thin) / (ox1 - ox0),
                                          yfact=(oy1 - oy0 + 2 * thin) / (oy1 - oy0), origin=(CX, (oy0 + oy1) / 2)))
        whole = unary_union([frame, opening]).buffer(0)
        frame = whole.difference(opening)
        floor = opening.bounds[3]
        print(f"   frame stroke after thinning: {whole.exterior.distance(opening.exterior):.2f}dp at its thinnest")

    if hollow:
        # A door in two panels: the solid panel becomes a second opening under a crossbar
        # `hollow` thick, between the legs' inner faces, and the sign stands in it.
        x0, _, x1, _ = opening.intersection(box(0, floor - 1.0, 108, floor)).bounds
        lower = box(x0, floor + hollow, x1, foot_y - hollow)
        frame = frame.difference(lower)
        opening = unary_union([opening, lower])

    ts = tower_h / (T["bar_top"] - T["dome_top"])
    tower, light, dark = (
        mirrored(affinity.scale(
            place(straighten(traced(n), taper_out, 676, T["bar_top"]), ts, (T["axis"], T["bar_top"]), (CX, floor)),
            xfact=tower_wide, origin=(CX, floor)))
        for n in ("tower", "light", "dark"))
    glyph = balanced_sign(glyph_cy, sign_h, cross_h) if sign_h else mute_glyph(glyph_cy, glyph_r, cross_size, stroke)
    return dict(frame=frame, band=band, opening=opening, tower=tower, light=light, dark=dark,
                top=mirrored(finial(floor - tower_h, finial_u)), glyph=glyph,
                cut=layout == "panel" and not hollow)


def layers(p, c):
    """(foreground paths, themed-icon ink) in the colours c."""
    scene = [path(p["tower"], SAND), path(p["light"], LIGHT), path(p["dark"], DARK), path(p["top"], GOLD)]
    # Themed icon: a hairline of paper under the tower's foot keeps the tower readable.
    foot = p["tower"].bounds[3]
    tower = unary_union([p["tower"], p["top"]]).difference(box(0, foot - 0.7, 108, foot + 0.1))
    door = [path(p["opening"], c["door"])] if c.get("door") else []
    if p.get("cut"):
        # The sign is cut out of the arch's own solid panel.
        arch = p["frame"].difference(p["glyph"])
        return door + [path(arch, c["arch"])] + scene, unary_union([arch, tower])
    if c.get("solid"):
        # The arch as a solid doorway: frame and opening in one colour, the sign below it.
        whole = unary_union([p["frame"], p["opening"]])
        mono = unary_union([whole.difference(unary_union([p["tower"], p["top"]]).buffer(0.01)), p["glyph"]])
        return [path(whole, c["arch"])] + scene + [path(p["glyph"], c["sign"])], mono
    if p["band"] is not None and c["band"] == c["arch"]:
        # Ground of the arch's own colour: one shape, the sign cut out of it.
        ground = unary_union([p["frame"], p["band"]]).difference(p["glyph"])
        return door + [path(ground, c["arch"])] + scene, unary_union([ground, tower])
    band = [path(p["band"], c["band"])] if p["band"] is not None else []
    fg = band + door + [path(p["frame"], c["arch"])] + scene + [path(p["glyph"], c["sign"])]
    return fg, unary_union([p["frame"], tower, p["glyph"]])


def report(name, p):
    ink = unary_union([p["frame"], p["tower"], p["top"], p["glyph"]])
    reach = max(Point(54, 54).distance(Point(q)) for g in getattr(ink, "geoms", [ink]) for q in g.exterior.coords)
    x0, y0, x1, y1 = p["frame"].bounds
    clear = p["glyph"].distance(unary_union([p["tower"], p["top"]]))
    gx0, _, gx1, _ = p["glyph"].bounds
    left = p["glyph"].intersection(box(0, 0, CX, 108)).area
    print(f"{name}: arch {x1 - x0:.1f} x {y1 - y0:.1f}dp, artwork reaches {reach:.1f}dp of 33, "
          f"mute sign {gx1 - gx0:.1f}dp wide, {clear:.2f}dp below the minaret; its centre of mass is at "
          f"x = {p['glyph'].centroid.x:.2f}, with {left / p['glyph'].area:.0%} of its ink left of the axis")


ON_TEAL = dict(bg=TEAL, arch=IVORY, sign=IVORY)
ON_CREAM = dict(bg="#F5EFE5", arch="#004D40", door="#00695C", sign="#004D40")
LIT = dict(ON_TEAL, door="#14857A")  # the arch's opening a clear step lighter than the background

# The geometries, in the order they were tried (README.md says why each one changed).
GROUND = dict(layout="ground", width=39.0, apex_y=22.5, foot_y=69.5, glyph_r=8.4, glyph_cy=79.05)
LOCKUP = dict(layout="framed", width=37.0, apex_y=24.6, foot_y=70.6, tower_h=27.3, glyph_r=7.8, glyph_cy=78.5)
DOOR = dict(LOCKUP, layout="ground", with_band=False, tower_h=33.0)
REFINED = dict(layout="framed", width=36.0, apex_y=24.0, foot_y=68.6, thin=1.0, tower_h=27.6, finial_u=1.1,
               glyph_r=9.4, glyph_cy=78.1, cross_size=0.72, stroke=0.43)
BALANCED = dict(layout="framed", width=33.0, apex_y=24.0, foot_y=68.6, thin=1.3, tower_h=29.0, finial_u=1.05,
                glyph_r=0, glyph_cy=78.0, sign_h=10.8, taper_out=0.11)
PANEL = dict(layout="panel", width=37.5, apex_y=25.0, foot_y=81.5, panel_h=14.5, thin=0.9, tower_h=28.2,
             finial_u=1.05, glyph_r=0, glyph_cy=74.7, sign_h=9.6, taper_out=0.11)
# The pick: REFINED's sturdier frame with BALANCED's matched sign, larger and closer to the
# arch; the minaret a little wider with most of its taper out; more air above the crescent.
# (An x as tall as the speaker was tried: its arms get thin and it reads as a close button.)
# The last review found the sign as loud as the arch and the minaret too small, so the frame
# is a little lighter again (2.3dp), the minaret taller, and the sign a little smaller.
FINAL = dict(layout="framed", width=36.0, apex_y=24.0, foot_y=68.6, thin=1.5, tower_h=28.8, finial_u=1.05,
             glyph_r=0, glyph_cy=77.5, sign_h=11.2, cross_h=0.8, taper_out=0.11, tower_wide=1.08)

# Tried after the pick, to stop the sign reading as a caption: the arch as a door in two
# panels, the minaret in the upper one and the sign in the lower one.
TWO_PANEL = dict(layout="panel", width=37.5, apex_y=25.0, foot_y=81.5, panel_h=17.0, thin=0.9, hollow=2.3,
                 tower_h=26.0, finial_u=1.05, glyph_r=0, glyph_cy=74.2, sign_h=8.4, taper_out=0.11, tower_wide=1.08)

# folder: (geometry, colours, a full-width band added behind a framed arch or None)
VARIANTS = {
    "final-teal": (FINAL, LIT, None),
    "final-cream": (FINAL, ON_CREAM, None),
    "_explored/01-ground-ivory": (dict(GROUND, tower_h=30.0), dict(ON_TEAL, band=IVORY, door="#0D7A6C"), None),
    "_explored/02-band-dark": (dict(GROUND, layout="framed", tower_h=28.0), dict(ON_TEAL, band=TEAL_DARK), 69.5),
    "_explored/03-lockup-teal": (LOCKUP, dict(ON_TEAL, door="#0D7A6C"), None),
    "_explored/05-door-teal": (DOOR, dict(ON_TEAL, solid=True), None),
    "_explored/06-door-cream": (DOOR, dict(bg="#F5EFE5", arch="#00594F", sign="#004D40", solid=True), None),
    "_explored/07-refined-ivory": (REFINED, LIT, None),
    "_explored/08-refined-gold": (REFINED, dict(LIT, sign=GOLD), None),
    "_explored/10-panel": (PANEL, LIT, None),
    "_explored/11-balanced": (BALANCED, LIT, None),
    "_explored/12-two-panel-door": (TWO_PANEL, LIT, None),
}


def main():
    for slug, (geometry, colours, band_y) in VARIANTS.items():
        parts = build(**geometry)
        if band_y is not None:
            parts["band"] = box(0, band_y, 108, 108)
        report(slug, parts)
        fg_paths, mono = layers(parts, colours)
        d = ROOT / slug
        d.mkdir(parents=True, exist_ok=True)
        bg = svg(f'<rect width="108" height="108" fill="{colours["bg"]}"/>')
        fg = svg("".join(fg_paths))
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(svg(path(mono, "#000000")), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(merged(bg, fg), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
