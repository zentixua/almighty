"""Round eight: six new one-idea marks at 64x64 (icon + the name must say it)."""
from pix import Layer, hexc, T
from art import R, c, mix, INK, outline_outer, aa_corners
import concepts32  # ramps: cmd, skin, lead, ender
import clouds32    # ramp: cloud

TILES = {'night': (hexc('#1E1A36'), hexc('#29234A')), 'sky': (hexc('#23417A'), hexc('#2D5592')),
         'dusk': (hexc('#2A1C33'), hexc('#3A2745'))}


def fin(m, tone='night', disc=(32, 32, 28)):
    bg, hi = TILES[tone]
    outline_outer(m)
    t = Layer(64, 64, bg)
    cx, cy, r = disc
    for y in range(64):
        for x in range(64):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                t.px(x, y, hi)
    t.paste(m, 0, 0)
    aa_corners(t, bg=bg); aa_corners(t, bg=hi)
    return t


def halo(m, cx, cy, rx, ry):
    for x in range(cx - rx, cx + rx + 1):
        for sgn in (-1, 1):
            t = 1 - ((x - cx) / rx) ** 2
            if t < 0:
                continue
            y = cy + sgn * round(ry * t ** 0.5)
            m.px(x, y, c('gold', 4) if sgn < 0 else c('gold', 2))
            m.px(x, y + (1 if sgn < 0 else -1) * 0, c('gold', 4) if sgn < 0 else c('gold', 2))
    for x in range(cx - rx, cx + rx + 1):
        t = 1 - ((x - cx) / rx) ** 2
        if t >= 0:
            y = cy - round(ry * t ** 0.5)
            m.px(x, y + 1, c('gold', 3))
            y2 = cy + round(ry * t ** 0.5)
            m.px(x, y2 - 1, c('gold', 1)) if abs(x - cx) > rx - 3 else None


def n1_creation():
    """A steel finger reaches to a blocky player finger; a spark jumps between them."""
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    # agent's arm from the upper left, finger pointing right-down
    for i in range(22):
        y = 14 + i // 3
        m.rect(i, y, i, y + 7, s(3)); m.px(i, y, s(4)); m.px(i, y + 7, s(1))
        if i % 7 == 6:
            m.rect(i, y + 1, i, y + 6, s(1))
    m.rect(20, 21, 27, 24, s(3)); m.rect(20, 21, 27, 21, s(4)); m.rect(20, 24, 27, 24, s(1))
    m.rect(26, 22, 27, 23, c('cyan', 3))
    # player's arm from the lower right, blocky, finger pointing left-up
    sk = lambda i: c('skin', i)
    for i in range(22):
        x = 63 - i
        y = 42 - i // 3
        m.rect(x, y, x, y + 7, sk(3)); m.px(x, y, sk(4)); m.px(x, y + 7, sk(1))
    m.rect(36, 34, 43, 37, sk(3)); m.rect(36, 34, 43, 34, sk(4)); m.rect(36, 37, 43, 37, sk(1))
    m.rect(52, 36, 63, 45, c('moss', 2)); m.rect(52, 36, 63, 36, c('moss', 3))     # sleeve
    m.rect(0, 13, 8, 23, c('steel', 1))
    # spark
    for (x, y, k) in [(31, 29, 4), (32, 30, 4), (30, 30, 3), (33, 29, 3), (31, 28, 3), (32, 31, 3), (29, 31, 2), (34, 28, 2)]:
        m.px(x, y, c('cyan', k))
    return fin(m, 'dusk', (32, 30, 22))


def n2_halo_bot():
    """A robot head under a halo: the AI as the almighty."""
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    m.rect(14, 20, 49, 52, s(3)); m.rect(14, 20, 49, 21, s(4)); m.rect(14, 20, 15, 52, s(4))
    m.rect(46, 22, 49, 52, s(2)); m.rect(16, 50, 49, 52, s(2))
    m.rect(19, 29, 44, 40, s(0)); m.rect(19, 29, 44, 29, s(1))
    for ex in (23, 36):
        m.rect(ex, 32, ex + 4, 36, c('cyan', 3)); m.rect(ex, 32, ex + 4, 33, c('cyan', 4))
    m.rect(26, 45, 37, 46, s(1))
    m.rect(10, 30, 13, 41, s(2)); m.rect(50, 30, 53, 41, s(1))
    halo(m, 32, 10, 18, 5)
    return fin(m, 'night', (32, 36, 27))


