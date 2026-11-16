"""Round 8: the minaret and the mute sign inside one round seal.

Usage: python -I gen.py     (needs round7/_gen/traced.json for folders 01 to 03)

Round 7's pick left three things open: the mute sign reads as a caption under the arch,
the minaret is small, and the arch's bulbous outline can be read as a bell or a keyhole.
Here the arch is gone. A round field holds the minaret, which stands on a ground line,
and the mute sign sits in the segment under that line (a coin's exergue), so the sign is
inside the mark.

Everything except the mute sign is an exact mirror image about x = 54.
"""

import importlib.util
import math
from pathlib import Path

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("round7_gen", ROOT.parent / "round7" / "_gen" / "gen.py")
r7 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(r7)

CX, CY = 54.0, 54.0
T = r7.T
TEAL, TEAL_DARK, LIT = "#00645A", "#004A40", "#14857A"
IVORY, SAND, LIGHT, DARK, GOLD = r7.IVORY, r7.SAND, r7.LIGHT, r7.DARK, r7.GOLD
path, mirrored = r7.path, r7.mirrored


def disc(r):
    return mirrored(Point(CX, CY).buffer(r, 256))


# ---------------------------------------------------------------- the minaret


def traced_minaret(floor, tower_h, wide=1.08, taper_out=0.11, finial_u=1.0):
    """Round 7's traced minaret standing on y = floor: (tower, light stone, shadow lines, finial)."""
    ts = tower_h / (T["bar_top"] - T["dome_top"])
    parts = [
        mirrored(affinity.scale(
            r7.place(r7.straighten(r7.traced(n), taper_out, 676, T["bar_top"]), ts, (T["axis"], T["bar_top"]), (CX, floor)),
            xfact=wide, origin=(CX, floor)))
        for n in ("tower", "light", "dark")]
    return parts + [mirrored(r7.finial(floor - tower_h, finial_u))]


def arched(cx, top, w, h):
    return unary_union([box(cx - w / 2, top + w / 2, cx + w / 2, top + h), Point(cx, top + w / 2).buffer(w / 2, 32)])


def crest(y, width, count, h):
    """A row of round-topped merlons standing on y, flush with both ends of `width`."""
    tooth = width / (count + (count - 1) * 0.55)
    out = []
    for i in range(count):
        x = CX - width / 2 + i * tooth * 1.55
        out.append(unary_union([box(x, y - h + tooth / 2, x + tooth, y + 0.05),
                                Point(x + tooth / 2, y - h + tooth / 2).buffer(tooth / 2, 24)]))
    return unary_union(out)


# Shares of the base's width. FIRST is the first drawn version; STEPPED followed the review
# of 04 (nearly vertical base, tiers 100 / 65 / 45, merlons backed by shadow); REAL followed
# the review of 05 and is close to the real tower: its upper tiers are 0.71 and 0.51 of the
# base's width and 0.47 and 0.70 (with the dome) of it tall, and its base has three windows.
FIRST = dict(heights=(1.35, 0.42, 0.36), widths=(0.93, 0.62, 0.37), dome=0.2, backing=False,
             window=(0.1, 0.17), window_tops=(0.27, 0.57, 0.87))
STEPPED = dict(FIRST, widths=(0.965, 0.65, 0.45), backing=True)
REAL = dict(heights=(1.4, 0.48, 0.44), widths=(0.965, 0.7, 0.5), dome=0.16, backing=True,
            window=(0.14, 0.21), window_tops=(0.16, 0.58, 1.0))


