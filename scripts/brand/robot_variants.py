"""Follow-up (Artem's idea, developed): an AI shown as a humanoid robot pulling the strings with its own iron hands.
Same palette and craft rules as the approved mark."""
from pix import Layer, T
from art import c
from final_mark import PAL, P, sky, outline_outside, aa_corners, draw_island
from hanging_bot import hanging_bot, strings, tile

s = lambda i: c('steel', i)


def robot_head(m, cx, top, w=20, h=14, eyes_low=True, antenna=True):
    x0, x1 = cx - w // 2, cx + w // 2 - 1
    m.rect(x0, top, x1, top + h - 1, s(3))
    m.rect(x0, top, x1, top + 1, s(4)); m.rect(x0, top, x0 + 1, top + h - 1, s(4))
    m.rect(x1 - 1, top + 2, x1, top + h - 1, s(2)); m.rect(x0 + 2, top + h - 2, x1, top + h - 1, s(2))
    vy = top + 4
    m.rect(x0 + 3, vy, x1 - 3, vy + 5, PAL['visor'][0]); m.rect(x0 + 3, vy, x1 - 3, vy, PAL['visor'][1])
    ey = vy + 3 if eyes_low else vy + 2
    for ex in (x0 + 5, x1 - 7):
        m.rect(ex, ey, ex + 2, ey, P('cyan', 4)); m.rect(ex, ey + 1, ex + 2, ey + 1, P('cyan', 3))
    if antenna:
        m.rect(cx - 1, top - 3, cx, top - 1, s(2)); m.rect(cx - 2, top - 5, cx + 1, top - 4, P('cyan', 3))
    m.rect(x0 - 2, top + 5, x0 - 1, top + 9, s(2)); m.rect(x1 + 1, top + 5, x1 + 2, top + 9, s(1))   # ear bolts


def halo(m, cx, y, half=9):
    for x in range(cx - half, cx + half):
        m.px(x, y, P('gold', 3)); m.px(x, y + 2, P('gold', 2))
    m.px(cx - half - 1, y + 1, P('gold', 3)); m.px(cx + half, y + 1, P('gold', 2))
    m.px(cx - half + 2, y, P('gold', 4)); m.px(cx - half + 3, y, P('gold', 4))


def iron_fist(m, x, y, side):
    m.rect(x - 2, y - 2, x + 2, y + 2, s(3) if side == 0 else s(2))
    m.rect(x - 2, y - 2, x + 2, y - 2, s(4))
    m.rect(x - 2, y, x + 2, y, s(1))                                   # finger seam
    m.px(x, y - 1, P('cyan', 3))                                      # knuckle light


def control_bar(m, x0, x1, y):
    """A marionette control: a wooden bar with a short cross piece; returns the string points."""
    m.rect(x0, y, x1, y + 1, c('soil', 3)); m.rect(x0, y + 1, x1, y + 1, c('soil', 2))
    m.rect(x0, y, x1, y, c('soil', 4))
    cx = (x0 + x1) // 2
    m.rect(cx - 1, y - 2, cx, y + 3, c('soil', 3))
    return [(x0 + 1, y + 2), (cx, y + 4), (x1 - 1, y + 2)]


def torso_arms_to(m, cx, sh_y, hands):
    """Shoulders and two jointed arms from the shoulders to the given hand points."""
    m.rect(cx - 12, sh_y, cx + 11, sh_y + 3, s(3)); m.rect(cx - 12, sh_y, cx + 11, sh_y, s(4))
    m.rect(cx - 12, sh_y + 3, cx + 11, sh_y + 3, s(1))
    m.rect(cx - 2, sh_y - 2, cx + 1, sh_y - 1, s(1))                   # neck
    m.rect(cx - 2, sh_y + 1, cx + 1, sh_y + 2, P('cyan', 2))            # chest light
    for side, (hx, hy) in enumerate(hands):
        sx = cx - 12 if side == 0 else cx + 11
        n = max(abs(hx - sx), abs(hy - sh_y))
        for i in range(n + 1):
            x = sx + round((hx - sx) * i / n); y = sh_y + 1 + round((hy - sh_y - 1) * i / n)
            m.rect(x - 1, y - 1, x + 1, y + 1, s(3) if side == 0 else s(2))
        ex, ey = (sx + hx) // 2, (sh_y + hy) // 2
        m.rect(ex - 1, ey - 1, ex + 1, ey + 1, s(1))                    # elbow joint
        iron_fist(m, hx, hy, side)