def n3_palm():
    """The owner's hand, palm up, holds the agent: a small glowing cube with eyes."""
    m = Layer(64, 64)
    sk = lambda i: c('skin', i)
    # palm (blocky), fingers curled up on the right, thumb on the left
    m.rect(10, 40, 53, 51, sk(3)); m.rect(10, 40, 53, 41, sk(4)); m.rect(10, 50, 53, 51, sk(2))
    for x in (39, 44, 49):
        m.rect(x, 33, x + 3, 40, sk(3)); m.rect(x, 33, x + 3, 33, sk(4)); m.rect(x + 3, 34, x + 3, 40, sk(2))
    m.rect(6, 34, 12, 41, sk(3)); m.rect(6, 34, 12, 34, sk(4)); m.rect(6, 35, 6, 41, sk(4))
    m.rect(14, 52, 49, 63, c('gold', 2)); m.rect(14, 52, 49, 53, c('gold', 3))      # sleeve
    # the agent: a small cube of light with eyes, hovering a little above the palm
    from round5 import cmd_block
    cx, ty, k = 26, 13, 7
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c('cyan', 4) if r < 2 or (x <= -hw + 1 and r < k) else c('cyan', 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(10):
            m.px(cx - 2 * k + u, y0 + v, c('cyan', 2))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('cyan', 1))
    for u0 in (4, 9):
        for du in range(2):
            u = u0 + du; y0 = ty + k + u // 2 + 1
            for v in range(3, 7):
                m.px(cx - 2 * k + u, y0 + v, c('steel', 0))
    for (x, y) in [(18, 38), (26, 39), (34, 38)]:
        m.px(x, y, c('cyan', 2))
    return fin(m, 'night', (28, 28, 24))


def n4_switch():
    """God mode, switched by the owner: a toggle switch whose knob is the agent's face, set to ON."""
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    # track
    m.rect(6, 22, 57, 43, s(1)); m.rect(8, 20, 55, 45, s(1)); m.rect(8, 20, 55, 20, s(2))
    m.rect(10, 24, 53, 41, c('cyan', 1)); m.rect(10, 24, 53, 24, c('cyan', 0))
    # ON side lit
    m.rect(10, 25, 30, 40, c('cyan', 2))
    # knob on the right: the agent
    m.rect(32, 16, 57, 49, c('cmd', 3)); m.rect(32, 16, 57, 17, c('cmd', 4)); m.rect(32, 16, 33, 49, c('cmd', 4))
    m.rect(55, 18, 57, 49, c('cmd', 2)); m.rect(34, 47, 57, 49, c('cmd', 1))
    m.rect(36, 23, 53, 39, s(0)); m.rect(36, 23, 53, 23, s(2))
    m.rect(39, 28, 42, 32, c('cyan', 4)); m.rect(47, 28, 50, 32, c('cyan', 4))
    m.rect(39, 32, 42, 32, c('cyan', 2)); m.rect(47, 32, 50, 32, c('cyan', 2))
    # ON mark: a vertical bar in the lit track
    m.rect(19, 28, 21, 37, c('cyan', 4))
    return fin(m, 'night', (32, 32, 28))


def n5_eye_rise():
    """A machine eye rising like a sun over a row of blocks: an all-seeing AI over the world."""
    m = Layer(64, 64)
    cx, cy, r = 32, 36, 20
    for y in range(cy - r, cy + 1):
        for x in range(cx - r, cx + r + 1):
            d = (x - cx) ** 2 + (y - cy) ** 2
            if d <= r * r:
                col = c('steel', 3) if d > (r - 3) ** 2 else c('steel', 0)
                if d <= 11 ** 2: col = c('cyan', 2)
                if d <= 8 ** 2: col = c('cyan', 3)
                if d <= 4 ** 2: col = c('steel', 0)
                m.px(x, y, col)
    m.px(cx - 6, cy - 8, c('cyan', 4)); m.px(cx - 5, cy - 8, c('cyan', 4)); m.px(cx - 6, cy - 7, c('cyan', 4))
    for i, x in enumerate(range(cx - r - 6, cx + r + 7, 4)):
        for a in (-1, 1):
            pass
    # rays
    for (x0, y0, x1, y1) in [(32, 9, 32, 12), (14, 18, 16, 20), (50, 18, 48, 20), (6, 31, 9, 32), (58, 31, 55, 32)]:
        m.line(x0, y0, x1, y1, c('gold', 3))
    # horizon of grass blocks
    for i, x in enumerate(range(0, 64, 8)):
        h = [0, 2, 0, 4, 4, 0, 2, 0][i]
        m.rect(x, 37 - h, x + 7, 63, c('soil', 2)); m.rect(x, 37 - h, x + 7, 39 - h, c('moss', 3))
        m.rect(x, 37 - h, x + 7, 37 - h, c('moss', 4)); m.rect(x + 7, 37 - h, x + 7, 63, c('soil', 1))
    return fin(m, 'dusk', (32, 30, 28))


