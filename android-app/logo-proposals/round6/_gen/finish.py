"""Round 6: turn the image model's concept renders into icon previews.

Usage: python -I finish.py

The renders (A-, B-, C-*.png) are flat pictures, not icon layers. A and B came back
with a partly transparent background (a ragged edge on A, cloudy patches on B); this
fills it with the picture's own background colours. Each picture is then cropped so
the subject sits inside the launcher's safe circle (*-icon.png), and sheet.png shows
the three under launcher masks at 96, 64 and 48 px.
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent

# name: (subject centre x, y, radius of the circle that holds the whole subject), in source pixels
SUBJECTS = {
    "A-arch-minaret": (592, 614, 423),
    "B-quiet-crescent": (627, 621, 388),
    "C-sidi-bou-said-door": (648, 610, 422),
}
SAFE = 0.44  # subject radius as a share of the icon's side (the safe circle is 0.458)


def backdrop(rgba: np.ndarray) -> np.ndarray:
    """The picture's background, rebuilt from its opaque background pixels as a smooth radial fit."""
    h, w = rgba.shape[:2]
    rgb, alpha = rgba[..., :3].astype(float), rgba[..., 3]
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot(xx - w / 2, yy - h / 2)
    # Background pixels: opaque and close to the colour the opaque pixels have most often.
    opaque = alpha >= np.median(alpha) - 10  # the renders are never fully opaque
    base = np.median(rgb[opaque & (r > w * 0.3)], axis=0)
    bg = opaque & (np.abs(rgb - base).sum(axis=2) < 60)
    out = np.empty_like(rgb)
    reach = np.percentile(r[bg], 98)
    for ch in range(3):
        # Colour as a quadratic in x, y and the distance from the centre.
        sample = np.flatnonzero(bg.ravel())[::37]
        cols = lambda idx: np.stack([np.ones(len(idx)), xx.ravel()[idx] / w, yy.ravel()[idx] / h,
                                     (np.minimum(r.ravel()[idx], reach) / w) ** 2], axis=1)
        coef, *_ = np.linalg.lstsq(cols(sample), rgb[..., ch].ravel()[sample], rcond=None)
        every = np.arange(h * w)
        out[..., ch] = (cols(every) @ coef).reshape(h, w)
    return np.clip(out, 0, 255)


def flatten(path: Path) -> Image.Image:
    rgba = np.asarray(Image.open(path).convert("RGBA"))
    if rgba[..., 3].min() == 255:
        return Image.fromarray(rgba[..., :3])
    a = np.clip(rgba[..., 3:4].astype(float) / np.percentile(rgba[..., 3], 75), 0, 1)
    rgb = rgba[..., :3].astype(float) * a + backdrop(rgba) * (1 - a)
    return Image.fromarray(rgb.round().astype(np.uint8))


def crop(img: Image.Image, cx, cy, radius) -> Image.Image:
    half = radius / SAFE / 2
    box = tuple(round(v) for v in (cx - half, cy - half, cx + half, cy + half))
    assert box[0] >= 0 and box[1] >= 0 and box[2] <= img.width and box[3] <= img.height, box
    return img.crop(box).resize((1024, 1024), Image.LANCZOS)


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


def sheet(icons: dict) -> Image.Image:
    cell, big = 380, 300
    img = Image.new("RGB", (cell * len(icons) + 40, 560), "#E7E3D8")
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 18)
    for i, (name, icon) in enumerate(icons.items()):
        x0 = 20 + i * cell
        img.paste(masked(icon, big, "circle"), (x0 + (cell - big) // 2, 30), masked(icon, big, "circle"))
        x = x0 + (cell - (96 + 64 + 48 + 32 + 3 * 22)) // 2
        for size, shape in ((96, "squircle"), (64, "circle"), (48, "squircle"), (32, "circle")):
            small = masked(icon, size, shape)
            img.paste(small, (x, 360 + (96 - size) // 2), small)
            x += size + 22
        draw.text((x0 + cell // 2, 500), name, fill="#222222", font=font, anchor="mm")
    return img


def main():
    icons = {}
    for name, subject in SUBJECTS.items():
        icon = crop(flatten(ROOT / f"{name}.png"), *subject)
        icon.save(ROOT / f"{name}-icon.png")
        icons[name] = icon
        print(ROOT / f"{name}-icon.png")
    sheet(icons).save(ROOT / "sheet.png")
    print(ROOT / "sheet.png")


if __name__ == "__main__":
    main()
