# Round 7: a symmetrical icon

Proposals only. The app still ships round 3's `15-final-teal`.

Superseded by `../round8/final`. Harsh reviewers scored this round's pick 3.5 to 4.5 next
to round 8's candidates and read its arch as a keyhole or a gravestone.

The owner's direction: keep working until the logo is right; it need not be one of
round 6's three; the muted-speaker versions so far were all lopsided.

## The pick: `final-teal` (and `final-cream`, the same drawing on the current icon's cream)

Kairouan's minaret seen through a horseshoe arch, with the muted speaker under the arch.
Arch, minaret and sign share one vertical axis.

- **Arch and minaret** are traced from a concept render (`V1-seal-badge.png`, made by an
  image model from round 6's A) and cleaned up: the render's badge is removed, the arch's
  frame is lighter, and the minaret is 8% wider with over half the taper of its base taken out.
- **The finial's crescent** has both horns up, so it is symmetrical too.
- **The mute sign** is the app's own (speaker and x, as `ic_volume_off`), redrawn so its
  halves match: the x has the speaker's area, and the sign is placed by its centre of mass.
- **House rule:** the sign is 6dp below the minaret, with the arch's bottom bar between them.

`sheet.png` shows today's icon, `final-teal` and `final-cream` at launcher sizes and themed.

## Checks (`python -I _gen/verify.py final-teal`, `check_icon.py final-teal`)

| Check | Result |
|---|---|
| Every path except the sign is an exact mirror image, point by point | 6 of 6 paths |
| The sign's centre of mass | x = 54.00; 50% of its ink each side of the axis |
| Icon pixels that differ from their mirror image outside the sign (720 px render) | 2 of 518,400 (one anti-aliased pair) |
| Ink outside the 66dp safe circle | 0.0%; the artwork reaches 30.0dp of 33 |
| VectorDrawable-safe (round 2's `check_icon.py`) | OK |
| Converts with round 3's `export_app.vector()` | yes: `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` in each folder |

## What was tried and dropped

| Idea | Why it went |
|---|---|
| Badge on the arch's bottom bar (`V1-seal-badge.png`) | The badge covers the minaret's foot |
| Badge hanging under the arch | An arch with a ball under its mouth is a bell |
| Base band that swells into a plaque (`V2-plaque.png`) | Reads as a bell jar |
| Arch with only a crescent and a large mute sign (`V3-quiet-door.png`) | A sound-settings icon; no minaret |
| `_explored/01-ground-ivory`: full-width ivory ground carrying the sign | Picture over a control bar; the ivory dissolves into a light wallpaper |
| `_explored/02-band-dark`: the same as a dark strip | Still a caption bar; the circle crops it into a segment |
| `_explored/05`, `06`: solid doorway | The minaret washes out on ivory; on cream the icon vanishes on light wallpapers |
| `_explored/08-refined-gold`: gold sign | The eye goes to the sign first |
| `_explored/10-panel`: sign cut into a solid base of the arch | Arched slab with an inscription: too close to a gravestone |
| `_explored/11-balanced`: narrower arch, thinner frame | Weaker at small sizes than the sturdier frame |
| `_explored/12-two-panel-door`: sign in a lower panel of the arch | Reads as a nameplate; moderate gravestone risk |
| An x as tall as the speaker | Its arms get thin and it reads as a close button |

`sheet-explored.png` shows the `_explored` folders.

## Blind reviews

Each review was a fresh reviewer who saw only a labelled line-up (`_reviews/`) and the
brief, not the project. Scores are out of 10.

| Review | Line-up | Top | Notes |
|---|---|---|---|
| 1 | `01-ground-ivory` alone | 4.5 | "Two icons stacked"; led to dropping the band |
| 2 | 7: today's icon, round 4's 07, round 6's A, four from this round | `03-lockup-teal`, 6.5 | Today's icon 2, round 4's 07 4, round 6's A 3 |
| 3 | 5 refinements | `07-refined-ivory`, 6 | Sign still left-heavy; led to the matched sign |
| 4 | 4 refinements | `07-refined-ivory`, 6.5 | `10-panel` disqualified (gravestone) |
| 5 | 7: today's icon, picks of rounds 4, 5 and 6, `final-teal`, `final-cream` | `final-teal`, 6 | `final-cream` second; reviewer told to be harsh, would not ship |
| 6 | 3: `final-teal`, `10-panel`, `12-two-panel-door` | `final-teal`, 7.5 | "Yes, ship. No changes are necessary"; no unintended resemblance |

Reviews 1 to 5 told the reviewer to be harsh; review 6 asked for an exacting but fair
review and said that phones draw launcher icons at 96 to 192 px.

## Still the owner's call

- **Teal or cream.** Reviews prefer teal (the cream circle fades on a light wallpaper);
  cream matches today's icon.
- **The sign as a caption.** Every reviewer noticed the sign sits under the arch, not in
  it. Each way of tying it to the arch failed for a reason in the table above.
- **No mushaf.** Today's icon has one; this one has the minaret only.
- **Keyhole or bell.** Review 3 read the arch's outline as a keyhole or a notification bell,
  and review 4 as a weak keyhole. Reviews 4, 5 and 6 saw no bell, and review 6 no keyhole.
  The house rule is no bells, so look at the outline with that in mind.

## Regenerating

```
python -I _gen/trace.py                      # once: V1-seal-badge.png -> _gen/traced.json
python -I _gen/gen.py                        # every folder's SVG layers
python -I _gen/verify.py final-teal
python -I ../round2/_tools/check_icon.py final-teal final-cream
python -I ../round2/_tools/render_preview.py final-teal final-cream
python -I ../round4/_gen/sheet.py sheet.png ../round3/15-final-teal final-teal final-cream
```

Not installed. Both folders have the flat background colour `round3/_gen/export_app.py`
needs, but that script has not been run on them.