def n6_halo_cursor():
    """The agent's cursor wears a halo and hovers over a grass block: a godly hand that acts in the game."""
    m = Layer(64, 64)
    arrow = ["4..............", "44.............", "434............", "4334...........", "43334..........",
             "433334.........", "4333334........", "43333334.......", "433333334......", "4333333334.....",
             "43333333334....", "433333333334...", "4333333222222..", "4333223........", "43322.23.......",
             "4322..223......", "422....223.....", "42.....2233....", ".......2233....", "........22....."]
    halo(m, 26, 8, 12, 3)
    m.sprite(arrow, 20, 13, {'4': c('steel', 4), '3': hexc('#F2F8FA'), '2': c('steel', 3)})
    from clouds32 import tiny_block
    # a larger grass block under the cursor tip
    from round5 import cmd_block
    k, cx, ty = 9, 40, 30
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c('moss', 4) if r < 2 or (x <= -hw + 1 and r < k) else c('moss', 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(16):
            m.px(cx - 2 * k + u, y0 + v, c('moss', 2) if v < 3 else c('soil', 3))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('moss', 1) if v < 3 else c('soil', 2))
    m.sprite(arrow, 20, 13, {'4': c('steel', 4), '3': hexc('#F2F8FA'), '2': c('steel', 3)})
    return fin(m, 'night', (32, 32, 28))


NEW = {'creation': n1_creation, 'halo-bot': n2_halo_bot, 'palm': n3_palm, 'switch': n4_switch,
       'eye-rise': n5_eye_rise, 'halo-cursor': n6_halo_cursor}


def n1b_creation():
    """Thick blocky arms: the agent's steel finger and a player's finger almost touch; a spark between."""
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    sk = lambda i: c('skin', i)
    for i in range(24):                        # agent arm: 9 px band from the upper left
        y = 6 + (i * 2) // 3
        m.rect(i, y, i, y + 8, s(3)); m.px(i, y, s(4)); m.px(i, y + 1, s(4)); m.rect(i, y + 7, i, y + 8, s(1))
        if i in (8, 16):
            m.rect(i, y + 1, i, y + 7, s(1))
    m.rect(22, 21, 31, 25, s(3)); m.rect(22, 21, 31, 21, s(4)); m.rect(22, 25, 31, 25, s(1))   # finger
    m.rect(30, 22, 31, 24, c('cyan', 3))
    for i in range(24):                        # player arm from the lower right
        x = 63 - i
        y = 49 - (i * 2) // 3
        m.rect(x, y, x, y + 8, sk(3)); m.px(x, y, sk(4)); m.px(x, y + 1, sk(4)); m.rect(x, y + 7, x, y + 8, sk(1))
    m.rect(32, 38, 41, 42, sk(3)); m.rect(32, 38, 41, 38, sk(4)); m.rect(32, 42, 41, 42, sk(1))
    for i in range(10):                        # sleeve
        x = 63 - i; y = 49 - (i * 2) // 3
        m.rect(x, y - 1, x, y + 9, c('moss', 2)); m.px(x, y - 1, c('moss', 3))
    # spark: a four-point burst between the fingertips
    sx, sy = 32, 32
    for d in range(0, 5):
        col = c('cyan', 4) if d < 2 else c('cyan', 3)
        for (dx, dy) in [(d, 0), (-d, 0), (0, d), (0, -d)]:
            m.px(sx + dx, sy + dy, col)
    for (dx, dy) in [(1, 1), (-1, -1), (1, -1), (-1, 1)]:
        m.px(sx + dx, sy + dy, c('cyan', 3))
    return fin(m, 'dusk', (32, 32, 26))


NEW['creation'] = n1b_creation
