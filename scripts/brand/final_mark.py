"""The final Almighty mark: a haloed cloud with the agent's visor works a floating island on strings.

Drawn on a 64x64 grid (Python + Pillow, pixel by pixel), enlarged only by whole nearest-neighbour factors.
Every shape is symmetric about the centre seam x = 32 (between pixel columns 31 and 32) unless lighting says
otherwise: light comes from the upper left, so left faces are one ramp step lighter than right faces.
"""
from pix import Layer, hexc, T
from art import R, c, mix, INK, outline_outer, aa_corners
import clouds32  # registers the cloud ramp

# ---------------------------------------------------------------- palette (hue-shifted ramps, dark -> light)
PAL = {
    'ink':   hexc('#0E0B16'),
    'sky':   [hexc(h) for h in ('#1A2F5E', '#203A70', '#264682', '#2E5394', '#3A62A6')],
    'cloud': [hexc(h) for h in ('#4B4E7A', '#7B80A8', '#B6BCD6', '#E4E9F4', '#FFFFFF')],
    'gold':  [hexc(h) for h in ('#4A1E2C', '#8C3B2E', '#C9692C', '#EDA640', '#FFE69C')],
    'cyan':  [hexc(h) for h in ('#123349', '#1C6676', '#2FB0A6', '#7BEBCF', '#E2FFF3')],
    'visor': [hexc(h) for h in ('#120F1F', '#1F1A33')],
    'moss':  [hexc(h) for h in ('#163238', '#235A45', '#3F8A45', '#76B84A', '#C6E46E')],
    'soil':  [hexc(h) for h in ('#2A1828', '#4A2A33', '#704338', '#9A6640', '#C3925C')],
    'stone': [hexc(h) for h in ('#1E1B2D', '#343049', '#4F4A65', '#736C86', '#A39BAF')],
}
P = lambda k, i: PAL[k][i]


def outline_outside(m, ink):
    """Ink on transparent pixels that touch the shape AND are reachable from the canvas edge, so holes
    (the halo's opening) stay open instead of filling with outline."""
    w, h = m.w, m.h
    outside = set()
    stack = [(x, y) for x in range(w) for y in (0, h - 1)] + [(x, y) for y in range(h) for x in (0, w - 1)]
    while stack:
        x, y = stack.pop()
        if (x, y) in outside or not (0 <= x < w and 0 <= y < h) or m.get(x, y)[3]:
            continue
        outside.add((x, y))
        stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
    src = m.im.copy()
    for (x, y) in outside:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            xx, yy = x + dx, y + dy
            if 0 <= xx < w and 0 <= yy < h and src.getpixel((xx, yy))[3]:
                m.px(x, y, ink)
                break
    # inner edge of a hole: a one-step darker ring colour, not ink
    return m


def step_down(col):
    """The next darker colour of the ramp this colour belongs to (keeps the palette closed; no blends)."""
    for k, ramp in PAL.items():
        if isinstance(ramp, list) and col[:3] in [c_[:3] for c_ in ramp]:
            i = [c_[:3] for c_ in ramp].index(col[:3])
            return ramp[max(0, i - 1)]
    return col


def sym(m, x, y, col, cx2=63):
    """Plot a pixel and its mirror about the vertical centre (x and cx2 - x)."""
    m.px(x, y, col); m.px(cx2 - x, y, col)


# ---------------------------------------------------------------- cloud
CLOUD_ROWS = {   # half-widths measured from the centre seam, per row: a puffed, stepped silhouette
    # y: (inner edge for the top puffs) -> we build from explicit spans instead
}


def cloud_shape():
    """The cloud as a set of cells: a broad centre puff, two side puffs, a body with stepped corners; symmetric."""
    cells = set()

    def span(y, x0, x1):
        for x in range(x0, x1 + 1):
            cells.add((x, y)); cells.add((63 - x, y))
    for y, x0 in [(11, 25), (12, 23), (13, 22), (14, 22)]:   # centre puff
        span(y, x0, 31)
    for y, a, b in [(13, 14, 19), (14, 12, 21)]:          # side puffs
        span(y, a, b)
    for y, x0 in [(15, 11), (16, 10), (17, 10), (18, 10), (19, 10), (20, 10), (21, 11), (22, 13)]:
        span(y, x0, 31)
    return cells


