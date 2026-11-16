// Misbaha tab icon proposals on the family's 24-unit grid (2026-11-12: the ring of beads read as too circular).
// Beads are placed at equal arc lengths along a cord made of cubic segments; every shape is filled, as in the
// current ic_tab_adhkar (same icon in both tab states). Usage:
//   node beads.mjs sheet <out.html>        proposal sheet (large, 24dp grey, 24dp in the pill, tab bar mock)
//   node beads.mjs vd <name> <out.xml>     VectorDrawable of one proposal
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const DRAWABLE = join(HERE, "..", "..", "app", "src", "main", "res", "drawable");

// ---------------------------------------------------------------- geometry helpers
const cubic = (p0, p1, p2, p3, t) => {
  const a = (1 - t) ** 3, b = 3 * (1 - t) ** 2 * t, c = 3 * (1 - t) * t * t, d = t ** 3;
  return [a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]];
};

/** Dense polyline of a path given as [start, [c1, c2, end], [c1, c2, end], ...] (a 1-element entry = straight line). */
function sample(path, n = 240) {
  const pts = [path[0]];
  let cur = path[0];
  for (const seg of path.slice(1)) {
    if (seg.length === 1) {
      for (let i = 1; i <= n; i++) pts.push([cur[0] + (seg[0][0] - cur[0]) * i / n, cur[1] + (seg[0][1] - cur[1]) * i / n]);
    } else {
      for (let i = 1; i <= n; i++) pts.push(cubic(cur, seg[0], seg[1], seg[2], i / n));
    }
    cur = seg[seg.length - 1];
  }
  return pts;
}