def kairouan(floor, tower_h, spec, finial_u=1.0, windows=3):
    """The Great Mosque of Kairouan's minaret, drawn from its parts so that three tiers can
    be counted at small sizes: (tower, light stone, shadow bands, finial).

    The crests have 7 and 5 merlons, as in round 3's approved drawing, and a full-width
    shadow band lies under each crest. spec["backing"] also shades the foot of each upper
    tier behind the merlons, so the merlons stand out. windows=1 replaces the base's
    three windows with one (tried in 06).
    """
    slab_k = 0.035
    total = sum(spec["heights"]) + 3 * slab_k + spec["dome"]
    w = tower_h / total  # the base's width
    h1, h2, h3 = (k * w for k in spec["heights"])
    w1_top, w2, w3 = (k * w for k in spec["widths"])
    slab = slab_k * w
    y1 = floor - h1
    y2 = y1 - slab - h2
    y3 = y2 - slab - h3
    body = unary_union([
        Polygon([(CX - w / 2, floor), (CX - w1_top / 2, y1), (CX + w1_top / 2, y1), (CX + w / 2, floor)]),
        box(CX - w2 / 2, y2, CX + w2 / 2, y1),
        box(CX - w3 / 2, y3, CX + w3 / 2, y2),
    ])
    over = 0.03 * w
    slabs = unary_union([box(CX - k / 2 - over, y - slab, CX + k / 2 + over, y)
                         for k, y in ((w1_top, y1), (w2, y2), (w3, y3))])
    crests = unary_union([crest(y1 - slab, w1_top + 2 * over, 7, 0.1 * w), crest(y2 - slab, w2 + 2 * over, 5, 0.09 * w)])
    dw, dh, dy = 0.9 * w3, spec["dome"] * w, y3 - slab
    dome = Polygon([(CX - dw / 2, dy + 0.02)]
                   + [(CX + dw / 2 * math.cos(math.radians(a)), dy - dh * math.sin(math.radians(a))) for a in range(180, -1, -6)]
                   + [(CX + dw / 2, dy + 0.02)])
    ww, wh = (k * w for k in spec["window"])
    base_windows = ([arched(CX, y1 + k * w, ww, wh) for k in spec["window_tops"]] if windows == 3
                    else [arched(CX, y1 + 0.42 * w, 0.16 * w, 0.34 * w)])
    openings = unary_union(base_windows + [arched(CX, y2 + (h2 - 1.15 * wh) / 2 + 0.03 * w, 1.05 * ww, 1.15 * wh),
                                           arched(CX, y3 + (h3 - 1.05 * wh) / 2 + 0.03 * w, 0.95 * ww, 1.05 * wh)])
    light = unary_union([slabs, crests, dome])
    tower = unary_union([body, light]).difference(openings)
    shadow = [box(CX - k / 2, y, CX + k / 2, y + 0.05 * w) for k, y in ((w1_top, y1), (w2, y2), (w3, y3))]
    if spec["backing"]:
        shadow += [box(CX - w2 / 2, y1 - slab - 0.1 * w, CX + w2 / 2, y1 - slab).difference(crests),
                   box(CX - w3 / 2, y2 - slab - 0.09 * w, CX + w3 / 2, y2 - slab).difference(crests)]
    shadow = unary_union(shadow).difference(openings)
    return [mirrored(g) for g in (tower, light, shadow, r7.finial(dy - dh, finial_u))]


# ---------------------------------------------------------------- the mute sign


def matched_sign(cy, h, ratio=0.92, gap=0.22, weight=1.0):
    """The muted speaker as two matched halves: the speaker and the x have the same bounding
    box (ratio * h wide, h tall), the gap between them is centred on the axis, and the x
    has `weight` times the speaker's area. Returns (sign, speaker, x)."""
    w = ratio * h
    body = box(0, -0.2 * h, 0.44 * w, 0.2 * h)
    cone = Polygon([(0.38 * w, -0.2 * h), (w, -h / 2), (w, h / 2), (0.38 * w, 0.2 * h)])
    speaker = unary_union([body, cone]).buffer(-0.035 * h, 16).buffer(0.035 * h, 16)
    sx0, sy0, sx1, sy1 = speaker.bounds
    speaker = affinity.scale(speaker, xfact=w / (sx1 - sx0), yfact=h / (sy1 - sy0), origin=(0, 0))
    speaker = affinity.translate(speaker, -speaker.bounds[0], 0)

    def cross(t):
        ax, ay = w / 2 - t, h / 2 - t
        return unary_union([LineString([(-ax, -ay), (ax, ay)]).buffer(t, 16),
                            LineString([(-ax, ay), (ax, -ay)]).buffer(t, 16)])

    lo, hi = 0.02 * h, 0.3 * h
    for _ in range(40):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if cross(mid).area < weight * speaker.area else (lo, mid)
    g = gap * h
    left = affinity.translate(speaker, CX - g / 2 - w, cy)
    right = affinity.translate(cross(lo), CX + g / 2 + w / 2, cy)
    return unary_union([left, right]), left, right


# ---------------------------------------------------------------- the seal


