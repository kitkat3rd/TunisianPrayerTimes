"""Measure how mirror-symmetrical an icon's artwork is about a vertical axis.

Usage: python -I symmetry.py <image.png> [...]   (add --diff to write <image>-mirror.png)

The artwork is every pixel that differs from the smooth background (a quadratic
fit to the picture's border). For each picture this prints:
  axis      the vertical line that mirrors the artwork best, as an offset from the
            picture's centre line, in percent of the picture's width
  mirror    how much of the artwork lands on artwork when mirrored about that axis
            (intersection over union; 100% is a perfect mirror image)
  weight    how far the artwork's centre of mass is from the picture's centre line,
            in percent of the width (negative is left)
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image


def artwork(rgb: np.ndarray, alpha=None) -> np.ndarray:
    if alpha is not None and alpha.min() < 250:
        return alpha > 127
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    border = np.zeros((h, w), bool)
    m = max(4, w // 25)
    border[:m], border[-m:], border[:, :m], border[:, -m:] = True, True, True, True
    r2 = ((xx - w / 2) ** 2 + (yy - h / 2) ** 2) / w**2
    cols = lambda mask: np.stack([np.ones(mask.sum()), xx[mask] / w, yy[mask] / h, r2[mask]], axis=1)
    every = np.ones((h, w), bool)
    fit = np.empty(rgb.shape, float)
    for ch in range(3):
        coef, *_ = np.linalg.lstsq(cols(border), rgb[..., ch][border].astype(float), rcond=None)
        fit[..., ch] = (cols(every) @ coef).reshape(h, w)
    return np.abs(rgb.astype(float) - fit).sum(axis=2) > 70


def measure(mask: np.ndarray):
    h, w = mask.shape
    xs = np.nonzero(mask)[1]
    centroid = xs.mean()
    best = (0.0, w / 2)
    for axis2 in range(int(2 * centroid) - w // 8, int(2 * centroid) + w // 8):  # axis = axis2 / 2
        # Mirror about x = axis2 / 2: column x goes to axis2 - x.
        lo, hi = max(0, axis2 - (w - 1)), min(w - 1, axis2)
        a = mask[:, lo:hi + 1]
        b = a[:, ::-1]
        union = (a | b).sum() + mask.sum() - a.sum()
        iou = (a & b).sum() / union if union else 0
        if iou > best[0]:
            best = (iou, axis2 / 2)
    return best[0], (best[1] - (w - 1) / 2) / w, (centroid - (w - 1) / 2) / w


def main(argv):
    diff = "--diff" in argv
    for a in (a for a in argv if a != "--diff"):
        p = Path(a)
        img = Image.open(p).convert("RGBA")
        arr = np.asarray(img)
        mask = artwork(arr[..., :3], arr[..., 3])
        iou, axis, weight = measure(mask)
        print(f"{p.name}: axis {axis:+.1%}, mirror {iou:.1%}, weight {weight:+.1%}")
        if diff:
            w = mask.shape[1]
            ax2 = round(2 * (axis * w + (w - 1) / 2))
            mirrored = np.zeros_like(mask)
            xs = np.arange(w)
            src = ax2 - xs
            ok = (src >= 0) & (src < w)
            mirrored[:, xs[ok]] = mask[:, src[ok]]
            out = np.full(mask.shape + (3,), 255, np.uint8)
            out[mask & mirrored] = (40, 40, 40)
            out[mask ^ mirrored] = (220, 40, 60)
            Image.fromarray(out).save(p.with_name(p.stem + "-mirror.png"))


if __name__ == "__main__":
    main(sys.argv[1:])
