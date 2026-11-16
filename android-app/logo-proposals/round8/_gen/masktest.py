"""One icon under the three common launcher masks at 192, 96 and 48 px.

Usage: python -I masktest.py <proposal-dir> <out.png>

Rows: circle, squircle, rounded square. Left half on a light wallpaper, right half on a
dark one. Each mask is cut from the icon's visible 72dp square, as a launcher does.
"""

import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "round7" / "_gen"))
from raster import raster  # noqa: E402

SIZES = (192, 96, 48)
MASKS = (("circle", 0.5), ("squircle", 0.34), ("rounded square", 0.22))


def masked(icon, size, radius_share):
    big = size * 4
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=big * radius_share, fill=255)
    out = icon.resize((size, size), Image.LANCZOS).convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out


def main(argv):
    d, out = Path(argv[0]).resolve(), Path(argv[1]).resolve()
    with tempfile.TemporaryDirectory() as tmp:
        png = Path(tmp) / "r.png"
        raster(d / "icon.svg", png, 768, visible=True)
        icon = Image.open(png).convert("RGB")
    half, row = 430, 230
    sheet = Image.new("RGB", (2 * half, row * len(MASKS) + 10), "#E7E3D8")
    draw = ImageDraw.Draw(sheet)
    draw.rectangle((half, 0, 2 * half, sheet.height), fill="#1D2120")
    font = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 15)
    for r, (name, share) in enumerate(MASKS):
        for side, ink in ((0, "#333333"), (1, "#BBBBBB")):
            x = side * half + 20
            for size in SIZES:
                m = masked(icon, size, share)
                sheet.paste(m, (x, r * row + 20 + (192 - size) // 2), m)
                x += size + 24
            draw.text((side * half + 20, r * row + 216), f"{name}: 192, 96, 48 px", fill=ink, font=font)
    sheet.save(out)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