def seal(r_out=30.5, stroke=2.3, ground=68.0, line=0.0, tower_h=34.0, sign_h=9.4, sign_dy=-0.5,
         spec=None, weight=1.0, **tower_kw):
    """A round field (with a ring `stroke` thick, or none), the minaret on a ground line,
    and the mute sign in the segment below it.

    line is the thickness of a separate ground line (0 when the segment is solid ground).
    spec picks a drawn minaret and the matched sign; without it, round 7's traced minaret
    and sign are used.
    """
    r_in = r_out - stroke
    inner = disc(r_in)
    ring = disc(r_out).difference(inner) if stroke else None
    field = inner.intersection(box(0, 0, 108, ground))
    base = inner.intersection(box(0, ground, 108, 108))
    bar = inner.intersection(box(0, ground, 108, ground + line)) if line else None
    seg_h = CY + r_in - ground - line
    sign_cy = ground + line + seg_h / 2 + sign_dy
    halves = None
    if spec:
        tower, light, dark, top = kairouan(ground, tower_h, spec, **tower_kw)
        sign, *halves = matched_sign(sign_cy, sign_h, weight=weight)
    else:
        tower, light, dark, top = traced_minaret(ground, tower_h)
        sign = r7.balanced_sign(sign_cy, sign_h)
    return dict(ring=ring, field=field, base=base, bar=bar, tower=tower, light=light, dark=dark, top=top,
                sign=sign, halves=halves, edge=disc(r_out))


def layers(p, c):
    """(foreground paths, themed-icon ink) in the colours c."""
    scene = [path(p["tower"], SAND), path(p["light"], LIGHT), path(p["dark"], DARK), path(p["top"], GOLD)]
    foot = p["tower"].bounds[3]
    tower = unary_union([p["tower"], p["top"]]).difference(box(0, foot - 0.7, 108, foot + 0.1))
    field = [path(p["field"], c["field"])]
    if p["ring"] is not None and c["base"] == c["ring"]:
        # Solid ground in the ring's colour: one shape, the sign cut out of it.
        ground = unary_union([p["ring"], p["base"]]).difference(p["sign"])
        return field + [path(ground, c["ring"])] + scene, unary_union([ground, tower])
    ring = [path(p["ring"], c["ring"])] if p["ring"] is not None else []
    bar = [path(p["bar"], c["line"])] if p["bar"] is not None else []
    fg = field + [path(p["base"], c["base"])] + ring + bar + scene + [path(p["sign"], c["sign"])]
    # Themed icon: with a ring, the ring; without one, the field's rim as a hairline ring.
    rim = p["ring"] if p["ring"] is not None else p["edge"].difference(p["edge"].buffer(-1.2))
    mono = unary_union([rim, tower, p["sign"]] + ([p["bar"]] if p["bar"] is not None else []))
    return fg, mono


def report(name, p):
    ink = unary_union([p["edge"], p["tower"], p["top"], p["sign"]])
    reach = max(Point(CX, CY).distance(Point(q)) for g in getattr(ink, "geoms", [ink]) for q in g.exterior.coords)
    x0, y0, x1, y1 = unary_union([p["tower"], p["top"]]).bounds
    sx0, _, sx1, _ = p["sign"].bounds
    left = p["sign"].intersection(box(0, 0, CX, 108)).area / p["sign"].area
    print(f"{name}: minaret {x1 - x0:.1f} x {y1 - y0:.1f}dp, artwork reaches {reach:.1f}dp of 33, "
          f"mute sign {sx1 - sx0:.1f}dp wide, {p['sign'].distance(p['tower']):.2f}dp below the minaret, "
          f"centre of mass x = {p['sign'].centroid.x:.2f}, {left:.0%} of its ink left of the axis")
    if p["halves"]:
        (ax0, ay0, ax1, ay1), (bx0, by0, bx1, by1) = p["halves"][0].bounds, p["halves"][1].bounds
        print(f"   speaker box {ax1 - ax0:.2f} x {ay1 - ay0:.2f}dp, {CX - ax1:.2f}dp left of the axis, area {p['halves'][0].area:.2f}; "
              f"x box {bx1 - bx0:.2f} x {by1 - by0:.2f}dp, {bx0 - CX:.2f}dp right of it, area {p['halves'][1].area:.2f}; "
              f"crescent tip {y0 - p['field'].bounds[1]:.2f}dp below the field's top")


# ---------------------------------------------------------------- the full-bleed scene

# The wall stands back from the minaret as the same masonry in shadow: the minaret's sand
# 15% darker, a coping between the two, and clearly darker arches. (Taking the sand
# towards the sky's teal instead gave an olive that looked dirty.)
WALL, COPING, ARCH = "#B8854C", "#C6915A", "#93683A"