function lengths(pts) {
  const acc = [0];
  for (let i = 1; i < pts.length; i++) acc.push(acc[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
  return acc;
}

/** Point and unit tangent at arc length s. */
function at(pts, acc, s) {
  let i = acc.findIndex((v) => v >= s);
  if (i === -1) i = acc.length - 1; // past the end: carry on along the last step
  if (i === 0) i = 1;
  const f = (s - acc[i - 1]) / (acc[i] - acc[i - 1] || 1);
  const [a, b] = [pts[i - 1], pts[i]];
  const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
  return { p: [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f], t: [(b[0] - a[0]) / len, (b[1] - a[1]) / len] };
}

/** n beads from arc length s0 to s1 inclusive; `sep` lists indices drawn as elongated separators. */
function beadsAlong(pts, s0, s1, n, r, sep = []) {
  const acc = lengths(pts);
  const out = [];
  for (let i = 0; i < n; i++) {
    const { p, t } = at(pts, acc, s0 + (s1 - s0) * i / (n - 1));
    out.push(sep.includes(i)
      ? { kind: "ellipse", c: p, rx: r * 1.18, ry: r * 0.62, rot: Math.atan2(t[1], t[0]) * 180 / Math.PI }
      : { kind: "circle", c: p, r });
  }
  return out;
}

// ---------------------------------------------------------------- the pieces every misbaha shares
let R = 1.5; // bead radius
const CORD = 1.0; // cord width
let SPACING = 4.15; // bead centre to centre

/** Imam bead and tassel hanging from the pinch (0,0) along +y, in the local frame. */
function imamAndTassel() {
  return {
    beads: [{ kind: "ellipse", c: [0, 2.5], rx: 1.3, ry: 1.75, rot: 0 }],
    cords: [[[0, 0], [0, 4.6]]],
    tassels: [[[-0.75, 4.45], [0.75, 4.45], [2.15, 8.7], [-2.15, 8.7]]],
  };
}

/** Teardrop loop that closes at the pinch (0,0) and rises along -y; `tip` sharpens the pinch. */
function teardrop({ w = 7.2, h = 14.2, tip = 0.36, g = 2.45, sep = false } = {}) {
  const loop = [[0, 0], [[-w, -h * tip], [-w, -h], [0, -h]], [[w, -h], [w, -h * tip], [0, 0]]];
  const pts = sample(loop);
  const L = lengths(pts).at(-1);
  // g: the first and last bead clear the pinch and the imam bead
  const n = Math.round((L - 2 * g) / SPACING) + 1;
  const seps = sep ? [Math.round((n - 1) / 3), Math.round(2 * (n - 1) / 3)] : [];
  const it = imamAndTassel();
  return { beads: [...beadsAlong(pts, g, L - g, n, R, seps), ...it.beads], cords: [pts, ...it.cords], tassels: it.tassels };
}

/** Run `make` with another bead size and spacing. */
function withBeads(r, spacing, make) {
  const [r0, s0] = [R, SPACING];
  R = r; SPACING = spacing;
  try { return make(); } finally { R = r0; SPACING = s0; }
}

/** Strand draped over a hand, as in Fluent's prayer beads: up, over, down, under, up. */
function draped() {
  const x0 = -7.6, x1 = 0, x2 = 7.6, top = -12.6, bot = -1.6;
  const path = [[x0, 0], [[x0, -6]], [[x0, top - 3.9], [x1, top - 3.9], [x1, top]], [[x1, bot]],
    [[x1, bot + 4.4], [x2, bot + 4.4], [x2, bot]], [[x2, top - 1.2]]];
  const pts = sample(path);
  const L = lengths(pts).at(-1);
  const n = Math.round((L - 1.9 - 0.2) / SPACING) + 1;
  const it = imamAndTassel();
  const shift = (o) => ({ ...o, c: [o.c[0] + x0, o.c[1]] });
  return {
    beads: [...beadsAlong(pts, 1.9, L - 0.2, n, R), ...it.beads.map(shift)],
    cords: [pts, it.cords[0].map(([x, y]) => [x + x0, y])],
    tassels: it.tassels.map((t) => t.map(([x, y]) => [x + x0, y])),
  };
}

/**
 * A loose, asymmetric loop as a misbaha falls in the hand (owner 2026-11-12 liked this curve in an Icons8 drawing):
 * a tall arch over the top, a lower strand that sags a little, the pinch low on the right, a short tail of beads and
 * a brush tassel swinging down-left. Our own curve, not a trace.
 */
function natural({ r = 1.15, spacing = 3.3, cord = false, tail = 2, len = 7.8, swing = 0.16, squash = [1, 1] } = {}) {
  const P = [18.8, 11.2];
  // wide arch over the top, then a lower strand with a soft S-wave back to the pinch; `squash` widens/flattens it
  const sq = ([x, y]) => [P[0] - (P[0] - x) * squash[0], P[1] - (P[1] - y) * squash[1]];
  const loop = [P, [[20.6, 9.0], [20.9, 4.4], [17.6, 2.2]], [[15.0, 0.6], [8.8, 0.4], [5.6, 2.6]],
    [[3.4, 4.0], [3.2, 7.0], [4.6, 8.9]], [[6.0, 10.8], [8.6, 11.2], [10.8, 10.0]],
    [[12.8, 8.9], [15.2, 7.9], [16.8, 8.8]], [[17.8, 9.4], [18.4, 10.3], P]]
    .map((seg, i) => (i === 0 ? seg : seg.map(sq)));
  const pts = sample(loop);
  const L = lengths(pts).at(-1);
  const g = spacing * 0.72; // the beads either side of the pinch keep apart
  const n = Math.round((L - 2 * g) / spacing) + 1;
  const tailPath = [P, [[19.1, 12.6], [18.9, 14.0], [18.4, 15.3]], [[18.0, 16.3], [17.4, 17.2], [16.7, 18.0]],
    [[16.0, 18.8], [15.2, 19.6], [14.4, 20.4]]];
  const tpts = sample(tailPath);
  const tacc = lengths(tpts);
  const beads = beadsAlong(pts, g, L - g, n, r);
  const sTail = (k) => spacing * (0.75 + k - 1);
  for (let k = 1; k <= tail; k++) beads.push({ kind: "circle", c: at(tpts, tacc, sTail(k)).p, r });
  // imam: an elongated bead along the tail, the same gap after the last bead, then the tassel
  const sImam = sTail(tail) + spacing + 0.45 * r;
  const im = at(tpts, tacc, sImam);
  beads.push({ kind: "ellipse", c: im.p, rx: r * 1.45, ry: r * 0.95, rot: Math.atan2(im.t[1], im.t[0]) * 180 / Math.PI });
  // brush tassel in a local frame (axis +y from its knot), turned to follow the tail
  const knot = at(tpts, tacc, sImam + r * 1.45 - 0.2);
  const ang = Math.atan2(knot.t[1], knot.t[0]) - Math.PI / 2 + swing; // a little further left, under the loop
  const side = (a, b, c, d) => sample([a, [b, c, d]], 16);
  const end = [[2.45, len], [-2.45, len + 1.0]];
  const notch = (t, depth) => {
    const x = end[0][0] + (end[1][0] - end[0][0]) * t, y = end[0][1] + (end[1][1] - end[0][1]) * t;
    return [[x + 0.42, y], [x, y - depth], [x - 0.42, y + 0.05]];
  };
  const local = [...side([0.6, 0], [0.75, len * 0.38], [1.2, len * 0.72], end[0]), ...notch(1 / 3, 1.4), ...notch(2 / 3, 1.4),
    ...side(end[1], [-1.1, len * 0.78], [-0.8, len * 0.38], [-0.6, 0])];
  const [cs, sn] = [Math.cos(ang), Math.sin(ang)];
  const tassel = local.map(([x, y]) => [knot.p[0] + x * cs - y * sn, knot.p[1] + x * sn + y * cs]);
  // tip: the tassel's axis, from its knot to its fringe
  return { beads, cords: cord ? [pts, tpts] : [], tassels: [tassel], tip: [-sn, cs] };
}

/** Turn a loose loop so its tassel points along screen angle `deg` (-45 = north-east), then fit it. */
function pointing(shape, deg, size) {
  return transform(shape, deg - Math.atan2(shape.tip[1], shape.tip[0]) * 180 / Math.PI, size);
}

/** The current icon, rebuilt for the comparison row (ring of 9 beads, tassel on top). */
function ring() {
  const c = [0, 0], r = 6.5;
  const beads = [0, 36, 72, 108, 144, -36, -72, -108, -144].map((a) => ({
    kind: "circle", c: [c[0] + r * Math.sin(a * Math.PI / 180), c[1] - r * Math.cos(a * Math.PI / 180)], r: 1.65,
  }));
  const circle = Array.from({ length: 97 }, (_, i) => [r * Math.cos(2 * Math.PI * i / 96), r * Math.sin(2 * Math.PI * i / 96)]);
  return {
    beads: [...beads, { kind: "ellipse", c: [0, 8.4], rx: 1.15, ry: 1.55, rot: 0 }],
    cords: [circle, [[0, r], [0, 8.3]]],
    tassels: [[[0, 9.5], [1.7, 12.6], [-1.7, 12.6]]],
    flipY: true,
  };
}

// ---------------------------------------------------------------- placing a drawing on the 24 grid
function transform(shape, rotDeg, size) {
  const th = rotDeg * Math.PI / 180, cs = Math.cos(th), sn = Math.sin(th);
  const flip = shape.flipY ? -1 : 1;
  const rot = ([x, y]) => [x * cs - y * flip * sn, x * sn + y * flip * cs];
  // bounding box after rotation, bead radii and the tassel's rounding included
  let [minX, minY, maxX, maxY] = [1e9, 1e9, -1e9, -1e9];
  const grow = ([x, y], m) => { minX = Math.min(minX, x - m); maxX = Math.max(maxX, x + m); minY = Math.min(minY, y - m); maxY = Math.max(maxY, y + m); };
  for (const b of shape.beads) grow(rot(b.c), b.kind === "circle" ? b.r : Math.max(b.rx, b.ry));
  for (const t of shape.tassels) for (const p of t) grow(rot(p), 0.45);
  const s = size / Math.max(maxX - minX, maxY - minY);
  const [cx, cy] = [(minX + maxX) / 2, (minY + maxY) / 2];
  const map = (p) => { const [x, y] = rot(p); return [12 + (x - cx) * s, 12 + (y - cy) * s]; };
  return {
    scale: s,
    beads: shape.beads.map((b) => b.kind === "circle"
      ? { kind: "circle", c: map(b.c), r: b.r * s }
      : { kind: "ellipse", c: map(b.c), rx: b.rx * s, ry: b.ry * s, rot: b.rot * flip + rotDeg }),
    cords: shape.cords.map((c) => c.map(map)),
    tassels: shape.tassels.map((t) => t.map(map)),
  };
}

const PROPOSALS = {
  current: { label: "Current: ring", ar: "الحالية: حلقة", make: () => transform(ring(), 0, 19.6) },
  A: { label: "A · Tilted teardrop, touching beads", ar: "قطرة مائلة، حبّات متلاصقة",
    make: () => transform(withBeads(1.3, 2.95, () => teardrop({ w: 8.2, h: 18, tip: 0.5, g: 2.3 })), 40, 21) },
  B: { label: "B · Tilted teardrop, spaced beads", ar: "قطرة مائلة، حبّات متباعدة",
    make: () => transform(withBeads(1.6, 3.95, () => teardrop({ w: 8.2, h: 18, tip: 0.5, g: 2.6 })), 40, 21) },
  C: { label: "C · Upright teardrop, touching beads", ar: "قطرة قائمة، حبّات متلاصقة",
    make: () => transform(withBeads(1.3, 2.95, () => teardrop({ w: 8.6, h: 15.5, tip: 0.5, g: 2.3 })), 0, 20.4) },
  D: { label: "D · Draped strand", ar: "خيط متدلٍّ", make: () => transform(withBeads(1.4, 3.2, draped), 0, 20.4) },
  E: { label: "E · Loose loop, small beads", ar: "حلقة مرسلة، حبّات صغيرة", make: () => transform(natural({ r: 1.0, spacing: 2.75 }), 0, 21) },
  F: { label: "F · Loose loop, larger beads", ar: "حلقة مرسلة، حبّات أكبر",
    make: () => transform(natural({ r: 1.3, spacing: 3.55, tail: 1 }), 0, 21) },
  G: { label: "G · Loose loop, flatter and wider", ar: "حلقة مرسلة أعرض", make: () => transform(natural({ r: 1.15, spacing: 3.1, squash: [1.12, 0.72], len: 7.2, tail: 1 }), 0, 21) },
  // owner 2026-11-12: "rotate it such as the tail is facing north east"
  E2: { label: "E ↗ · Small beads, tassel to the north-east", ar: "حبّات صغيرة، الشرّابة نحو الشمال الشرقي",
    make: () => pointing(natural({ r: 1.0, spacing: 2.75 }), -45, 21) },
  F2: { label: "F ↗ · Larger beads, tassel to the north-east", ar: "حبّات أكبر، الشرّابة نحو الشمال الشرقي",
    make: () => pointing(natural({ r: 1.3, spacing: 3.55, tail: 1 }), -45, 21) },
  G2: { label: "G ↗ · Flatter loop, tassel to the north-east", ar: "حلقة أعرض، الشرّابة نحو الشمال الشرقي",
    make: () => pointing(natural({ r: 1.15, spacing: 3.1, squash: [1.12, 0.72], len: 7.2, tail: 1 }), -45, 21) },
  // owner 2026-11-12: "take the E proposal and rotate -30 degrees" (which E and which way: all four drawn)
  E2ccw: { label: "E ↗ turned 30° counterclockwise", ar: "E ↗ مُدارة 30° عكس عقارب الساعة",
    make: () => pointing(natural({ r: 1.0, spacing: 2.75 }), -75, 21) },
  E2cw: { label: "E ↗ turned 30° clockwise", ar: "E ↗ مُدارة 30° مع عقارب الساعة",
    make: () => pointing(natural({ r: 1.0, spacing: 2.75 }), -15, 21) },
  Eccw: { label: "E turned 30° counterclockwise", ar: "E مُدارة 30° عكس عقارب الساعة",
    make: () => transform(natural({ r: 1.0, spacing: 2.75 }), -30, 21) },
  Ecw: { label: "E turned 30° clockwise", ar: "E مُدارة 30° مع عقارب الساعة",
    make: () => transform(natural({ r: 1.0, spacing: 2.75 }), 30, 21) },
};

// ---------------------------------------------------------------- output
const f = (v) => (Math.round(v * 100) / 100).toString();
const polyD = (pts, close) => "M" + pts.map(([x, y]) => `${f(x)},${f(y)}`).join("L") + (close ? "Z" : "");
const thin = (pts) => pts.filter((_, i) => i % 6 === 0 || i === pts.length - 1);
const circleD = ({ c: [x, y], r }) => `M${f(x - r)},${f(y)}a${f(r)},${f(r)} 0 1,0 ${f(2 * r)},0a${f(r)},${f(r)} 0 1,0 ${f(-2 * r)},0Z`;
function ellipseD({ c: [x, y], rx, ry, rot }) {
  const t = rot * Math.PI / 180, dx = rx * Math.cos(t), dy = rx * Math.sin(t);
  return `M${f(x - dx)},${f(y - dy)}A${f(rx)},${f(ry)} ${f(rot)} 1,0 ${f(x + dx)},${f(y + dy)}A${f(rx)},${f(ry)} ${f(rot)} 1,0 ${f(x - dx)},${f(y - dy)}Z`;
}
const beadD = (b) => (b.kind === "circle" ? circleD(b) : ellipseD(b));

function svg(icon, px, color) {
  const cords = icon.cords.map((c) => `<path d="${polyD(thin(c), false)}" fill="none" stroke="${color}" stroke-width="${f(CORD * icon.scale)}" stroke-linecap="round" stroke-linejoin="round"/>`).join("");
  const tassels = icon.tassels.map((t) => `<path d="${polyD(t, true)}" fill="${color}" stroke="${color}" stroke-width="0.9" stroke-linejoin="round"/>`).join("");
  return `<svg width="${px}" height="${px}" viewBox="0 0 24 24">${cords}${tassels}<path d="${icon.beads.map(beadD).join("")}" fill="${color}"/></svg>`;
}

function vd(icon) {
  const cords = icon.cords.map((c) => polyD(thin(c), false)).join("");
  const tassels = icon.tassels.map((t) => polyD(t, true)).join("");
  const cordPath = cords ? `
    <path
        android:fillColor="#00000000"
        android:pathData="${cords}"
        android:strokeColor="#FF000000"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeWidth="${f(CORD * icon.scale)}" />` : "";
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by android-app/logo-proposals/tab-icons/beads.mjs -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">${cordPath}
    <path
        android:fillColor="#FF000000"
        android:pathData="${tassels}"
        android:strokeColor="#FF000000"
        android:strokeLineJoin="round"
        android:strokeWidth="0.9" />
    <path
        android:fillColor="#FF000000"
        android:pathData="${icon.beads.map(beadD).join("")}" />
</vector>
`;
}

/** The other tabs' drawables, turned into SVG for the tab bar mock. */
function drawableSvg(name, px, color) {
  const xml = readFileSync(join(DRAWABLE, `${name}.xml`), "utf-8");
  const flip = /scaleY="-1"/.test(xml);
  const paths = [...xml.matchAll(/<path([\s\S]*?)\/>/g)].map(([, a]) => {
    const get = (k) => (a.match(new RegExp(`android:${k}="([^"]*)"`)) || [])[1];
    const stroke = get("strokeColor");
    const fill = stroke && /transparent|#00/.test(get("fillColor") || "transparent") ? "none" : color;
    return `<path d="${get("pathData")}" fill="${fill}" fill-rule="${get("fillType") === "evenOdd" ? "evenodd" : "nonzero"}"`
      + (stroke ? ` stroke="${color}" stroke-width="${get("strokeWidth")}" stroke-linecap="round" stroke-linejoin="round"` : "") + "/>";
  }).join("");
  return `<svg width="${px}" height="${px}" viewBox="0 0 24 24">${flip ? `<g transform="translate(0,24) scale(1,-1)">${paths}</g>` : paths}</svg>`;
}

