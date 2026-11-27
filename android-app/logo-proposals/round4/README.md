# Round 4: the same parts, stacked on one axis

Proposals only. The app still ships round 3's `15-final-teal`.

15 sets four small things side by side (arch, mushaf on a rahla, minaret, mute badge),
so each one gets about a third of the icon. Below 64 px the mushaf, the rahla and the
minaret's tiers close up, and the thin ribbon on cream is light next to other icons.

Round 4 keeps 15's parts and colours and nests the parts on the centre line, so each
is drawn much larger: the minaret is 1.5 times as wide as in 15, the mushaf 1.25 to 1.5 times.

## With the mute badge

The badge is the owner's hint that the app silences the phone during prayer. It sits on
the arch's left foot, as in 15, and the arch moves 1 to 2.5dp to the right to make room.

| Folder | What it shows | Badge |
|---|---|---|
| `07-bab-minaret-silent-teal` | Kairouan's minaret seen through a solid arch, on the app's teal | 16dp across (15's is 12.5dp) |
| `08-bab-minaret-silent-cream` | As 07, a teal arch on 15's cream | 16dp |
| `09-arch-mushaf-silent-teal` | The ribbon arch over an open mushaf, with a crescent; the left leg ends in the badge | 13.6dp |
| `10-arch-mushaf-silent-cream` | As 09 on cream | 13.6dp |

As in round 3, the badge touches the arch only. It clears the minaret by 3.2dp in 07
and 08, and the mushaf by 1.3dp in 09 and 10 (the mushaf is smaller there to allow it).

## Without the badge

| Folder | What it shows |
|---|---|
| `01-bab-minaret-teal`, `02-bab-minaret-cream` | 07 and 08 without the badge, arch centred |
| `03-arch-mushaf-teal`, `04-arch-mushaf-cream` | 09 and 10 without the badge, with a larger mushaf |
| `05-one-axis-teal`, `06-one-axis-cream` | Arch, minaret and mushaf together |

05 and 06 have no badge version: the mushaf fills the foot of the arch, so a badge
there would touch it or shrink to 10dp.

## Notes

Kept from round 3: the arch curve and its two-tone fold at the apex, the owner's mushaf
(abstract lines, never letters), the mute badge, and the minaret's crests, openings and
dome. The minaret's base is now 1.7 times as tall as wide, like the real one (15's is 2.3).
Dropped: the rahla and the ground band. In 05 and 06 the minaret stands behind the
mushaf, with a gap around the book and nothing under it.

All ink is inside the 66dp safe circle in every folder.

```
python -I _gen/gen.py
python -I ../round2/_tools/check_icon.py 07-bab-minaret-silent-teal 09-arch-mushaf-silent-teal
python -I ../round2/_tools/render_preview.py 07-bab-minaret-silent-teal 09-arch-mushaf-silent-teal
python -I _gen/sheet.py sheet-silent.png ../round3/15-final-teal 07-bab-minaret-silent-teal 08-bab-minaret-silent-cream 09-arch-mushaf-silent-teal 10-arch-mushaf-silent-cream
```

`sheet-silent.png` shows 15 and the four badge versions large, at 96/64/48/32 px, and
themed. `sheet-large.png` shows all ten the same way.

Not installed and not tried with `round3/_gen/export_app.py`. That script needs a flat
background colour; the teal folders use a radial gradient (`#007567` to `#004D40`), so
they need either a flat teal or gradient support in the exporter.

## Blue colours on the same shapes (27 November)

The owner wants the app's user to choose the logo's colours, and asked whether
`06-one-axis-cream` is good for its shapes or only for its colours. These two folders
hold the one-axis drawing in blue. Only the colours differ: the path data is the same,
byte for byte, as in 05 and 06.

| Folder | Made from | Colours |
|---|---|---|
| `11-one-axis-blue` | `05-one-axis-teal` | The field and whites of `round3/02-arch-blue/polished`: gradient `#2E70B8` / `#245FA5` / `#1B4D88`, arch `#FFFDFA` with a `#D6E5F3` fold, cover `#153C66`, page lines `#1B4D88`. The sand minaret is 05's. |
| `12-one-axis-blue-cream` | `06-one-axis-cream` | Teal `#004D40` becomes `#1B4D88` and `#00695C` becomes `#2E70B8`. Nothing else changes. |

**Installed 27 November:** `06-one-axis-cream` is the phone app's logo, and
`12-one-axis-blue-cream` is its second colour. The app's user chooses between them at the
end of the prayer tab's settings sheet. Each colour is one launcher entry in the manifest
(`LauncherTeal`, `LauncherBlue`), and `LauncherIcon.kt` turns one on. To export again:

```
python -I ../round3/_gen/export_app.py ../round4/06-one-axis-cream blue=../round4/12-one-axis-blue-cream
```

They were made with `sed` colour swaps on the SVG files, not with `_gen/gen.py`, so
`gen.py` does not know them. `sheet-blue.png` shows them next to the polished blue
logo and 06 at 200, 80, 48, 32 and 24 px, as the launcher's central 72dp crop.