def draw_cloud(m):
    cells = cloud_shape()
    for (x, y) in cells:
        up, down = (x, y - 1) not in cells, (x, y + 1) not in cells
        down2 = (x, y + 2) not in cells
        col = P('cloud', 3)
        if up:
            col = P('cloud', 4)
        elif down:
            col = P('cloud', 1)
        elif down2:
            col = P('cloud', 2)
        m.px(x, y, col)
    # puff seams: a short shade line where a side puff meets the centre puff (gives volume)
    for x in (20, 21):
        sym(m, x, 14, P('cloud', 2))                  # where a side puff tucks under the centre puff
    for (x, y) in [(25, 11), (26, 11), (14, 13), (15, 13), (10, 16)]:
        m.px(x, y, P('cloud', 4))


def draw_visor(m):
    """A small crisp visor: 14 x 5, two 3 x 2 eyes."""
    m.rect(25, 16, 38, 20, P('visor', 0))
    m.rect(25, 16, 38, 16, P('visor', 1))
    for x0 in (27, 34):
        m.rect(x0, 18, x0 + 2, 18, P('cyan', 4))
        m.rect(x0, 19, x0 + 2, 19, P('cyan', 3))


def draw_halo(m):
    """A crisp halo: an open ellipse 26 x 7, gold lit on the far (top) arc, shaded on the near (bottom) arc."""
    ring_top = [(21, 4), (22, 3), (23, 3), (24, 2), (25, 2)] + [(x, 1) for x in range(26, 32)]
    ring_bot = [(21, 5), (22, 6), (23, 6), (24, 7), (25, 7)] + [(x, 8) for x in range(26, 32)]
    for (x, y) in ring_top:
        sym(m, x, y, P('gold', 3))
    for (x, y) in ring_bot:
        sym(m, x, y, P('gold', 2))
    for x in range(26, 32):
        sym(m, x, 2, P('gold', 4)) if x < 30 else None    # inner highlight on the far arc
        sym(m, x, 7, P('gold', 3))                         # near arc is two pixels thick
    sym(m, 20, 4, P('gold', 3)); sym(m, 20, 5, P('gold', 2))
    m.px(23, 3, P('gold', 4)); m.px(24, 2, P('gold', 4))                # glint on the far arc, upper left


# ---------------------------------------------------------------- island (symmetric 2:1 iso)
def draw_island(m, k=9, ty=28, lip=2, soil=3, rock=2):
    """Top: 4k x 2k rhombus centred on the seam. Sides: turf lip, soil, rock, then a keel that tapers three
    pixels per row on each face, so both faces end at the centre."""
    cx = 32
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            col = P('moss', 3)
            if r < k and x <= -hw + 1:
                col = P('moss', 4)                       # lit back-left edge
            elif r < k and x >= hw - 2:
                col = P('moss', 3)
            elif r >= k and x >= hw - 2:
                col = P('moss', 2)                       # front-right edge turns from the light
            elif r >= k and x <= -hw + 1:
                col = P('moss', 3)
            m.px(cx + x, ty + r, col)
    # two quiet, mirrored grass tufts
    for (dx, dy) in [(-7, k - 1), (-6, k - 1), (-6, k - 2)]:
        m.px(cx + dx, ty + dy, P('moss', 2)); m.px(cx - 1 - dx, ty + dy, P('moss', 2))
    for (dx, dy) in [(-2, 3), (-1, 3)]:
        m.px(cx + dx, ty + dy, P('moss', 4)); m.px(cx - 1 - dx, ty + dy, P('moss', 4))
    fringe = [0, 1, 0, 0, 1, 1, 0, 1, 0, 0]               # regular turf drips, mirrored on both faces
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        v = 0
        while True:
            depth = v - (lip + soil + rock)
            if depth >= 0 and u < (depth + 1) * 3:
                break
            for side in (0, 1):
                x = cx - 2 * k + u if side == 0 else cx + 2 * k - 1 - u
                sh = side
                d = fringe[u % len(fringe)]
                if v < lip + d:
                    col = P('moss', 3 - sh)
                elif v == lip + d:
                    col = P('moss', 1)
                elif v < lip + soil + 1:
                    col = P('soil', 3 - sh)
                elif v == lip + soil + 1:
                    col = P('soil', 1)
                else:
                    deep = 1 if v > lip + soil + 3 else 0
                    col = P('stone', 3 - sh - deep)
                if side == 1 and u == 2 * k - 1:
                    col = step_down(col)                  # seam: one ramp step darker, not ink
                m.px(x, y0 + v, col)
            v += 1
    # block seams: the island is built of blocks; every 6 columns a darker vertical step on both faces,
    # and one horizontal course line in the rock, so it reads as Minecraft blocks, not a smooth rock
    for u in (6, 12):
        y0 = ty + k + u // 2 + 1
        for v in range(lip + 1, lip + soil + rock + 1):
            for side in (0, 1):
                x = cx - 2 * k + u if side == 0 else cx + 2 * k - 1 - u
                p = m.get(x, y0 + v)
                if p[3]:
                    m.px(x, y0 + v, step_down(p))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        v = lip + soil + 4
        for side in (0, 1):
            x = cx - 2 * k + u if side == 0 else cx + 2 * k - 1 - u
            p = m.get(x, y0 + v)
            if p[3] and u >= 2:
                m.px(x, y0 + v, step_down(p))
    # two stones, mirrored
    m.px(cx - 2 * k + 5, ty + k + 3 + lip + soil + 2, P('stone', 4))
    m.px(cx + 2 * k - 1 - 5, ty + k + 3 + lip + soil + 2, P('stone', 2))
    return [(cx - 2 * k + 5, ty + k - 2), (cx, ty + 4), (cx + 2 * k - 1 - 5, ty + k - 2)]


