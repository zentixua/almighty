"""The four marks, each drawn on a 64x64 grid. mark(name, tile=True) returns the store/mod-list icon
(with its tile background); tile=False returns the bare mark on transparency for logos and banners."""
from pix import Layer, hexc

INK = hexc('#0A0B10')


def tile(pal):
    L = Layer(64, 64, pal['bg'])
    return L


# ---------------------------------------------------------------- Regent: the crowned agent
REGENT = {
    'bg': hexc('#14161F'), 'bg2': hexc('#181B26'),
    's0': hexc('#262B38'), 's1': hexc('#3B4253'), 's2': hexc('#5B6479'), 's3': hexc('#8790A5'), 's4': hexc('#C3CAD8'),
    'g0': hexc('#6E3F12'), 'g1': hexc('#B7771F'), 'g2': hexc('#E9AE36'), 'g3': hexc('#FFE08A'),
    'e0': hexc('#0E7C73'), 'e1': hexc('#3FE0C8'), 'e2': hexc('#D8FFF8'),
    'v': hexc('#07080C'),
}


def regent(tile_on=True):
    p = REGENT
    m = Layer(64, 64)
    # Shoulders (a bust, so the head reads as a figure, not a box).
    m.poly([(6, 63), (12, 57), (51, 57), (57, 63)], p['s0'])
    m.rect(12, 57, 51, 57, p['s1'])
    m.rect(26, 54, 37, 56, p['v'])
    # Head: a squared helm, 32 wide.
    m.rect(16, 27, 47, 53, p['s2'])
    m.rect(16, 27, 47, 28, p['s3'])          # top light
    m.rect(16, 27, 17, 53, p['s3'])          # left light
    m.rect(16, 27, 16, 51, p['s4'])
    m.rect(17, 27, 45, 27, p['s4'])
    m.rect(45, 29, 47, 53, p['s1'])          # right shade
    m.rect(18, 52, 47, 53, p['s1'])          # bottom shade
    for c in [(16, 53), (47, 27), (47, 53), (16, 27)]:
        m.px(*c, (0, 0, 0, 0))               # clipped corners
    # Visor band.
    m.rect(19, 36, 44, 44, p['v'])
    m.rect(19, 45, 44, 45, p['s1'])
    # Eyes: two hard-edged lights, the only cool colour on the mark besides the jewel.
    for x in (22, 35):
        m.rect(x, 39, x + 6, 41, p['e1'])
        m.rect(x, 42, x + 6, 42, p['e0'])
        m.rect(x, 39, x + 1, 39, p['e2'])
    # Jaw vents.
    for x in (27, 31, 35):
        m.rect(x, 47, x + 1, 50, p['s1'])
    # Side plates.
    for x in (12, 48):
        m.rect(x, 35, x + 3, 46, p['s1'])
        m.rect(x + (1 if x == 12 else 1), 37, x + 2, 44, p['s2'])
    # Crown: five spikes widening one pixel per two rows (clean 1:2 flanks), lit from the left.
    band_top = 22
    for cx2, top in [(35, 12), (49, 10), (63, 6), (77, 10), (91, 12)]:   # centre x doubled
        for y in range(top, band_top):
            half = 1 + (y - top) // 2
            x0, x1 = (cx2 + 1) // 2 - half, cx2 // 2 + half
            x0, x1 = max(x0, 17), min(x1, 46)
            for x in range(x0, x1 + 1):
                c = p['g2'] if 2 * x < cx2 else p['g1']
                if x == x0 and x > 17:
                    c = p['g3']
                m.px(x, y, c)
        tx = (cx2 + 1) // 2 - 1
        m.rect(tx, top - 2, tx + 1, top - 1, p['g3'])
    m.rect(17, 12, 17, band_top - 1, p['g3'])
    m.rect(17, band_top, 46, 26, p['g1'])
    m.rect(17, band_top, 46, band_top, p['g2'])
    m.rect(17, band_top + 1, 17, 26, p['g3'])
    m.rect(17, 26, 46, 26, p['g0'])
    # A raised plate on the band carries the letters.
    m.rect(25, 18, 38, 26, p['g1'])
    m.rect(25, 18, 38, 18, p['g3'])
    m.rect(25, 26, 38, 26, p['g0'])
    m.rect(24, 18, 24, 26, INK)
    m.rect(39, 18, 39, 26, INK)
    ai_letters(m, 27, 19, p['e1'])
    # Studs.
    for x in (20, 42):
        m.rect(x, 23, x + 1, 24, p['g3'])
    m.outline(INK)
    if not tile_on:
        return m
    t = tile(p)
    t.paste(m, 0, 0)
    return t


