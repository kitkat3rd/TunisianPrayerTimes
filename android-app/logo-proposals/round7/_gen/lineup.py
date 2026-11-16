"""A labelled line-up of icons for a blind review.

Usage: python -I lineup.py <out.png> <label>=<icon.svg | icon.png> [...]

Each icon is shown as a launcher would: under a circle mask at 280 px on a light
wallpaper, and under a squircle at 96 and 48 px on a dark one. SVGs are the 108dp
adaptive canvas (the central 72dp is shown); PNGs are taken as the visible square.
"""

import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from raster import raster  # noqa: E402

BIG = 280


def load(src: Path) -> Image.Image:
    if src.suffix.lower() == ".svg":
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "r.png"
            raster(src, out, 720, visible=True)
            return Image.open(out).convert("RGB")
    return Image.open(src).convert("RGB")


def masked(icon: Image.Image, size: int, shape: str) -> Image.Image:
    big = size * 4
    mask = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, big - 1, big - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.3, fill=255)
    out = icon.resize((size, size), Image.LANCZOS).convert("RGBA")
    out.putalpha(mask.resize((size, size), Image.LANCZOS))
    return out


def main(argv):
    out = Path(argv[0]).resolve()
    items = [(a.split("=", 1)[0], load(Path(a.split("=", 1)[1]).resolve())) for a in argv[1:]]
    cell = BIG + 40
    sheet = Image.new("RGB", (cell * len(items) + 20, BIG + 250), "#E7E3D8")
    draw = ImageDraw.Draw(sheet)
    draw.rectangle((0, BIG + 90, sheet.width, sheet.height), fill="#1D2120")
    font = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 30)
    for i, (label, icon) in enumerate(items):
        x = 20 + i * cell
        big = masked(icon, BIG, "circle")
        sheet.paste(big, (x + 10, 20), big)
        draw.text((x + 10 + BIG // 2, BIG + 52), label, fill="#222222", font=font, anchor="mm")
        for size, dx in ((96, 40), (48, 180)):
            small = masked(icon, size, "squircle")
            sheet.paste(small, (x + dx, BIG + 120 + (96 - size) // 2), small)
    sheet.save(out)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
