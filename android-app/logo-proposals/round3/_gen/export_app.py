"""Install a proposal as the phone app's launcher icon, with other colours of the same drawing.

Usage: python -I export_app.py [--launcher-placement SCALE TRANSLATE_Y] [proposal-dir] [name=proposal-dir ...]
       (default: 15-final-teal; a directory is relative to round3/)
Current Android launcher export:
       python -I export_app.py --launcher-placement 1.13 -1.63368 ../round4/06-one-axis-cream blue=../round4/12-one-axis-blue-cream

The first proposal is the app's own icon, and the one the store pictures show. Each
name=proposal-dir is another colour of it that the app's user can choose (LauncherIcon.kt).
It gets drawable/ic_launcher_<name>_foreground.xml and mipmap-anydpi-v26/ic_launcher_<name>.xml,
and shares the first one's monochrome layer. It gets no legacy PNGs: the app's minSdk is 26,
where the adaptive icon is always used.
The optional placement applies to all Android adaptive icon foregrounds and their
shared monochrome layer. It scales about the viewport center and then translates vertically.

Writes, under android-app/:
  app/src/main/res/drawable/ic_launcher_foreground.xml   foreground.svg as a VectorDrawable
  app/src/main/res/drawable/ic_launcher_monochrome.xml   monochrome.svg (themed icon)
  app/src/main/res/values/ic_launcher_background.xml     the flat background colours
  app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml    adaptive icons with <monochrome>
  app/src/main/res/mipmap-*/ic_launcher*.png             legacy PNGs (rounded square, circle)
  app-icon.svg, app-icon-512.png                         Play Store icon: the central 72dp, full bleed
  feature-graphic.svg, feature-graphic.png               Play feature graphic: the round icon on the
                                                         app's teal, the app name from strings.xml
                                                         in Amiri Bold (_gen/fonts/Amiri-Bold.ttf, OFL)

The proposal's SVGs come from gen4.py or round4's gen.py: one <path fill fill-rule d> per
colour, with absolute M/L/Z data only, so the conversion is one-to-one.
"""

import argparse
import base64
import re
import subprocess
import tempfile
from pathlib import Path
from textwrap import indent

from PIL import Image

ROUND3 = Path(__file__).resolve().parent.parent
ANDROID = ROUND3.parent.parent
RES = ANDROID / "app" / "src" / "main" / "res"
BROWSERS = (
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
)

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
PATH_RE = re.compile(r'<path fill="(#[0-9A-Fa-f]{6})" fill-rule="(evenodd|nonzero)" d="([^"]+)"/>')


def write(path: Path, text: str):
    path.write_text(text, encoding="utf-8", newline="\n")
    print(path.relative_to(ANDROID))


def vector(svg: str, comment: str, placement: tuple[float, float] | None = None) -> str:
    paths = PATH_RE.findall(svg)
    assert paths and svg.count("<path") == len(paths), "unexpected SVG content"
    body = "".join(
        "    <path\n"
        f'        android:fillColor="{fill}"\n'
        + ('        android:fillType="evenOdd"\n' if rule == "evenodd" else "")
        + f'        android:pathData="{d}" />\n'
        for fill, rule, d in paths
    )
    if placement is not None:
        scale, translate_y = placement
        body = (
            '    <group\n'
            '        android:pivotX="54"\n'
            '        android:pivotY="54"\n'
            f'        android:scaleX="{scale}"\n'
            f'        android:scaleY="{scale}"\n'
            f'        android:translateY="{translate_y}">\n'
            + indent(body, '    ')
            + '    </group>\n'
        )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f"{body}"
        "</vector>\n"
    )


