"""Round-two marks, drawn on a 64x64 grid with the art.py ramps.

island(): our own world element, a floating chunk of land with cut strata and a tapering rock underside.
hand(): the puppeteer hand. core(): the keyed core.
"""
from pix import Layer, hexc, T
from art import c, mix, finish, INK, R

TILE = c('night', 1)


# ------------------------------------------------------------------ the floating island chunk
def island(m, cx, ty, k, sb=7, taper=3, tree=True, roots=True):
    """Top: a 4k x 2k rhombus of turf. Sides: turf lip, soil, stone, then the underside tapers to a keel."""
    tufts = {(k // 2 + 1, -k + 2), (k // 2 + 1, -k + 3), (k + 2, 3), (k + 2, 4), (k + 3, 4), (k - 2, k + 1), (k - 2, k + 2)}
    lights = {(2, -1), (2, 0), (k // 2, 2), (k // 2, 3), (k + 1, -4), (k + 1, -3)}
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            col = c('moss', 3)
            if r == 0 or (r < k and x <= -hw + 1):
                col = c('moss', 4)                     # lit back-left rim
            elif r >= k and x >= hw - 2:
                col = c('moss', 2)                     # front-right edge turns away from the light
            elif (r, x) in tufts:
                col = c('moss', 2)
            elif (r, x) in lights:
                col = c('moss', 4)
            m.px(cx + x, ty + r, col)
    lip = [2, 2, 3, 3, 2, 2, 2, 3, 4, 4, 3, 2, 2, 2, 3, 3, 2, 2, 3, 2]
    stones = {(4, sb - 1): 1, (5, sb - 1): 1, (2 * k - 5, sb + 1): 1, (2 * k - 4, sb + 1): 1, (k, sb - 2): -1,
              (k + 1, sb - 2): -1}
    keel = 0
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for side in (0, 1):                            # 0 = left (lit), 1 = right (shade)
            x = cx - 2 * k + u if side == 0 else cx + 2 * k - 1 - u
            sh = 0 if side == 0 else 1
            v = 0
            while True:
                if v >= sb and u < (v - sb + 1) * taper:
                    break
                lp = lip[u % len(lip)]
                soil_end = lp + 3
                if v < lp:
                    col = c('moss', 3 - sh)
                elif v == lp:
                    col = c('moss', 1)                 # coloured inner line under the turf
                elif v < soil_end:
                    col = c('soil', 3 - sh)
                elif v == soil_end:
                    col = c('soil', 1)
                else:
                    depth = 1 if v > sb + 2 else 0
                    col = c('stone', 3 - sh - depth)
                    s = stones.get((u, v))
                    if s:
                        col = c('stone', max(0, min(4, 3 - sh - depth + s)))
                if side == 1 and u == 2 * k - 1:
                    col = mix(col, c('stone', 0), 0.35)  # seam between faces: darker step, not ink
                m.px(x, y0 + v, col)
                keel = max(keel, y0 + v)
                v += 1
    if roots:
        for (x, y, n) in [(cx - 4, keel - 3, 4), (cx + 3, keel - 2, 5), (cx - 9, keel - 7, 3)]:
            for i in range(n):
                m.px(x, y + i, c('soil', 2) if i < n - 1 else c('moss', 2))
    if tree:
        tx, tyy = cx - k // 2 - 2, ty + k - 4           # stands on the back-left of the turf
        m.rect(tx, tyy, tx, tyy + 2, c('soil', 1))
        rows = [(0, 0), (-1, 1), (-1, 1), (-2, 2), (-2, 2), (-3, 3)]
        for i, (a, b) in enumerate(rows):
            for xx in range(a, b + 1):
                col = c('moss', 2) if xx > 0 else c('moss', 3)
                if xx == a and i > 0:
                    col = c('moss', 4) if i % 2 else c('moss', 3)
                m.px(tx + xx, tyy - 6 + i, col)
        m.px(tx, tyy - 7, c('moss', 4))
    return keel


# ------------------------------------------------------------------ the puppeteer hand
def hand(m, ox=0, oy=0, glyph=None):
    """Steel gauntlet, palm down, fingers hanging toward the world. Returns fingertip (x, y) anchors."""
    s = lambda i: c('steel', i)
    X = lambda x: x + ox
    Y = lambda y: y + oy
    # Cuff with a status light.
    m.rect(X(24), Y(1), X(39), Y(5), s(2))
    m.rect(X(24), Y(1), X(39), Y(1), s(4))
    m.rect(X(24), Y(2), X(25), Y(5), s(3))
    m.rect(X(37), Y(2), X(39), Y(5), s(1))
    m.rect(X(26), Y(4), X(36), Y(4), s(1))
    m.rect(X(34), Y(2), X(35), Y(2), c('cyan', 3))
    # Back of the hand: widening to the knuckles.
    for y in range(6, 16):
        a, b = (21, 42) if y < 9 else (19, 44)
        m.rect(X(a), Y(y), X(b), Y(y), s(3))
        m.px(X(a), Y(y), s(4))
        m.rect(X(b - 2), Y(y), X(b), Y(y), s(2))
    m.rect(X(21), Y(6), X(42), Y(6), s(4))
    m.rect(X(19), Y(9), X(20), Y(9), s(4))
    m.rect(X(43), Y(9), X(44), Y(9), s(2))
    # Raised back plate (inner lines in the steel's dark step, not ink).
    m.rect(X(25), Y(7), X(38), Y(14), s(1))
    m.rect(X(26), Y(8), X(37), Y(13), s(3))
    m.rect(X(26), Y(8), X(37), Y(8), s(4))
    m.rect(X(26), Y(8), X(26), Y(13), s(4))
    m.rect(X(37), Y(9), X(37), Y(13), s(2))
    m.rect(X(27), Y(13), X(37), Y(13), s(2))
    if glyph:
        glyph(m, X(27), Y(9))
    # Thumb.
    m.rect(X(15), Y(10), X(19), Y(15), s(3))
    m.rect(X(15), Y(10), X(19), Y(10), s(4))
    m.rect(X(15), Y(11), X(15), Y(15), s(4))
    m.rect(X(15), Y(15), X(19), Y(15), s(1))
    m.rect(X(14), Y(16), X(18), Y(20), s(3))
    m.rect(X(14), Y(16), X(14), Y(20), s(4))
    m.rect(X(18), Y(16), X(18), Y(20), s(2))
    m.px(X(14), Y(20), T)
    m.px(X(18), Y(20), T)
    m.rect(X(15), Y(21), X(17), Y(21), c('cyan', 2))
    # Fingers.
    tips = []
    for x0, e in [(19, 23), (25, 25), (31, 24), (37, 21)]:
        m.rect(X(x0), Y(16), X(x0 + 4), Y(17), s(4))        # knuckle cap
        m.px(X(x0 + 4), Y(17), s(3))
        mid = 18 + (e - 18) // 2
        m.rect(X(x0), Y(18), X(x0 + 4), Y(mid - 1), s(3))
        m.rect(X(x0 + 3), Y(18), X(x0 + 4), Y(mid - 1), s(2))
        m.rect(X(x0), Y(mid), X(x0 + 4), Y(mid), s(1))      # joint
        m.rect(X(x0), Y(mid + 1), X(x0 + 4), Y(e), s(3))
        m.rect(X(x0 + 3), Y(mid + 1), X(x0 + 4), Y(e), s(2))
        m.px(X(x0), Y(mid + 1), s(4))
        m.px(X(x0), Y(e), T)
        m.px(X(x0 + 4), Y(e), T)
        m.rect(X(x0 + 1), Y(e + 1), X(x0 + 3), Y(e + 1), c('cyan', 2))
        m.px(X(x0 + 1), Y(e + 1), c('cyan', 4))
        tips.append((X(x0 + 2), Y(e + 2)))
    m.rect(X(19), Y(15), X(44), Y(15), s(2))              # shadow line under the back plate
    return tips


def strings(m, tips, targets):
    for (x, y), (tx, ty) in zip(tips, targets):
        for yy in range(y, ty):
            m.px(x, yy, c('cyan', 1) if (yy - y) % 4 == 3 else c('cyan', 2))
        m.px(x, ty, c('cyan', 4))
        m.px(x - 1, ty, c('cyan', 3))
        m.px(x + 1, ty, c('cyan', 3))


def puppeteer(tile=True, glyph=None):
    m = Layer(64, 64)
    island(m, 32, 33, 7, sb=9, taper=2, tree=False)
    tips = hand(m, 0, 0, glyph)
    targets = [(21, 41), (27, 44), (33, 44), (39, 42)]
    strings(m, tips, targets)
    return finish(m, TILE if tile else None)


# ------------------------------------------------------------------ the keyed core
def core(tile=True, _raw=False):
    """A command core: a heavy steel cube with a live agent light inside, locked by the owner's gold key."""
    m = Layer(64, 64)
    cx, ty, k, sd = 30, 10, 10, 20
    s = lambda i: c('steel', i)
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            col = s(3)
            if r < 2 or (r < k and x <= -hw + 1):
                col = s(4)
            if r >= k and x >= hw - 2:
                col = s(2)
            m.px(cx + x, ty + r, col)
    # Top inset: a recessed square (inner lines) with a cyan seam.
    for r in range(5, 2 * k - 5):
        rr = r - 5
        kk = k - 5
        hw = 2 * (rr + 1) if rr < kk else 2 * (2 * kk - rr)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, s(2) if r > k - 1 else s(1))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(sd):
            xl, xr = cx - 2 * k + u, cx + 2 * k - 1 - u
            edge = u < 2 or v < 1 or v >= sd - 2
            m.px(xl, y0 + v, s(3) if edge else s(2))
            m.px(xr, y0 + v, s(2) if edge else s(1))
            if u == 2 * k - 1:
                m.px(xr, y0 + v, s(1))
    # Left face: the agent inside, its monogram lit in a recessed window.
    for u in range(4, 17):
        y0 = ty + k + u // 2 + 1
        for v in range(4, 15):
            m.px(cx - 2 * k + u, y0 + v, c('cyan', 0))
        m.px(cx - 2 * k + u, y0 + 3, s(1))
        m.px(cx - 2 * k + u, y0 + 15, s(3))
    for v in range(4, 15):
        m.px(cx - 2 * k + 3, ty + k + 1 + 1 + v, s(1))
        m.px(cx - 2 * k + 17, ty + k + 8 + 1 + v, s(3))
    monogram(m, cx - 2 * k + 6, ty + k + 3 + 1 + 4, skew=True)
    # Right face: keyhole plate and the owner's key, turned in the lock.
    for u in range(8, 13):
        y0 = ty + k + u // 2 + 1
        for v in range(6, 13):
            m.px(cx + 2 * k - 1 - u, y0 + v, c('gold', 1 if v == 12 or u == 8 else (4 if v == 6 else 3)))
    kx, ky = cx + 2 * k - 1 - 10, ty + k + 5 + 8
    m.rect(kx, ky - 1, kx, ky + 2, c('gold', 0))
    # Key shaft leaving the face toward the viewer's right, bow outside the cube.
    m.rect(kx + 1, ky, kx + 12, ky + 1, c('gold', 3))
    m.rect(kx + 1, ky + 1, kx + 12, ky + 1, c('gold', 2))
    for (dx, dy) in [(0, -3), (1, -4), (2, -4), (3, -4), (4, -3), (5, -2), (5, -1), (5, 0), (5, 1), (5, 2),
                     (4, 3), (3, 4), (2, 4), (1, 4), (0, 3), (-1, -2), (-1, 2)]:
        pass
    bx, by = kx + 13, ky - 4
    bow = [
        "..3333..",
        ".344443.",
        "34.....3",
        "34.....2",
        "34.....2",
        "34.....2",
        ".322222.",
        "..1111..",
    ]
    bow = [
        "..4433..",
        ".43..32.",
        "43....21",
        "43....21",
        "43....21",
        "43....21",
        ".32..21.",
        "..2111..",
    ]
    m.sprite(bow, bx, by - 1, {str(i): c('gold', i) for i in range(5)})
    m.rect(kx + 3, ky + 2, kx + 3, ky + 3, c('gold', 2))    # key bit
    m.rect(kx + 5, ky + 2, kx + 5, ky + 4, c('gold', 2))
    return finish(m, TILE if tile else None)


MARKS2 = {'puppeteer': puppeteer, 'core': core}


# ------------------------------------------------------------------ the agent monogram: Λ and I, one glyph
MONO = [
    "...4.....4",
    "..434....3",
    "..4.3....3",
    ".43.33...3",
    ".4...3...3",
    "43...33..3",
    "4.....3..2",
]


def monogram(m, x, y, skew=False):
    """Cyan ΛI. With skew=True each column drops one pixel every two columns (to sit on a 2:1 iso face)."""
    for gy, row in enumerate(MONO):
        for gx, ch in enumerate(row):
            if ch == '.':
                continue
            m.px(x + gx, y + gy + (gx // 2 if skew else 0), c('cyan', int(ch)))


def hand2(m, ox=0, oy=0, emblem=True):
    """Palm-down gauntlet seen from above-front: short back, long spread fingers, thumb out to the left."""
    s = lambda i: c('steel', i)
    P = lambda x, y, col: m.px(x + ox, y + oy, col)

    def row(y, a, b, col):
        for x in range(a, b + 1):
            P(x, y, col)
    # Wrist and cuff.
    for y in range(0, 5):
        row(y, 26, 37, s(2))
        P(26, y, s(3)); P(27, y, s(3)); P(37, y, s(1))
    row(0, 26, 37, s(3))
    row(3, 26, 37, s(1))
    P(34, 1, c('cyan', 3)); P(35, 1, c('cyan', 3))
    # Back of the hand: widens from 14 to 24 px over 8 rows.
    back = {5: (24, 39), 6: (23, 40), 7: (22, 41), 8: (21, 42), 9: (21, 43), 10: (20, 43), 11: (20, 43), 12: (20, 43)}
    for y, (a, b) in back.items():
        row(y, a, b, s(3))
        P(a, y, s(4)); P(a + 1, y, s(4) if y < 8 else s(3))
        P(b, y, s(1)); P(b - 1, y, s(2))
    row(5, 24, 39, s(4))
    # Emblem: an inlaid disc on the back, carrying the monogram.
    if emblem:
        disc = {6: (28, 35), 7: (27, 36), 8: (26, 37), 9: (26, 37), 10: (26, 37), 11: (27, 36), 12: (28, 35)}
        for y, (a, b) in disc.items():
            row(y, a, b, c('cyan', 0))
        for y, (a, b) in disc.items():
            P(a - 1, y, s(1)); P(b + 1, y, s(4) if y > 9 else s(2))
        row(5, 28, 35, s(1)); row(13, 28, 35, s(4))
    # Thumb: angled out and down to the left, two segments.
    for i, y in enumerate(range(8, 19)):
        a = 18 - i // 2
        P(a, y, s(4)); P(a + 1, y, s(3)); P(a + 2, y, s(3)); P(a + 3, y, s(2))
        if y == 13:
            row(y, a, a + 3, s(1))
    P(13, 19, c('cyan', 3)); P(14, 19, c('cyan', 2))
    # Fingers: 4 px wide, three segments, spread outward a little.
    tips = []
    for x0, L, drift in [(20, 15, -1), (26, 17, 0), (32, 16, 0), (38, 13, 1)]:
        for i in range(L):
            y = 13 + i
            sh = (i * drift) // 7
            a = x0 + sh
            cols = [s(4), s(3), s(3), s(2)]
            if i < 2:
                cols = [s(4), s(4), s(4), s(3)]       # knuckle cap
            if i in (L // 3 + 1, 2 * L // 3 + 1):
                cols = [s(1)] * 4                       # joints: inner lines
            if i == L - 1:
                cols = [None, s(3), s(2), None]         # rounded tip
            for j, col in enumerate(cols):
                if col is not None:
                    P(a + j, y, col)
        a = x0 + ((L - 1) * drift) // 7
        P(a + 1, 13 + L, c('cyan', 4)); P(a + 2, 13 + L, c('cyan', 2))
        tips.append((a + 1 + ox, 13 + L + 1 + oy))
    return tips


def puppeteer2(tile=True, emblem=True):
    m = Layer(64, 64)
    island(m, 32, 36, 7, sb=8, taper=2, tree=False)
    tips = hand2(m, 0, 0, emblem)
    if emblem:
        monogram(m, 27, 6)
    targets = [(tips[0][0], 44), (tips[1][0], 47), (tips[2][0], 47), (tips[3][0], 45)]
    strings(m, tips, targets)
    return finish(m, TILE if tile else None)


def core2(tile=True):
    """The keyed core with the monogram as its inner light instead of a visor."""
    m = core(False)
    return m


MARKS2['puppeteer2'] = puppeteer2


# ------------------------------------------------------------------ quick sketches (round two, Artem's shortlist)
def cube(m, cx, ty, k, s, mat, lit=0):
    """Plain iso cube in one ramp: top light, left mid, right dark, lit rim on the back-left edges."""
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c(mat, min(4, 3 + lit) if (r == 0 or x <= -hw + 1) and r < k else 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(s):
            m.px(cx - 2 * k + u, y0 + v, c(mat, 2))
            m.px(cx + 2 * k - 1 - u, y0 + v, c(mat, 1))


def crown_sketch(tile=True):
    m = Layer(64, 64)
    island(m, 32, 34, 8, sb=8, taper=2, tree=False)
    g = lambda i: c('gold', i)
    for cx2, top in [(37, 9), (49, 6), (63, 3), (77, 6), (89, 9)]:
        for y in range(top, 21):
            half = 1 + (y - top) // 2
            x0, x1 = max((cx2 + 1) // 2 - half, 18), min(cx2 // 2 + half, 45)
            for x in range(x0, x1 + 1):
                m.px(x, y, g(3) if 2 * x < cx2 else g(2))
            m.px(x0, y, g(4))
        m.px((cx2 + 1) // 2 - 1, top - 1, g(4)); m.px((cx2 + 1) // 2, top - 1, g(3))
    m.rect(18, 21, 45, 27, g(3)); m.rect(18, 21, 45, 21, g(4)); m.rect(18, 27, 45, 27, g(1))
    m.rect(38, 22, 45, 26, g(2)); m.rect(43, 22, 45, 26, g(1))
    m.rect(27, 22, 36, 26, c('cyan', 0))
    monogram(m, 27, 21)
    m.rect(18, 28, 45, 28, c('gold', 0))        # crown's shadow line reads as hovering
    return finish(m, TILE if tile else None)


def avatar_sketch(tile=True):
    """The agent's body: a blocky figure with a lit visor, arms raised, standing on the world."""
    m = Layer(64, 64)
    island(m, 32, 40, 7, sb=7, taper=2, tree=False)
    s = lambda i: c('steel', i)
    # head
    m.rect(26, 6, 37, 17, s(3)); m.rect(26, 6, 37, 6, s(4)); m.rect(26, 6, 26, 17, s(4)); m.rect(36, 7, 37, 17, s(2))
    m.rect(27, 10, 36, 13, c('cyan', 0)); m.rect(28, 11, 30, 12, c('cyan', 3)); m.rect(33, 11, 35, 12, c('cyan', 3))
    m.px(28, 11, c('cyan', 4)); m.px(33, 11, c('cyan', 4))
    # body
    m.rect(27, 18, 36, 31, c('night', 4)); m.rect(27, 18, 28, 31, s(2)); m.rect(35, 18, 36, 31, c('night', 3))
    m.rect(30, 20, 33, 23, c('cyan', 2)); m.px(30, 20, c('cyan', 4))
    # arms raised
    for i in range(10):
        m.rect(23 - i // 2, 18 - i, 25 - i // 2, 18 - i, s(3)); m.px(23 - i // 2, 18 - i, s(4))
        m.rect(38 + i // 2, 18 - i, 40 + i // 2, 18 - i, s(2))
    m.rect(17, 5, 20, 8, s(4)); m.rect(43, 5, 46, 8, s(3))
    # legs
    m.rect(28, 32, 31, 42, s(2)); m.rect(32, 32, 35, 42, s(1)); m.rect(28, 32, 28, 42, s(3))
    # power: two short cyan arcs from the hands
    for (x, y) in [(18, 3), (19, 2), (16, 4), (44, 3), (45, 2), (47, 4)]:
        m.px(x, y, c('cyan', 3))
    return finish(m, TILE if tile else None)


def block_a_sketch(tile=True):
    """The letter A built from six blocks: the agent's mark is made of the world; the apex block is lit."""
    m = Layer(64, 64)
    k, s = 4, 8
    order = [(14, 44, 'stone'), (50, 44, 'stone'), (20, 32, 'stone'), (44, 32, 'stone'), (32, 36, 'gold'),
             (26, 20, 'stone'), (38, 20, 'stone'), (32, 8, 'cyan')]
    for cx, ty, mat in sorted(order, key=lambda t: t[1]):
        cube(m, cx, ty, k, s, mat)
    return finish(m, TILE if tile else None)


def halo_sketch(tile=True):
    """Power from above: a gold halo over the world and a single bolt of the agent's light striking it."""
    m = Layer(64, 64)
    island(m, 32, 34, 8, sb=8, taper=2, tree=True)
    g = lambda i: c('gold', i)
    # halo: an ellipse ring, 30 x 8
    ring = {2: (24, 39), 3: (20, 43), 4: (18, 45), 5: (17, 46), 6: (17, 46), 7: (18, 45), 8: (20, 43), 9: (24, 39)}
    inner = {4: (24, 39), 5: (21, 42), 6: (21, 42), 7: (24, 39)}
    for y, (a, b) in ring.items():
        m.rect(a, y, b, y, g(3) if y < 6 else g(2))
    for y, (a, b) in inner.items():
        m.rect(a, y, b, y, (0, 0, 0, 0))
    m.rect(24, 2, 39, 2, g(4)); m.rect(20, 3, 26, 3, g(4))
    bolt = [
        "...4433",
        "..4332.",
        ".4332..",
        "4333332",
        "...332.",
        "..332..",
        ".332...",
        ".32....",
        "32.....",
        "2......",
    ]
    m.sprite(bolt, 29, 12, {str(i): c('cyan', i) for i in range(5)})
    m.sprite(["2", "3", "4"], 29, 22, {str(i): c('cyan', i) for i in range(5)})
    return finish(m, TILE if tile else None)


SKETCHES = [
    ('puppeteer2', 'Puppeteer: a steel hand with the agent monogram works a floating island on strings - the agent runs the world, wired, not loose.'),
    ('core', 'Keyed core: a command core with the agent lit inside, locked by the owner\'s gold key - power that only turns when the owner allows.'),
    ('crown', 'Sovereign: a crown carrying the agent monogram hovers over the island - the agent rules the world.'),
    ('avatar', 'Avatar: the agent as a real body, a blocky figure with a lit visor standing on the world, arms raised in power.'),
    ('block_a', 'Block A: the letter A built from the world\'s own blocks, the apex block lit with the agent\'s light.'),
    ('halo', 'Halo strike: a gold halo over the island and one bolt of the agent\'s light striking it - power from above.'),
]
MARKS2.update({'crown': crown_sketch, 'avatar': avatar_sketch, 'block_a': block_a_sketch, 'halo': halo_sketch})
