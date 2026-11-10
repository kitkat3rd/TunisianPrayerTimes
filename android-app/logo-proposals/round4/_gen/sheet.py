"""Comparison sheet for round 4: every proposal large, at launcher sizes and themed.

Usage: python -I sheet.py <out.png> <proposal-dir> [...]
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "round2" / "_tools"))
from render_preview import CSS, MASKS, icon, layers, screenshot, themed  # noqa: E402

BIG, PER_ROW = 220, 4


def cell(d: Path) -> str:
    bg, fg, mono = layers(d)
    small = "".join(icon(bg, fg, s, MASKS["squircle" if s == 64 else "circle"]) for s in (96, 64, 48, 32))
    return (
        '<div class="cell" style="width:300px;gap:12px">'
        f"{icon(bg, fg, BIG)}"
        f'<div class="row" style="gap:10px;align-items:center;justify-content:center">{small}</div>'
        f'<div class="row" style="gap:10px;justify-content:center">{themed(mono, 64, "light")}{themed(mono, 64, "dark")}'
        f'{themed(mono, 32, "light")}{themed(mono, 32, "dark")}</div>'
        f'<span style="font:600 14px \'Segoe UI\',sans-serif;color:#222">{d.name}</span></div>'
    )


def main(argv) -> None:
    out = Path(argv[0]).resolve()
    dirs = [Path(a).resolve() for a in argv[1:]]
    rows = (len(dirs) + PER_ROW - 1) // PER_ROW
    html = (
        f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head>'
        '<body style="background:#e7e3d8"><div style="display:flex;flex-wrap:wrap;gap:28px 20px;padding:24px">'
        + "".join(cell(d) for d in dirs)
        + "</div></body></html>"
    )
    screenshot(html, out, PER_ROW * 320 + 48, rows * 500 + 40)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
