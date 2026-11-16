# Round 5: three icons from the brief alone

Proposals only. The app still ships round 3's `15-final-teal`.

The owner asked for three icons designed as if round 3's icon had never been seen.
The brief used: prayer times for Tunisia; the phone goes quiet while you pray. Each
icon is one idea in two or three flat colours from the app's palette, and each says
"quiet" in a different way.

| Folder | Idea | How it says quiet | Reads down to |
|---|---|---|---|
| `01-five-stones` | A pointed horseshoe arch built of five stones, one per daily prayer, in alternating cream and gold like Maghrebi arch stones | The phone's muted speaker stands in the doorway | 48 px (below that the speaker closes up and only the arch is left) |
| `02-pause-dome` | A dome with a crescent on two pillars | The two pillars are the pause sign: the phone pauses for prayer | 24 px |
| `03-kairouan-moon` | Kairouan's stepped minaret, in blocks, in the mouth of a large crescent | The crescent is also the Do Not Disturb moon on phones that use one | 24 px |

Risks:

- 01: the × touches the speaker only, but a mute sign inside an arch can be read as
  "the mosque is muted". The house rule is never to mute a religious element; round 3
  allowed the badge on the arch, but not on the minaret or the mushaf.
- 02: the pause sign is seen only once it is pointed out; before that it is a small
  domed building. Read literally it could say "prayer paused".
- 03: the weakest hint of silence, and a crescent holding a mosque is common among
  prayer apps. The merlons can make the tower look like a chess rook at 36 px.

All ink is inside the 66dp safe circle (`check_icon.py` passes on all three).

```
python -I _gen/gen.py
python -I ../round2/_tools/check_icon.py 01-five-stones 02-pause-dome 03-kairouan-moon
python -I ../round2/_tools/render_preview.py 01-five-stones 02-pause-dome 03-kairouan-moon
python -I ../round4/_gen/sheet.py sheet.png 01-five-stones 02-pause-dome 03-kairouan-moon
```

`sheet.png` shows the three large, at 96/64/48/32 px, and themed.
`round3/_gen/export_app.py` needs a flat background colour: 02 has one, 01 and 03 use
a radial gradient. None has been run through that script.