def draw_strings(m, anchors, y0=23):
    for (x, y) in anchors:
        xx = x if x < 32 else x - 1
        for yy in range(y0, y):
            m.px(xx, yy, P('cyan', 3)); m.px(xx + 1, yy, P('cyan', 1))
        m.px(xx, y, P('cyan', 4)); m.px(xx + 1, y, P('cyan', 3))
        m.px(xx, y0, P('cyan', 4)); m.px(xx + 1, y0, P('cyan', 2))      # bead where the string leaves the cloud


def mark_layer():
    """The bare mark on transparency (with ink outline), for logos and lockups."""
    m = Layer(64, 64)
    draw_cloud(m)
    draw_visor(m)
    anchors = draw_island(m)
    draw_strings(m, anchors)
    draw_halo(m)
    outline_outside(m, PAL['ink'])
    aa_corners(m, PAL['ink'])
    return m


# ---------------------------------------------------------------- background: a quiet banded sky
def sky(w, h, scale_hint=1, clouds=True, seed_x=0):
    """Stepped sky bands (no gradient), a few far square clouds one step lighter than their band."""
    L = Layer(w, h, P('sky', 0))
    bands = [(0.0, 0), (0.28, 1), (0.55, 2), (0.8, 3)]
    for f, i in bands:
        L.rect(0, int(h * f), w - 1, h - 1, P('sky', i))
    # dither-free band edges: a one-pixel checker row between bands to soften, every other pixel
    if clouds:
        for (fx, fy, cw) in [(0.08, 0.2, 10), (0.78, 0.12, 14), (0.62, 0.62, 9), (0.18, 0.74, 12), (0.9, 0.8, 8)]:
            x, y = int(w * fx) + seed_x, int(h * fy)
            band = max(i for f, i in bands if y >= int(h * f))
            col = P('sky', min(4, band + 1))
            L.rect(x, y, x + cw, y + 2, col)
            L.rect(x + 2, y - 1, x + cw // 2, y - 1, col)
    return L


def icon64():
    t = sky(64, 64, clouds=False)
    # two far square clouds placed by hand for the tile
    for (x, y, w) in [(2, 26, 7), (55, 44, 7)]:
        t.rect(x, y, x + w, y + 1, P('sky', 3)); t.rect(x + 2, y - 1, x + 4, y - 1, P('sky', 3))
    m = mark_layer()
    t.paste(m, 0, 3)          # 3 px of sky above the halo; the mark spans rows 3-61
    return t