def scene(ground=68.5, line=1.7, tower_h=41.0, sign_w=15.5, sign_cy=78.2, spec=REAL, finial_u=0.85, weight=1.12,
          wall=0.0, arcade=False):
    """The minaret on a ground line that runs off both sides of the icon, a band below it
    to the bottom edge, and the mute sign in the band. Nothing frames the scene: the
    launcher's mask does.

    wall > 0 adds the mosque's courtyard wall behind the minaret, wall * (the minaret's
    base width) tall, with three blind arches each side as at Kairouan.
    """
    tower, light, dark, top = kairouan(ground, tower_h, spec, finial_u=finial_u)
    sign_h = sign_w / (2 * 0.92 + 0.22)
    sign, *halves = matched_sign(sign_cy, sign_h, weight=weight)
    p = dict(band=box(0, ground, 108, 108), bar=box(0, ground, 108, ground + line), tower=tower, light=light,
             dark=dark, top=top, sign=sign, halves=halves, wall=None, arches=None, coping=None)
    if wall:
        x0, _, x1, _ = tower.bounds
        w = x1 - x0
        h = wall * w
        behind = box(CX - 0.15 * w, 0, CX + 0.15 * w, 108)  # no wall behind the windows: they show the sky
        p["wall"] = box(0, ground - h, 108, ground).difference(behind)
        p["coping"] = box(0, ground - h - 0.05 * w, 108, ground - h).difference(behind)
        if arcade:
            # An arcade as in the mosque's courtyard: wide openings on thin piers, so the
            # arches read as openings in one wall, not as separate round-topped slabs.
            step, first, aw, ah = 0.54 * w, 0.9 * w, 0.4 * w, 0.8 * h
        else:
            step, first, aw, ah = 0.56 * w, w / 2 + 0.36 * w, 0.3 * w, 0.76 * h
        p["arches"] = mirrored(unary_union([arched(CX - first - i * step, ground - ah, aw, ah + 0.05) for i in range(5)]))
    return p


def scene_layers(p, c):
    fg = [path(p["band"], c["band"])]
    if p["wall"] is not None:
        fg += [path(p["wall"], WALL), path(p["coping"], COPING), path(p["arches"], ARCH)]
    fg += [path(p["bar"], c["line"]), path(p["tower"], SAND), path(p["light"], LIGHT), path(p["dark"], DARK),
           path(p["top"], GOLD), path(p["sign"], c["sign"])]
    foot = p["tower"].bounds[3]
    tower = unary_union([p["tower"], p["top"]]).difference(box(0, foot - 0.7, 108, foot + 0.1))
    return fg, unary_union([tower, p["bar"], p["sign"]])


def scene_report(name, p):
    ink = unary_union([p["tower"], p["top"], p["sign"]])
    reach = max(Point(CX, CY).distance(Point(q)) for g in getattr(ink, "geoms", [ink]) for q in g.exterior.coords)
    x0, y0, x1, y1 = unary_union([p["tower"], p["top"]]).bounds
    sx0, _, sx1, _ = p["sign"].bounds
    (ax0, ay0, ax1, ay1), (bx0, by0, bx1, by1) = p["halves"][0].bounds, p["halves"][1].bounds
    print(f"{name}: minaret {x1 - x0:.1f} x {y1 - y0:.1f}dp, minaret and sign reach {reach:.1f}dp of 33, "
          f"crescent tip {y0 - 18:.1f}dp below the visible top, mute sign {sx1 - sx0:.1f}dp wide "
          f"({(sx1 - sx0) / 72:.0%} of the icon), {p['sign'].distance(p['tower']):.2f}dp below the minaret")
    print(f"   speaker box {ax1 - ax0:.2f} x {ay1 - ay0:.2f}dp, {CX - ax1:.2f}dp left of the axis, area {p['halves'][0].area:.2f}; "
          f"x box {bx1 - bx0:.2f} x {by1 - by0:.2f}dp, {bx0 - CX:.2f}dp right of it, area {p['halves'][1].area:.2f}")