def island_small(m, ty=38, k=7):
    return draw_island(m, k=k, ty=ty)


def va_upright_bar():
    """An upright robot puppeteer grips the marionette cross with both iron hands; strings work an island."""
    m = Layer(64, 64)
    anchors = island_small(m, 38, 7)
    pts = control_bar(m, 6, 57, 31)
    robot_head(m, 32, 5)
    torso_arms_to(m, 32, 21, [(10, 30), (53, 30)])
    strings(m, [(pts[0], anchors[0]), (pts[1], anchors[1]), (pts[2], anchors[2])])
    return tile(m)


def vb_upright_bar_halo():
    m = Layer(64, 64)
    anchors = island_small(m, 38, 7)
    pts = control_bar(m, 6, 57, 32)
    robot_head(m, 32, 7, antenna=False)
    halo(m, 32, 2)
    torso_arms_to(m, 32, 22, [(10, 31), (53, 31)])
    strings(m, [(pts[0], anchors[0]), (pts[1], anchors[1]), (pts[2], anchors[2])])
    return tile(m)


def vc_fingers_player():
    """The strings are tied to its iron fingers; it works a small player standing on a block."""
    from puppets import player
    from round9 import grass
    m = Layer(64, 64)
    grass(m, 32, 52, 6, 4)
    a = player(m, 32, 37)
    robot_head(m, 32, 5)
    torso_arms_to(m, 32, 21, [(10, 30), (53, 30)])
    strings(m, [((10, 33), a[0]), ((53, 33), a[2])])
    m.line(32, 25, a[1][0], a[1][1], P('cyan', 3))                     # a third string from the chest
    return tile(m)


def vd_hanging_bar():
    """Hanging upside down from the top edge (feet to the north), gripping the cross; strings work an island."""
    m = Layer(64, 64)
    anchors = island_small(m, 38, 7)
    h1, h2 = hanging_bot(m)
    pts = control_bar(m, h1[0] - 2, h2[0] + 2, h1[1] - 2)
    strings(m, [(pts[0], anchors[0]), (pts[1], anchors[1]), (pts[2], anchors[2])])
    return tile(m)


def ve_hands_close():
    """Close framing: two big iron hands on the cross; the robot's eyes peek over the top edge."""
    m = Layer(64, 64)
    anchors = island_small(m, 37, 8)
    # the robot's face, cut by the top edge: only the visor and eyes show
    m.rect(18, 0, 45, 9, s(3)); m.rect(18, 0, 19, 9, s(4)); m.rect(44, 0, 45, 9, s(2)); m.rect(18, 9, 45, 9, s(2))
    m.rect(21, 1, 42, 6, PAL['visor'][0])
    for ex in (24, 36):
        m.rect(ex, 4, ex + 3, 4, P('cyan', 4)); m.rect(ex, 5, ex + 3, 5, P('cyan', 3))
    pts = control_bar(m, 4, 59, 21)
    for (hx, side) in ((12, 0), (51, 1)):                               # big fists with wrists going up out of frame
        m.rect(hx - 4, 10, hx + 3, 17, s(2) if side else s(3))
        m.rect(hx - 4, 10, hx - 3, 17, s(4) if side == 0 else s(3))
        m.rect(hx - 5, 17, hx + 4, 24, s(3) if side == 0 else s(2))
        m.rect(hx - 5, 17, hx + 4, 17, s(4))
        for fx in range(hx - 4, hx + 4, 3):
            m.rect(fx, 20, fx, 24, s(1))
        m.rect(hx - 1, 12, hx, 13, P('cyan', 3))
    strings(m, [(pts[0], anchors[0]), (pts[1], anchors[1]), (pts[2], anchors[2])])
    return tile(m)


ROBOTS = {'robot-bar': va_upright_bar, 'robot-bar-halo': vb_upright_bar_halo, 'robot-fingers-player': vc_fingers_player,
          'robot-hanging-bar': vd_hanging_bar, 'robot-hands-close': ve_hands_close}
