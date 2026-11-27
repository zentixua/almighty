"""Round five finalist candidates at 64x64, full craft. Different concept families."""
from pix import Layer, hexc, T
from art import R, c, mix, INK, outline_outer, aa_corners
import concepts32  # registers the cmd / skin / lead / ender ramps
from cand import t2_theater_screen

BGS = {'violet': (hexc('#221C3F'), hexc('#2E2754')), 'teal': (hexc('#14303A'), hexc('#1C4250')),
       'ember': (hexc('#2B1A24'), hexc('#3A2430'))}


def finish64(m, tone='violet', disc=(32, 34, 27)):
    """Two-tone tile: a large soft disc behind the subject (flat, no gradient), ink outline, AA."""
    bg, hi = BGS[tone]
    outline_outer(m)
    t = Layer(64, 64, bg)
    cx, cy, r = disc
    for y in range(64):
        for x in range(64):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                t.px(x, y, hi)
    t.paste(m, 0, 0)
    aa_corners(t, bg=bg)
    aa_corners(t, bg=hi)
    return t


def cmd_block(m, cx, ty, k=12, s=22, eyes=True, top_inset=True):
    """A command block in the vanilla manner (our drawing): framed faces, dark inset squares with a lighter
    inner ring and an arrow; the front inset can carry the agent's eyes."""
    cm = lambda i: c('cmd', i)
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            col = cm(3)
            if r == 0 or (r < k and x <= -hw + 1): col = cm(4)
            m.px(cx + x, ty + r, col)
    if top_inset:
        kk = k - 4
        for r in range(2 * kk):
            hw = 2 * (r + 1) if r < kk else 2 * (2 * kk - r)
            for x in range(-hw, hw):
                m.px(cx + x, ty + 4 + r, c('steel', 1) if r < kk else c('steel', 2))
        m.px(cx - 1, ty + 4 + kk - 1, cm(3)); m.px(cx, ty + 4 + kk - 1, cm(3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(s):
            lu = cm(3) if (u <= 1 or v <= 1) else cm(2)
            ru = cm(2) if v <= 1 else cm(1)
            if v == s - 1: lu, ru = cm(1), cm(0)
            m.px(cx - 2 * k + u, y0 + v, lu)
            m.px(cx + 2 * k - 1 - u, y0 + v, ru)
    # insets
    for u in range(4, 2 * k - 3):
        y0 = ty + k + u // 2 + 1
        for v in range(4, s - 3):
            edge = u == 4 or v == 4
            m.px(cx - 2 * k + u, y0 + v, c('steel', 2) if edge else c('steel', 0))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('steel', 1) if edge else c('steel', 0))
    # arrow on the right inset: a chevron pointing back
    for i in range(4):
        u = k - 2 + i
        y0 = ty + k + u // 2 + 1
        m.px(cx + 2 * k - 1 - u, y0 + s // 2 - 3 + i, cm(3))
        m.px(cx + 2 * k - 1 - u, y0 + s // 2 + 3 - i, cm(3))
    if eyes:
        for u0 in (7, 15):
            for du in range(3):
                u = u0 + du
                y0 = ty + k + u // 2 + 1
                for v in range(9, 13):
                    m.px(cx - 2 * k + u, y0 + v, c('cyan', 4) if v < 11 else c('cyan', 2))
                m.px(cx - 2 * k + u, y0 + 13, c('cyan', 1))


def r1_living_block():
    m = Layer(64, 64)
    cmd_block(m, 32, 7, 12, 22)
    return finish64(m, 'violet', (32, 36, 28))


def r2_block_head():
    """A player-shaped head made of a command block: the screen is the face."""
    m = Layer(64, 64)
    cm = lambda i: c('cmd', i)
    x0, y0, n = 10, 6, 44
    m.rect(x0, y0, x0 + n - 1, y0 + n - 1, cm(3))
    m.rect(x0, y0, x0 + n - 1, y0 + 2, cm(4)); m.rect(x0, y0, x0 + 2, y0 + n - 1, cm(4))
    m.rect(x0 + n - 4, y0 + 3, x0 + n - 1, y0 + n - 1, cm(2)); m.rect(x0 + 3, y0 + n - 3, x0 + n - 1, y0 + n - 1, cm(1))
    # framed screen face
    m.rect(x0 + 6, y0 + 10, x0 + n - 7, y0 + n - 8, c('steel', 1))
    m.rect(x0 + 7, y0 + 11, x0 + n - 8, y0 + n - 9, c('steel', 0))
    for yy in range(y0 + 12, y0 + n - 9, 2):
        m.rect(x0 + 7, yy, x0 + n - 8, yy, c('night', 2))
    for ex in (x0 + 11, x0 + n - 18):
        m.rect(ex, y0 + 19, ex + 6, y0 + 24, c('cyan', 3))
        m.rect(ex, y0 + 19, ex + 6, y0 + 20, c('cyan', 4))
        m.rect(ex, y0 + 25, ex + 6, y0 + 25, c('cyan', 1))
    m.rect(x0 + 17, y0 + 30, x0 + n - 18, y0 + 31, c('cyan', 2))      # mouth line
    # neck stub
    m.rect(26, 50, 37, 55, c('steel', 2)); m.rect(26, 50, 37, 50, c('steel', 3))
    return finish64(m, 'teal', (32, 30, 28))


def r3_possessed_head():
    """A player head (our own face) whose eyes have been taken over: cyan light and a scan line."""
    m = Layer(64, 64)
    s = lambda i: c('skin', i)
    x0, y0, n = 10, 8, 44
    m.rect(x0, y0, x0 + n - 1, y0 + n - 1, s(3))
    m.rect(x0 + n - 5, y0 + 1, x0 + n - 1, y0 + n - 1, s(2)); m.rect(x0, y0 + n - 4, x0 + n - 1, y0 + n - 1, s(2))
    hair = c('stone', 1)
    m.rect(x0, y0, x0 + n - 1, y0 + 9, hair); m.rect(x0, y0, x0 + n - 1, y0 + 1, c('stone', 2))
    m.rect(x0, y0 + 10, x0 + 3, y0 + 15, hair); m.rect(x0 + n - 4, y0 + 10, x0 + n - 1, y0 + 13, hair)
    m.rect(x0 + 18, y0 + 10, x0 + 25, y0 + 11, hair)                    # side part
    for ex in (x0 + 7, x0 + n - 15):
        m.rect(ex, y0 + 20, ex + 7, y0 + 24, c('cyan', 3))
        m.rect(ex, y0 + 20, ex + 7, y0 + 21, c('cyan', 4))
        m.rect(ex - 1, y0 + 25, ex + 8, y0 + 25, s(1))
    m.rect(x0 + 16, y0 + 33, x0 + 27, y0 + 34, s(1))
    # scan line through the eyes, spilling past the head
    m.rect(x0 - 6, y0 + 22, x0 + n + 5, y0 + 22, c('cyan', 4))
    m.rect(x0 - 9, y0 + 22, x0 - 7, y0 + 22, c('cyan', 2)); m.rect(x0 + n + 6, y0 + 22, x0 + n + 8, y0 + 22, c('cyan', 2))
    # displaced slice (glitch) on the right edge
    for yy in range(y0 + 26, y0 + 29):
        for xx in range(x0 + n - 10, x0 + n):
            m.px(xx + 3, yy, m.get(xx, yy))
    return finish64(m, 'violet', (32, 32, 28))


def r4_leash():
    """The owner's hand holds a lead tied to a living command block: power on a leash."""
    from concepts32 import lead, knot
    m = Layer(64, 64)
    cmd_block(m, 38, 4, 9, 16)
    pts = [(18, 26), (13, 32), (10, 39), (10, 46)]
    for (a, b) in zip(pts, pts[1:]):
        lead(m, [a, b])
        lead(m, [(a[0] + 1, a[1]), (b[0] + 1, b[1])])
    knot(m, 19, 25); knot(m, 20, 26)
    # hand from the bottom left, fist around the lead
    s = lambda i: c('skin', i)
    m.rect(4, 46, 17, 55, s(3)); m.rect(4, 46, 17, 47, s(4)); m.rect(15, 48, 17, 55, s(2))
    for x in range(5, 16, 3):
        m.rect(x, 46, x, 49, s(2))
    m.rect(0, 56, 18, 63, c('gold', 2)); m.rect(0, 56, 18, 57, c('gold', 3))   # sleeve
    return finish64(m, 'ember', (36, 28, 26))


def r6_monogram():
    """A monogram A: command-block legs, the crossbar a dark band holding the agent's eyes."""
    m = Layer(64, 64)
    cm = lambda i: c('cmd', i)
    for y in range(6, 58):
        w = 6 + (y - 6) * 21 // 52
        a, b = 32 - w, 31 + w
        m.rect(a, y, a + 9, y, cm(3)); m.rect(b - 9, y, b, y, cm(2))
        m.px(a, y, cm(4)); m.px(a + 1, y, cm(4)); m.px(b, y, cm(1))
    m.rect(26, 6, 37, 9, cm(4)); m.rect(26, 10, 37, 12, cm(3))
    m.rect(14, 34, 49, 43, c('steel', 0)); m.rect(14, 34, 49, 34, c('steel', 2))
    m.rect(20, 37, 25, 40, c('cyan', 3)); m.rect(20, 37, 25, 37, c('cyan', 4))
    m.rect(38, 37, 43, 40, c('cyan', 3)); m.rect(38, 37, 43, 37, c('cyan', 4))
    return finish64(m, 'violet', (32, 34, 28))


def r5_puppeteer():
    return t2_theater_screen()


R5 = {
    'living-block': (r1_living_block, 'Living command block: the block every player knows as admin power, awake, with the agent\'s eyes in its panel.'),
    'block-head': (r2_block_head, 'Block head: a player-shaped head made of a command block, its screen the agent\'s face.'),
    'possessed': (r3_possessed_head, 'Possessed player: a player head whose eyes the agent has taken over, a scan line through them.'),
    'leash': (r4_leash, 'On a leash: the owner\'s hand holds the lead of a living command block, power held in check.'),
    'puppeteer': (r5_puppeteer, 'Puppeteer (Artem\'s pick #1): on a puppet stage, a screen with the agent\'s eyes works a player on strings.'),
    'monogram': (r6_monogram, 'Monogram A: an A in command-block orange, its crossbar the agent\'s visor.'),
}


# ---------------------------------------------------------------- refinement pass (after the retest)
def cmd_block2(m, cx, ty, k=12, s=22):
    """Second pass: the top carries the same framed inset as the sides (so it reads as one designed block),
    the agent's eyes are taller, the arrow mark is cleaner, and face edges get a 1-px bevel."""
    cm = lambda i: c('cmd', i)
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, cm(4) if (r == 0 or (r < k and x <= -hw + 1)) else cm(3))
    kk = k - 4
    for r in range(2 * kk):                                   # top inset: framed, with a lit inner ring
        hw = 2 * (r + 1) if r < kk else 2 * (2 * kk - r)
        for x in range(-hw, hw):
            ring = (r == 0 or x <= -hw + 1)
            m.px(cx + x, ty + 4 + r, c('steel', 2) if ring else c('steel', 1))
    for x in (-1, 0):
        m.px(cx + x, ty + 4 + kk - 2, cm(3)); m.px(cx + x, ty + 4 + kk - 1, cm(2))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(s):
            lu = cm(4) if u == 0 else (cm(3) if (u <= 2 or v <= 1) else cm(2))
            ru = cm(2) if v <= 1 else cm(1)
            if v == s - 1:
                lu, ru = cm(1), cm(0)
            m.px(cx - 2 * k + u, y0 + v, lu)
            m.px(cx + 2 * k - 1 - u, y0 + v, ru)
    for u in range(4, 2 * k - 3):
        y0 = ty + k + u // 2 + 1
        for v in range(4, s - 3):
            edge = u == 4 or v == 4
            m.px(cx - 2 * k + u, y0 + v, c('steel', 2) if edge else c('steel', 0))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('steel', 1) if edge else c('steel', 0))
    for i in range(5):                                        # arrow: a clean chevron
        u = k - 3 + i
        y0 = ty + k + u // 2 + 1
        m.px(cx + 2 * k - 1 - u, y0 + s // 2 - 4 + i, cm(3))
        m.px(cx + 2 * k - 1 - u, y0 + s // 2 + 4 - i, cm(3))
    for u0 in (7, 15):                                        # eyes: 3 wide, 6 tall, lit top, glow foot
        for du in range(3):
            u = u0 + du
            y0 = ty + k + u // 2 + 1
            for v in range(8, 14):
                m.px(cx - 2 * k + u, y0 + v, c('cyan', 4) if v < 10 else c('cyan', 3) if v < 13 else c('cyan', 2))
            m.px(cx - 2 * k + u, y0 + 14, c('cyan', 1))


def r1b_living_block():
    m = Layer(64, 64)
    cmd_block2(m, 32, 7, 12, 22)
    return finish64(m, 'violet', (32, 36, 28))


def r2b_block_head():
    m = Layer(64, 64)
    cm = lambda i: c('cmd', i)
    x0, y0, n = 10, 5, 44
    m.rect(x0, y0, x0 + n - 1, y0 + n - 1, cm(3))
    m.rect(x0, y0, x0 + n - 1, y0 + 2, cm(4)); m.rect(x0, y0, x0 + 2, y0 + n - 1, cm(4))
    m.rect(x0 + n - 4, y0 + 3, x0 + n - 1, y0 + n - 1, cm(2)); m.rect(x0 + 3, y0 + n - 4, x0 + n - 1, y0 + n - 1, cm(1))
    m.rect(x0 + n - 1, y0 + 3, x0 + n - 1, y0 + n - 1, cm(0))
    for (rx, ry) in [(x0 + 4, y0 + 4), (x0 + n - 7, y0 + 4), (x0 + 4, y0 + n - 7), (x0 + n - 7, y0 + n - 7)]:
        m.rect(rx, ry, rx + 1, ry + 1, cm(1)); m.px(rx, ry, cm(4))          # rivets
    # framed screen with an inner lit ring (the command block's inset, now a face)
    m.rect(x0 + 6, y0 + 9, x0 + n - 7, y0 + n - 8, c('steel', 2))
    m.rect(x0 + 7, y0 + 10, x0 + n - 8, y0 + n - 9, c('steel', 0))
    m.rect(x0 + 7, y0 + 10, x0 + n - 8, y0 + 10, c('steel', 1))
    for ex in (x0 + 11, x0 + n - 18):
        m.rect(ex, y0 + 17, ex + 6, y0 + 23, c('cyan', 3))
        m.rect(ex, y0 + 17, ex + 6, y0 + 18, c('cyan', 4))
        m.rect(ex, y0 + 24, ex + 6, y0 + 24, c('cyan', 1))
    # the arrow of the command block as its mouth
    for i in range(4):
        m.px(x0 + 20 + i, y0 + 29 + i, c('cmd', 3)); m.px(x0 + 20 + i, y0 + 29 + 6 - i, c('cmd', 3))
    m.rect(26, 49, 37, 54, c('steel', 2)); m.rect(26, 49, 37, 49, c('steel', 3)); m.rect(36, 50, 37, 54, c('steel', 1))
    return finish64(m, 'violet', (32, 28, 28))


def r6b_monogram():
    m = Layer(64, 64)
    cm = lambda i: c('cmd', i)
    for y in range(6, 58):
        w = 6 + (y - 6) * 21 // 52
        a, b = 32 - w, 31 + w
        m.rect(a, y, a + 9, y, cm(3)); m.rect(b - 9, y, b, y, cm(2))
        m.px(a, y, cm(4)); m.px(a + 1, y, cm(4)); m.px(b, y, cm(1))
        m.px(a + 9, y, cm(2)); m.px(b - 9, y, cm(3))                         # inner bevels
    m.rect(26, 6, 37, 8, cm(4)); m.rect(26, 9, 37, 12, cm(3))
    # the crossbar is the command block's inset, set inside the counter, holding the agent's eyes
    m.rect(20, 33, 43, 42, cm(3)); m.rect(20, 33, 43, 33, cm(4)); m.rect(20, 42, 43, 42, cm(1))
    m.rect(22, 35, 41, 40, c('steel', 0)); m.rect(22, 35, 41, 35, c('steel', 2))
    m.rect(25, 36, 29, 39, c('cyan', 3)); m.rect(25, 36, 29, 36, c('cyan', 4))
    m.rect(34, 36, 38, 39, c('cyan', 3)); m.rect(34, 36, 38, 36, c('cyan', 4))
    for (rx, ry) in [(29, 16), (33, 16)]:
        pass
    return finish64(m, 'violet', (32, 34, 28))


R5B = {'living-block': r1b_living_block, 'block-head': r2b_block_head, 'monogram': r6b_monogram}