TOWER3 = dict(REAL, widths=(0.965, 0.7, 0.46))
SCENES = {
    # After the harsh review of 07: no disc inside the icon. Sky, ground line and band run
    # off every edge; the sign is smaller and sand, the x heavier, the top tier narrower.
    "_explored/09-scene-lit": (dict(spec=TOWER3), dict(bg=LIT, band=TEAL_DARK, line=IVORY, sign=SAND)),
    "_explored/10-scene-teal": (dict(spec=TOWER3), dict(bg=TEAL, band=TEAL_DARK, line=IVORY, sign=SAND)),
    # The same with the mosque's wall behind the minaret, so the tower does not stand alone
    # on a horizon like a lighthouse.
    "_explored/11-scene-wall": (dict(spec=TOWER3, wall=0.42), dict(bg=LIT, band=TEAL_DARK, line=IVORY, sign=SAND)),
    # After a second harsh reviewer: the sign in the ground line's cream and a little larger,
    # so it stays readable; three wider arches a side.
    "_explored/12-scene-wall-cream-sign": (dict(spec=TOWER3, wall=0.42, sign_w=18.0, sign_cy=78.5),
                                 dict(bg=LIT, band=TEAL_DARK, line=IVORY, sign=IVORY)),
    # 12's cream ground line runs to the icon's edge and, on a light wallpaper, splits the
    # icon in two. Here the line is the minaret's light stone, and thinner.
    "_explored/13-scene-wall-sand-line": (dict(spec=TOWER3, wall=0.42, sign_w=18.0, sign_cy=78.4, line=1.3),
                                dict(bg=LIT, band=TEAL_DARK, line=LIGHT, sign=IVORY)),
    # The first reviewer found 13's cream sign competing with the minaret again, and its
    # separate dark arches a weak row of slabs. Here the sign is the minaret's light stone
    # (the ground line's colour) at the same size, and the wall is an arcade.
    "final": (dict(spec=TOWER3, wall=0.42, arcade=True, sign_w=18.0, sign_cy=78.4, line=1.3),
                  dict(bg=LIT, band=TEAL_DARK, line=LIGHT, sign=LIGHT)),
}

RINGED = dict(bg=TEAL, ring=IVORY, line=IVORY, field=LIT, sign=IVORY)
BIG = dict(r_out=32.5, ground=68.5, line=1.7, sign_h=8.8, sign_dy=-0.3)

# folder: (geometry, colours)
VARIANTS = {
    "_explored/01-seal-ground": (dict(), dict(RINGED, base=IVORY)),
    "_explored/02-seal-dark": (dict(line=1.7, sign_h=8.8), dict(RINGED, base=TEAL_DARK)),
    "_explored/03-seal-line": (dict(line=1.7, sign_h=8.8), dict(RINGED, base=LIT)),
    # After the harsh review of 02: the ring further out, a larger minaret drawn with deeper
    # setbacks, fewer merlons and shadow bands, the sign in two matched halves, and the
    # segment in the background's own teal (no third green).
    "_explored/04-seal-drawn": (dict(BIG, tower_h=36.3, spec=FIRST, weight=0.85), dict(RINGED, base=TEAL)),
    # After the harsh review of 04: the STEPPED minaret, the finial 1.5 ring strokes clear
    # of the ring, the segment darker than the field again. 06 has one window in the base.
    "_explored/05-seal-stepped": (dict(BIG, tower_h=35.4, spec=STEPPED, weight=0.85, finial_u=0.95), dict(RINGED, base=TEAL_DARK)),
    "_explored/06-seal-one-window": (dict(BIG, tower_h=35.4, spec=STEPPED, weight=0.85, finial_u=0.95, windows=1),
                           dict(RINGED, base=TEAL_DARK)),
    # After the harsh review of 05: no ivory ring (under a round launcher mask it was a ring
    # inside a ring), so the field is wider and the minaret larger; the REAL minaret with
    # larger windows and a smaller dome and crescent; the x with the speaker's full area.
    "_explored/07-field": (dict(r_out=33.0, stroke=0.0, ground=68.5, line=1.7, sign_h=9.0, sign_dy=-0.4, tower_h=39.4,
                      spec=REAL, finial_u=0.85), dict(bg=TEAL, line=IVORY, field=LIT, base=TEAL_DARK, sign=IVORY)),
    "_explored/08-ring-thin": (dict(r_out=32.6, stroke=1.4, ground=68.5, line=1.4, sign_h=9.0, sign_dy=-0.4, tower_h=38.0,
                          spec=REAL, finial_u=0.85), dict(RINGED, base=TEAL_DARK)),
}


def main():
    jobs = [(slug, seal, layers, report, g, c) for slug, (g, c) in VARIANTS.items()]
    jobs += [(slug, scene, scene_layers, scene_report, g, c) for slug, (g, c) in SCENES.items()]
    for slug, build, to_layers, tell, geometry, colours in jobs:
        parts = build(**geometry)
        tell(slug, parts)
        fg_paths, mono = to_layers(parts, colours)
        d = ROOT / slug
        d.mkdir(parents=True, exist_ok=True)
        bg = r7.svg(f'<rect width="108" height="108" fill="{colours["bg"]}"/>')
        fg = r7.svg("".join(fg_paths))
        (d / "background.svg").write_text(bg, encoding="utf-8", newline="\n")
        (d / "foreground.svg").write_text(fg, encoding="utf-8", newline="\n")
        (d / "monochrome.svg").write_text(r7.svg(path(mono, "#000000")), encoding="utf-8", newline="\n")
        (d / "icon.svg").write_text(r7.merged(bg, fg), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