function tabBar(adhkarSvg, selected) {
  const DP = 2.625; // a 420 dpi phone
  const tabs = [["الصلاة", "ic_tab_prayer", "ic_tab_prayer_filled"], ["المنبهات", "ic_tab_alarms_outline", "ic_tab_alarms_filled"],
    ["القرآن", "ic_tab_quran", "ic_tab_quran_filled"], ["الأذكار", null, null], ["عباداتي", "ic_tab_qibla", "ic_tab_qibla_filled"]];
  const cells = tabs.map(([label, out, fill], i) => {
    const sel = i === selected;
    const color = sel ? "#004D40" : "#6D6D6D";
    const icon = out ? drawableSvg(sel ? fill : out, 24 * DP, color) : adhkarSvg(24 * DP, color);
    return `<div class="tab"><span class="${sel ? "pill on" : "pill"}">${icon}</span><b style="color:${color}">${label}</b></div>`;
  }).join("");
  return `<div class="bar" dir="rtl">${cells}</div>`;
}

if (process.argv[2] === "sheet") {
  const keys = process.argv.slice(4);
  const rows = Object.entries(PROPOSALS).filter(([key]) => !keys.length || keys.includes(key)).map(([key, p]) => {
    const icon = p.make();
    const draw = (px, color) => svg(icon, px, color);
    return `<section><h2>${p.label} <span dir="rtl">«${p.ar}»</span></h2><div class="row">
      <div class="big">${draw(200, "#004D40")}</div>
      <div class="small"><div>${draw(63, "#6D6D6D")}</div><div><span class="pill on">${draw(63, "#004D40")}</span></div></div>
      <div class="bars">${tabBar(draw, 3)}${tabBar(draw, 2)}</div></div></section>`;
  }).join("");
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;padding:20px 24px;background:#fff;font-family:"Segoe UI",sans-serif;width:1500px}
h2{font-size:22px;margin:18px 0 8px;font-weight:600} h2 span{font-weight:400;color:#555;margin-inline-start:10px}
section{border-top:1px solid #e3e3e3}
.row{display:flex;gap:28px;align-items:center}
.big svg{background:repeating-linear-gradient(0deg,#eee 0 1px,transparent 1px 8.333px),repeating-linear-gradient(90deg,#eee 0 1px,transparent 1px 8.333px);background-color:#FAFAFA}
.small{display:flex;flex-direction:column;gap:14px;align-items:center}
.pill{display:inline-flex;border-radius:80px;padding:8px 42px}
.pill.on{background:#E0F2F1}
.bars{display:flex;flex-direction:column;gap:14px}
.bar{display:flex;width:1079px;background:#fff;border-top:3px solid #E6E6E6;padding:16px 0 10px}
.tab{flex:1;display:flex;flex-direction:column;align-items:center;gap:6px} .tab b{font:500 31px "Segoe UI",sans-serif}
</style></head><body>${rows}</body></html>`;
  writeFileSync(process.argv[3], html, "utf-8");
  console.log(process.argv[3]);
} else if (process.argv[2] === "installed") {
  // the drawable as installed in res/drawable, read back from its XML, in the tab bar
  const draw = (px, color) => drawableSvg("ic_tab_adhkar", px, color);
  const html = `<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;padding:20px 24px;background:#fff;font-family:"Segoe UI",sans-serif;width:1500px}
.row{display:flex;gap:28px;align-items:center}
.big svg{background:#FAFAFA}
.pill{display:inline-flex;border-radius:80px;padding:8px 42px} .pill.on{background:#E0F2F1}
.bars{display:flex;flex-direction:column;gap:14px}
.bar{display:flex;width:1079px;background:#fff;border-top:3px solid #E6E6E6;padding:16px 0 10px}
.tab{flex:1;display:flex;flex-direction:column;align-items:center;gap:6px} .tab b{font:500 31px "Segoe UI",sans-serif}
</style></head><body><div class="row"><div class="big">${draw(200, "#004D40")}</div>
<div class="bars">${tabBar(draw, 3)}${tabBar(draw, 2)}</div></div></body></html>`;
  writeFileSync(process.argv[3], html, "utf-8");
  console.log(process.argv[3]);
} else if (process.argv[2] === "vd") {
  writeFileSync(process.argv[4], vd(PROPOSALS[process.argv[3]].make()), "utf-8");
  console.log(process.argv[4]);
} else {
  console.log("usage: node beads.mjs sheet <out.html> | vd <name> <out.xml>");
}