def adaptive(background: str, foreground: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@color/{background}" />\n'
        f'    <foreground android:drawable="@drawable/{foreground}" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )


def browser() -> str:
    for exe in BROWSERS:
        if Path(exe).exists():
            return exe
    raise SystemExit("export_app.py needs Chrome or Edge to draw the PNGs")


def render_pngs(icon_svg: Path, tmp: Path):
    """Draw every PNG on one transparent page with headless Chrome, then crop."""
    uri = "data:image/svg+xml;base64," + base64.b64encode(icon_svg.read_bytes()).decode()
    jobs = [("play", 512, "0")] + [
        (f"{dens}/{name}", size, radius)
        for dens, size in DENSITIES.items()
        for name, radius in (("ic_launcher", "22%"), ("ic_launcher_round", "50%"))
    ]
    cells, divs, x = [], [], 0
    for key, size, radius in jobs:
        cells.append((key, x, size))
        full, off = size * 108 / 72, -size * 18 / 72
        divs.append(
            f'<div style="position:absolute;left:{x}px;top:0;width:{size}px;height:{size}px;'
            f'overflow:hidden;border-radius:{radius}">'
            f'<img src="{uri}" style="position:absolute;left:{off}px;top:{off}px;width:{full}px;height:{full}px"></div>'
        )
        x += size + 8
    html = tmp / "icons.html"
    html.write_text('<html><body style="margin:0;background:transparent">' + "".join(divs) + "</body></html>",
                    encoding="utf-8")
    shot = tmp / "icons.png"
    chrome_shot(html, shot, x, 512, transparent=True)
    sheet = Image.open(shot).convert("RGBA")
    for key, left, size in cells:
        im = sheet.crop((left, 0, left + size, size))
        if key == "play":
            out = ANDROID / "app-icon-512.png"
            im.convert("RGB").save(out)
        else:
            dens, name = key.split("/")
            out = RES / f"mipmap-{dens}" / f"{name}.png"
            im.save(out)
        print(out.relative_to(ANDROID))


def chrome_shot(html: Path, out: Path, width: int, height: int, transparent=False):
    # Headless Chrome sometimes hangs on this PC; a private profile and one retry get past it.
    args = [browser(), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
            "--no-first-run", f"--user-data-dir={html.parent / 'chrome-profile'}",
            f"--screenshot={out}", f"--window-size={width},{height}", html.as_uri()]
    if transparent:
        args.insert(-1, "--default-background-color=00000000")
    for attempt in (1, 2):
        try:
            subprocess.run(args, check=True, timeout=90, capture_output=True)
            return
        except subprocess.TimeoutExpired:
            if attempt == 2:
                raise
            print("headless Chrome hung, retrying")


def feature_graphic(src: Path, tmp: Path):
    """1024x500: the round icon with a thin gold ring on the app's teal, the name below."""
    name = re.search(r'<string name="app_name">([^<]+)</string>',
                     (RES / "values" / "strings.xml").read_text(encoding="utf-8")).group(1)
    icon_body = (src / "icon.svg").read_text(encoding="utf-8").split(">", 1)[1].rsplit("</svg>", 1)[0]
    cx, cy, r = 512, 196, 124
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <!-- Generated by logo-proposals/round3/_gen/export_app.py from {src.name}; edit that script, not this file. -->
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#00332D"/>
      <stop offset="100%" stop-color="#006B5A"/>
    </linearGradient>
    <clipPath id="disc"><circle cx="54" cy="54" r="36"/></clipPath>
  </defs>
  <rect width="1024" height="500" fill="url(#bg)"/>
  <g opacity="0.1" stroke="#D4AF37" stroke-width="1" fill="none">
    <circle cx="{cx}" cy="{cy}" r="{r + 40}"/>
    <circle cx="{cx}" cy="{cy}" r="{r + 80}"/>
    <circle cx="{cx}" cy="{cy}" r="{r + 120}"/>
  </g>
  <circle cx="{cx}" cy="{cy}" r="{r + 7}" fill="none" stroke="#D4AF37" stroke-width="3" opacity="0.75"/>
  <svg x="{cx - r}" y="{cy - r}" width="{2 * r}" height="{2 * r}" viewBox="18 18 72 72">
    <g clip-path="url(#disc)">{icon_body}</g>
  </svg>
  <text x="512" y="398" text-anchor="middle" font-family="Amiri, serif" font-weight="700" font-size="60" fill="#FFFFFF" direction="rtl">{name}</text>
  <text x="512" y="460" text-anchor="middle" font-family="'IBM Plex Sans Arabic', sans-serif" font-size="20" fill="#D4AF37">Tunisian Prayer Times</text>
</svg>
"""
    write(ANDROID / "feature-graphic.svg", svg)
    # The name is in Amiri Bold (owner's pick, 2026-11-03; _gen/fonts, SIL OFL), the
    # tagline in the app's IBM Plex Sans Arabic. The SVG names both, with generic fallbacks.
    faces = (("Amiri", 700, Path(__file__).parent / "fonts" / "Amiri-Bold.ttf"),
             ("IBM Plex Sans Arabic", 400, RES / "font" / "ibm_plex_sans_arabic_regular.ttf"))
    fonts = "".join(
        f"@font-face{{font-family:'{family}';font-weight:{w};"
        f"src:url(data:font/ttf;base64,{base64.b64encode(f.read_bytes()).decode()})}}"
        for family, w, f in faces
    )
    html = tmp / "feature.html"
    html.write_text(f"<html><head><meta charset=\"utf-8\"><style>{fonts}body{{margin:0}}</style></head><body>{svg}</body></html>",
                    encoding="utf-8")
    shot = tmp / "feature.png"
    chrome_shot(html, shot, 1024, 500)
    Image.open(shot).convert("RGB").crop((0, 0, 1024, 500)).save(ANDROID / "feature-graphic.png")
    print("feature-graphic.png")


def flat_background(src: Path) -> str:
    bg = re.search(r'<rect width="108" height="108" fill="(#[0-9A-Fa-f]{6})"/>',
                   (src / "background.svg").read_text(encoding="utf-8"))
    assert bg, f"{src.name}: the app icon needs a flat background colour"
    return bg.group(1)


def note_for(src: Path) -> str:
    return (f"Generated by logo-proposals/round3/_gen/export_app.py from {src.parent.name}/{src.name}; "
            "regenerate instead of editing this file.")


def main():
    parser = argparse.ArgumentParser(description="Export the Android launcher artwork.")
    parser.add_argument("--launcher-placement", nargs=2, type=float, metavar=("SCALE", "TRANSLATE_Y"))
    parser.add_argument("proposals", nargs="*")
    args = parser.parse_args()
    placement = tuple(args.launcher_placement) if args.launcher_placement is not None else None
    others = dict(a.split("=", 1) for a in args.proposals if "=" in a)
    src = (ROUND3 / next((a for a in args.proposals if "=" not in a), "15-final-teal")).resolve()
    fg = (src / "foreground.svg").read_text(encoding="utf-8")
    mono = (src / "monochrome.svg").read_text(encoding="utf-8")
    write(RES / "drawable" / "ic_launcher_foreground.xml", vector(fg, note_for(src), placement))
    write(RES / "drawable" / "ic_launcher_monochrome.xml", vector(mono, note_for(src), placement))
    colours = {"ic_launcher_background": flat_background(src)}
    for name in ("ic_launcher", "ic_launcher_round"):
        write(RES / "mipmap-anydpi-v26" / f"{name}.xml", adaptive("ic_launcher_background", "ic_launcher_foreground"))
    for name, folder in others.items():
        other = (ROUND3 / folder).resolve()
        assert (other / "monochrome.svg").read_text(encoding="utf-8") == mono, \
            f"{other.name} is not the same drawing as {src.name}"
        write(RES / "drawable" / f"ic_launcher_{name}_foreground.xml",
              vector((other / "foreground.svg").read_text(encoding="utf-8"), note_for(other), placement))
        background = "ic_launcher_background"
        if flat_background(other) != colours[background]:
            background = f"ic_launcher_{name}_background"
            colours[background] = flat_background(other)
        write(RES / "mipmap-anydpi-v26" / f"ic_launcher_{name}.xml",
              adaptive(background, f"ic_launcher_{name}_foreground"))
    write(RES / "values" / "ic_launcher_background.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          + "".join(f'    <color name="{name}">{value}</color>\n' for name, value in colours.items())
          + "</resources>\n")
    # Play Store icon: the visible 72dp square, full bleed (Play applies its own mask).
    body = (src / "icon.svg").read_text(encoding="utf-8").split(">", 1)[1]
    write(ANDROID / "app-icon.svg",
          '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">' + body)
    with tempfile.TemporaryDirectory() as tmp:
        render_pngs(src / "icon.svg", Path(tmp))
        feature_graphic(src, Path(tmp))


if __name__ == "__main__":
    main()
