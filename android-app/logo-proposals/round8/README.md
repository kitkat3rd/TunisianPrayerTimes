# Round 8: the minaret in its wall, the mute sign in the ground

Proposals only. The app still ships round 3's `15-final-teal`.

Round 7's pick (`round7/final-teal`) left three faults open: the mute sign read as a
caption under the arch, the minaret was small, and harsh reviewers read the arch's
outline as a keyhole, a gravestone or a bell. Round 8 drops the arch.

## The pick: `final`

Kairouan's minaret standing in the mosque's arcaded courtyard wall, on a ground line that
runs off both sides of the icon. Below the line the ground is dark, and the muted speaker
sits in it on the minaret's axis. Nothing frames the scene; the launcher's mask does.

- **Minaret:** drawn from its parts, not traced. Three tiers 0.965, 0.70 and 0.46 of the
  base's width (the real upper tiers are 0.71 and 0.51), 7 and 5 merlons as in round 3's
  approved drawing, a shadow band under each crest, three windows in the base, a small
  dome, and a crescent with both horns up. It is 16.3dp wide and 45.2dp tall with the
  finial; round 7's was 13dp wide.
- **Wall:** the minaret's sand 15% darker, with an arcade of wide openings on thin piers.
- **Mute sign:** the speaker and the x have the same bounding box (8.04 x 8.74dp), each
  0.96dp from the axis. The x has 12% more area than the speaker, because at equal area
  reviewers still saw the solid speaker as heavier. It is the minaret's light stone, the
  ground line's colour, and 25% of the icon's width.
- **House rules:** the sign is 5.5dp below the minaret's foot and touches nothing. No
  bell, no arch-topped slab.

`sheet.png` shows today's icon, round 7's pick and `final`. `masks.png` shows `final`
under a circle, a squircle and a rounded square at 192, 96 and 48 px.

## Checks (`python -I ../round7/_gen/verify.py final`)

| Check | Result |
|---|---|
| Every path except the sign is an exact mirror image, point by point | 9 of 9 paths |
| Icon pixels that differ from their mirror image outside the sign (720 px render) | 0 of 518,400 |
| Minaret and sign inside the 66dp safe circle | yes: they reach 30.7dp of 33 |
| Crescent tip below the visible top edge | 5.3dp |
| VectorDrawable-safe (round 2's `check_icon.py`) | OK |
| Converts with round 3's `export_app.vector()` | yes: `final/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` |

`check_icon.py` reports 70% of the foreground outside the safe circle. That is the ground,
the wall and the ground line, which run off the icon on purpose.

## Harsh blind reviews

Each reviewer saw only a labelled line-up (`_reviews/`) and the brief, was told to be harsh,
and listed only necessary changes. Scores are out of 10.

| Line-up | Top | `round7/final-teal` in the same line-up | Ship? |
|---|---|---|---|
| 7 | `02-seal-dark`, 6 | 3.5; keyhole moderate to strong | no |
| 8 | `04-seal-drawn`, 6 | 4; keyhole moderate, gravestone weak to moderate | no |
| 9 | `05-seal-stepped`, 6.5 | 4.5; keyhole strong, gravestone moderate | no |
| 10 (reviewer X) | `07-field`, 6 | 4; keyhole moderate to strong | no |
| 13 (reviewer Y, fresh) | `11-scene-wall`, 6.5 | 4.5; today's icon 2 | no |

Reviewers X and Y were then shown each rework in turn:

| Version | X | Y |
|---|---|---|
| `11-scene-wall` (olive wall, sand sign at 22%) | 8, ship | 6.5, no: sign a smudge at 48 px, wall looks dirty |
| `13-scene-wall-sand-line` (sand wall, cream sign at 25%) | 7.5, no: cream sign competes; separate arches a weak row of slabs | 7.5, ship |
| `final` (arcade, light-stone sign at 25%) | **8, ship: "Nothing necessary is left"** | **8, ship: "Nothing further is necessary"** |

(`_explored/11-scene-wall` now carries the later sand wall, not the olive one reviewed.)

For `final`, both reviewers found no bell, gravestone, keyhole, clock, coin or padlock;
weak chess rook, castle, bridge and media player; lighthouse none to weak. Both called
the composition symmetrical and the sign balanced.

## What was tried and dropped in this round

| Folder in `_explored` | Idea | Why it went |
|---|---|---|
| `01` to `03` | Ivory ring, round 7's traced minaret, sign in the segment under a ground line | Minaret small; `01`'s ivory ground makes the sign the loudest thing |
| `04` to `06` | Larger ring, drawn minaret with deeper setbacks; `06` with one window | Ring inside a round launcher mask is a ring in a ring; coin; lighthouse |
| `07`, `08` | No ivory ring, or a thin one | A disc inside a rounded-square icon; crescent crowds the rim |
| `09`, `10` | Full-bleed scene without the wall | A lone tower on a horizon over a dark band is a lighthouse |
| `11` to `13` | The wall, in three colourings | See the table above |

## Still the owner's call

- **No arch and no mushaf.** Today's icon has both; this one has the minaret and its wall.
- **A picture over a band.** Both reviewers said this caps the score at 8.
- **Sky colour.** `#14857A` is lighter than the app's teal (`#00695C`). `_explored/10-scene-teal`
  shows a darker sky, without the wall.
- **Small sizes.** At 48 px the arcade is texture and the sign is small but readable.
- **Realism.** The upper tiers are set back a little more than the real ones so the three
  tiers can be counted at 96 px.

## Regenerating

```
python -I _gen/gen.py                        # every folder's SVG layers (needs round7/_gen/traced.json)
python -I ../round7/_gen/verify.py final
python -I ../round2/_tools/check_icon.py final
python -I ../round2/_tools/render_preview.py final
python -I _gen/masktest.py final masks.png
python -I ../round4/_gen/sheet.py sheet.png ../round3/15-final-teal ../round7/final-teal final
```

Not installed. `final` has the flat background colour `round3/_gen/export_app.py` needs,
but that script has not been run on it.