MARKS = {'regent': regent}


def mark(name, tile_on=True):
    return MARKS[name](tile_on)


# ---------------------------------------------------------------- shared: isometric block
def iso_cube(m, cx, ty, k, s, top, left, right):
    """Pixel-exact 2:1 cube. cx is the seam between the two side faces; top rhombus is 4k wide, 2k tall;
    sides are s rows tall. top(r, x), left(u, v), right(u, v) return colours (u: 0 at the outer edge)."""
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(cx - hw, cx + hw):
            m.px(x, ty + r, top(r, x - cx))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(s):
            m.px(cx - 2 * k + u, y0 + v, left(u, v))
            m.px(cx + 2 * k - 1 - u, y0 + v, right(u, v))


GRASS = {
    't0': hexc('#2C5A24'), 't1': hexc('#3D7A2E'), 't2': hexc('#56A03C'), 't3': hexc('#7DC452'),
    'd0': hexc('#33201A'), 'd1': hexc('#563826'), 'd2': hexc('#764E33'), 'd3': hexc('#956843'),
}


def grass_block(m, cx, ty, k, s, dark=0):
    """Our own stylised turf block: flat top with a lit rim, a regular stepped fringe, a few large stones.
    Deliberately not Minecraft's texture: no per-pixel speckle."""
    g = GRASS

    def top(r, x):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        if r < k and (x == -hw or x == -hw + 1):
            return g['t3']                       # lit upper-left rim
        if r == 0:
            return g['t3']
        if r < k and (x == hw - 1 or x == hw - 2):
            return g['t2']
        # two quiet tufts, placed by hand relative to the block's size
        for (rr, xx) in [(k // 2 + 1, -k // 2 - 2), (k + 1, k // 2 + 1)]:
            if r == rr and xx <= x <= xx + 2:
                return g['t1']
        return g['t2']

    fringe = [3, 3, 2, 2, 4, 4, 3, 3, 2, 2, 3, 3, 4, 4, 2, 2]

    def side(lit):
        stones = {(2 * k // 3, s // 2): 1, (2 * k // 3 + 1, s // 2): 1, (k // 3, s - 4): 0, (k // 3 + 1, s - 4): 0,
                  (2 * k - 4, s - 3): 1}
        def f(u, v):
            fr = fringe[u % len(fringe)]
            if v < fr:
                return g['t2'] if lit else g['t1']
            if v == fr:
                return g['d0'] if lit else g['d0']
            if (u, v) in stones:
                return (g['d3'] if lit else g['d2']) if stones[(u, v)] else (g['d1'] if lit else g['d0'])
            return g['d2'] if lit else g['d1']
        return f

    iso_cube(m, cx, ty, k, s, top, side(True), side(False))


# ---------------------------------------------------------------- Grasp: the hand above the world
GRASP = {
    'bg': hexc('#12151C'), 'bg2': hexc('#161A23'),
    's0': hexc('#23283A'), 's1': hexc('#363D52'), 's2': hexc('#56607A'), 's3': hexc('#8892AB'), 's4': hexc('#C5CCDC'),
    'c0': hexc('#1B6F67'), 'c1': hexc('#43D9C2'), 'c2': hexc('#D8FFF8'),
}


def grasp(tile_on=True):
    p = GRASP
    m = Layer(64, 64)
    grass_block(m, 32, 37, 7, 12)
    tips = [21, 27, 33, 39]          # finger centre columns
    ends = [24, 26, 25, 22]          # last row of each finger
    # Control strings drop straight from the fingertips to lit anchors on the top face.
    anchors = [(21, 44), (27, 47), (33, 47), (39, 45)]
    for (x, e), (ax, ay) in zip(zip(tips, ends), anchors):
        m.line(x, e + 2, ax, ay - 1, p['c0'])
        m.px(ax, ay, p['c1'])
    # Cuff.
    m.rect(24, 1, 39, 5, p['s1'])
    m.rect(24, 1, 39, 1, p['s2'])
    m.rect(24, 1, 25, 5, p['s2'])
    m.rect(38, 2, 39, 5, p['s0'])
    # Back of the hand, slightly wider at the knuckles.
    m.rect(20, 6, 43, 15, p['s2'])
    m.rect(19, 9, 44, 15, p['s2'])
    m.rect(20, 6, 43, 6, p['s3'])
    m.rect(20, 6, 21, 15, p['s3'])
    m.rect(19, 9, 19, 15, p['s3'])
    m.rect(42, 7, 44, 15, p['s1'])
    # Back plate with the letters.
    m.rect(25, 7, 38, 15, p['s0'])
    m.rect(25, 7, 38, 7, INK)
    ai_letters(m, 27, 8, p['c1'])
    # Thumb: two segments angled out to the left.
    m.rect(15, 10, 19, 15, p['s2'])
    m.rect(15, 10, 19, 10, p['s3'])
    m.rect(15, 10, 15, 15, p['s3'])
    m.rect(14, 16, 18, 20, p['s2'])
    m.rect(14, 16, 14, 20, p['s3'])
    m.rect(15, 15, 18, 15, p['s0'])
    m.rect(18, 16, 18, 20, p['s1'])
    m.rect(15, 21, 17, 21, p['c1'])
    # Fingers: knuckle cap, two phalanges (the distal one narrower), rounded lit tip.
    for x, e in zip(tips, ends):
        x0 = x - 2
        m.rect(x0, 16, x0 + 4, 17, p['s3'])
        mid = 18 + (e - 18) // 2
        m.rect(x0, 18, x0 + 4, mid - 1, p['s2'])
        m.rect(x0 + 4, 18, x0 + 4, mid - 1, p['s1'])
        m.rect(x0, mid, x0 + 4, mid, p['s0'])
        m.rect(x0, mid + 1, x0 + 4, e, p['s2'])
        m.rect(x0 + 4, mid + 1, x0 + 4, e, p['s1'])
        m.rect(x0, mid + 1, x0, e, p['s3'])
        m.px(x0, e, (0, 0, 0, 0))
        m.px(x0 + 4, e, (0, 0, 0, 0))
        m.rect(x0 + 1, e + 1, x0 + 3, e + 1, p['c1'])
        m.px(x0 + 1, e + 1, p['c2'])
        m.px(x0 + 1, e, p['s2'])
    m.outline(INK)
    if not tile_on:
        return m
    t = tile(p)
    t.paste(m, 0, 0)
    return t


MARKS['grasp'] = grasp


# ---------------------------------------------------------------- Overseer: the eye above the pyramid of the world
OVERSEER = {
    'bg': hexc('#13141C'), 'bg2': hexc('#171923'),
    'r0': hexc('#2B2F3D'), 'r1': hexc('#434958'), 'r2': hexc('#626A7C'), 'r3': hexc('#8A93A5'),
    'g0': hexc('#6E3F12'), 'g1': hexc('#B7771F'), 'g2': hexc('#E9AE36'), 'g3': hexc('#FFE08A'),
    'w0': hexc('#9AA3B5'), 'w1': hexc('#DCE1EA'),
    'e0': hexc('#0E7C73'), 'e1': hexc('#3FE0C8'), 'e2': hexc('#D8FFF8'), 'v': hexc('#07080C'),
}


def overseer(tile_on=True):
    p = OVERSEER
    m = Layer(64, 64)
    # Stepped pyramid: three tiers of stone blocks, each with a lit tread.
    for (x0, x1, y0, y1) in [(5, 58, 50, 61), (13, 50, 40, 49), (21, 42, 31, 39)]:
        m.rect(x0, y0, x1, y1, p['r1'])
        m.rect(x0, y0, x1, y0 + 1, p['r3'])
        m.rect(x0, y0 + 2, x1, y0 + 2, p['r2'])
        m.rect(x1 - 1, y0 + 2, x1, y1, p['r0'])
        # block joints: one block = 8 px wide, staggered per course
        for i, yy in enumerate(range(y0 + 3, y1 + 1, 5)):
            off = 0 if i % 2 == 0 else 4
            m.rect(x0, min(yy + 4, y1), x1 - 2, min(yy + 4, y1), p['r0']) if yy + 4 <= y1 else None
            for xx in range(x0 + 6 + off, x1 - 2, 8):
                m.rect(xx, yy, xx, min(yy + 3, y1), p['r0'])
    # Floating capstone: a gold triangle with 45-degree flanks.
    for i in range(18):
        y = 8 + i
        m.rect(31 - i, y, 32 + i, y, p['g2'])
        m.px(31 - i, y, p['g3'])
        m.px(32 + i, y, p['g1'])
        if i > 0:
            m.px(31 - i + 1, y, p['g3'])
    m.rect(14, 25, 49, 26, p['g1'])
    m.rect(14, 26, 49, 26, p['g0'])
    # The eye.
    rows = {13: (28, 35), 14: (25, 38), 15: (23, 40), 16: (22, 41), 17: (21, 42), 18: (21, 42), 19: (22, 41),
            20: (23, 40), 21: (25, 38), 22: (28, 35)}
    for y, (a, b) in rows.items():
        m.rect(a - 1, y, b + 1, y, p['v'])
    for y, (a, b) in rows.items():
        if 14 <= y <= 21:
            m.rect(a + 1, y, b - 1, y, p['w1'])
    m.rect(24, 20, 39, 20, p['w0'])
    m.rect(26, 21, 37, 21, p['w0'])
    # Iris and pupil.
    iris = {13: (29, 34), 14: (28, 35), 15: (27, 36), 16: (27, 36), 17: (27, 36), 18: (27, 36), 19: (27, 36),
            20: (28, 35), 21: (29, 34)}
    for y, (a, b) in iris.items():
        m.rect(a, y, b, y, p['e1'])
    m.rect(29, 19, 34, 21, p['e0'])
    m.rect(30, 15, 33, 18, p['v'])
    m.rect(29, 16, 34, 17, p['v'])
    m.rect(30, 15, 30, 15, p['e2'])
    m.px(28, 14, p['e2'])
    m.outline(INK)
    if not tile_on:
        return m
    t = tile(p)
    t.paste(m, 0, 0)
    return t


MARKS['overseer'] = overseer


# ---------------------------------------------------------------- Prompt: the command line that edits the world
PROMPT = {
    'bg': hexc('#121419'), 'bg2': hexc('#161920'),
    'f0': hexc('#23283A'), 'f1': hexc('#363D52'), 'f2': hexc('#56607A'), 'f3': hexc('#8892AB'),
    'scr': hexc('#0B0D12'), 'scr2': hexc('#10131A'),
    'c0': hexc('#1B6F67'), 'c1': hexc('#43D9C2'), 'c2': hexc('#D8FFF8'),
    'g1': hexc('#B7771F'), 'g2': hexc('#E9AE36'), 'g3': hexc('#FFE08A'),
}


def prompt(tile_on=True):
    p = PROMPT
    m = Layer(64, 64)
    # Console frame with a title bar.
    m.rect(4, 8, 59, 57, p['f1'])
    m.rect(4, 8, 59, 13, p['f2'])
    m.rect(4, 8, 59, 8, p['f3'])
    m.rect(4, 9, 4, 57, p['f2'])
    m.rect(59, 9, 59, 57, p['f0'])
    m.rect(5, 57, 59, 57, p['f0'])
    for i, c in enumerate(['g2', 'f3', 'f3']):
        m.rect(8 + i * 4, 10, 9 + i * 4, 11, p[c])
    m.rect(7, 16, 56, 54, p['scr'])
    for y in range(17, 54, 3):
        m.rect(7, y, 56, y, p['scr2'])
    # Prompt line: '>' then the letters AI, then the cursor.
    for i in range(5):
        for t in range(3):
            m.px(11 + i + t, 20 + i, p['c1'])
            m.px(11 + i + t, 28 - i, p['c1'])
    ai_letters(m, 22, 20, p['c1'], big=True)
    m.rect(38, 27, 45, 28, p['g2'])
    m.rect(38, 27, 45, 27, p['g3'])
    # Output: the command lands on the world as a bolt.
    grass_block(m, 32, 38, 5, 6)
    bolt = [
        ".......3333",
        "......3gggg",
        ".....3gggg.",
        "....3gggg..",
        "...3gggggg.",
        "......ggg..",
        ".....ggg...",
        "....gg1....",
        "...g1......",
        "..1........",
    ]
    m.sprite(bolt, 31, 29, {'g': p['g2'], '3': p['g3'], '1': p['g1']})
    m.outline(INK)
    if not tile_on:
        return m
    t = tile(p)
    t.paste(m, 0, 0)
    return t


# ---------------------------------------------------------------- Sovereign: the agent's crown over the world
SOVEREIGN = {
    'bg': hexc('#13151D'), 'bg2': hexc('#171A24'),
    'g0': hexc('#6E3F12'), 'g1': hexc('#B7771F'), 'g2': hexc('#E9AE36'), 'g3': hexc('#FFE08A'),
    'e0': hexc('#0E7C73'), 'e1': hexc('#3FE0C8'), 'e2': hexc('#D8FFF8'),
}


def sovereign(tile_on=True):
    p = SOVEREIGN
    m = Layer(64, 64)
    grass_block(m, 32, 29, 8, 16)
    # The crown sits on the world: its band rests on the centre of the top face.
    band_top, band_bot = 22, 33
    for cx2, top in [(31, 11), (47, 8), (63, 4), (79, 8), (95, 11)]:
        for y in range(top, band_top):
            half = 1 + (y - top) // 2
            x0, x1 = max((cx2 + 1) // 2 - half, 15), min(cx2 // 2 + half, 48)
            for x in range(x0, x1 + 1):
                c = p['g2'] if 2 * x < cx2 else p['g1']
                if x == x0 and x > 15:
                    c = p['g3']
                m.px(x, y, c)
        tx = (cx2 + 1) // 2 - 1
        m.rect(tx, top - 2, tx + 1, top - 1, p['g3'])
    m.rect(15, 11, 15, band_top - 1, p['g3'])
    # Band with a curved lower edge (it wraps around), lit left, shaded right.
    m.rect(15, band_top, 48, band_bot - 2, p['g2'])
    m.rect(18, band_bot - 1, 45, band_bot - 1, p['g2'])
    m.rect(22, band_bot, 41, band_bot, p['g1'])
    m.rect(15, band_top, 48, band_top, p['g3'])
    m.rect(15, band_top + 1, 16, band_bot - 2, p['g3'])
    m.rect(44, band_top + 1, 48, band_bot - 2, p['g1'])
    m.rect(46, band_top + 1, 48, band_bot - 2, p['g0'])
    m.rect(15, band_bot - 2, 17, band_bot - 2, p['g1'])
    m.rect(18, band_bot - 1, 21, band_bot - 1, p['g1'])
    m.rect(42, band_bot - 1, 45, band_bot - 1, p['g0'])
    ai_letters(m, 27, band_top + 3, p['g3'])     # engraved: lit lower lip, dark cut above it
    ai_letters(m, 27, band_top + 2, p['g0'])
    for x in (20, 42):
        m.rect(x, band_top + 4, x + 1, band_top + 6, p['g3'])
        m.rect(x + 1, band_top + 5, x + 1, band_top + 6, p['g1'])
    m.outline(INK)
    if not tile_on:
        return m
    t = tile(p)
    t.paste(m, 0, 0)
    return t


MARKS['sovereign'] = sovereign
MARKS['prompt'] = prompt


# ---------------------------------------------------------------- shared: the agent spark (four-point star)
SPARK13 = [
    "......h......",
    "......h......",
    "......h......",
    ".....hmm.....",
    "....hhmmm....",
    "..hhhhmmmmm..",
    "hhhhhhhmmmmmm",
    "..mmmmmmmll..",
    "....mmmll....",
    ".....mml.....",
    "......l......",
    "......l......",
    "......l......",
]
SPARK9 = [
    "....h....",
    "....h....",
    "...hmm...",
    "..hhmmm..",
    "hhhhmmmmm",
    "..mmmml..",
    "...mml...",
    "....l....",
    "....l....",
]


def spark(m, cx, cy, big, hi, mid, lo):
    rows = SPARK13 if big else SPARK9
    r = len(rows) // 2
    m.sprite(rows, cx - r, cy - r, {'h': hi, 'm': mid, 'l': lo})


# ---------------------------------------------------------------- shared: the letters AI, 7 rows, 2-px stems
AI7 = [
    ".####...##",
    "##..##..##",
    "##..##..##",
    "######..##",
    "##..##..##",
    "##..##..##",
    "##..##..##",
]
AI9 = [
    "..####....###",
    ".######...###",
    "###..###..###",
    "###..###..###",
    "########..###",
    "########..###",
    "###..###..###",
    "###..###..###",
    "###..###..###",
]


def ai_letters(m, x, y, c, shade=None, big=False):
    rows = AI9 if big else AI7
    m.sprite(rows, x, y, {'#': c})
    if shade:
        for yy, row in enumerate(rows):
            for xx, ch in enumerate(row):
                if ch == '#' and (yy + 1 >= len(rows) or row[xx] != rows[yy + 1][xx] if xx < len(rows[yy + 1]) else True):
                    m.px(x + xx, y + yy, shade)
