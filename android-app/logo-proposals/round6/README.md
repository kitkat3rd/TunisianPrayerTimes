# Round 6: concept renders from an image model

Proposals only. The app still ships round 3's `15-final-teal`.

The owner found round 5's hand-built geometry ugly, so these three were rendered by an
image model (openai/gpt-image-2.5-flare) from written art direction. They are flat
pictures to choose a look from, not icon layers: the chosen one still has to be redrawn
as vector foreground, background and monochrome layers.

| File | What it shows |
|---|---|
| `A-arch-minaret.png` | Kairouan's minaret through an ivory horseshoe arch on teal, mute badge on the arch's left foot |
| `B-quiet-crescent.png` | A gold crescent holding a small minaret; sound bars fade to a dot below |
| `C-sidi-bou-said-door.png` | A Sidi Bou Said doorway in cobalt and whitewash with a crescent and a mushaf on a rahla; mute badge on the right foot |

`_gen/finish.py` fills the partly transparent backgrounds A and B came back with,
crops each picture so the subject sits inside the launcher's safe circle (`*-icon.png`),
and writes `sheet.png` (launcher masks at 96, 64, 48 and 32 px).

Known faults to fix in a redraw: A's minaret has four tiers (the real one has three)
and its arch is closed along the bottom like a window frame; B's minaret is generic
and stands on the crescent; C is blue, not the app's teal.
