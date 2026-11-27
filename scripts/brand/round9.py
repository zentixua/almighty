"""Round nine: combos of the strongest cues (halo, the agent's small visor, acting on the world)."""
from pix import Layer, hexc, T
from art import c, mix, INK
from round8 import fin, halo
from clouds32 import cloud
from marks2 import island
from round6 import finish_sky

ARROW = ["4..............", "44.............", "434............", "4334...........", "43334..........",
         "433334.........", "4333334........", "43333334.......", "433333334......", "4333333334.....",
         "43333333334....", "433333333334...", "4333333222222..", "4333223........", "43322.23.......",
         "4322..223......", "422....223.....", "42.....2233....", ".......2233....", "........22....."]


def grass(m, cx, ty, k, s):
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c('moss', 4) if r < 2 or (x <= -hw + 1 and r < k) else c('moss', 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(s):
            m.px(cx - 2 * k + u, y0 + v, c('moss', 2) if v < 3 else c('soil', 3))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('moss', 1) if v < 3 else c('soil', 2))


def visor(m, x, y, w=9, h=4):
    m.rect(x, y, x + w - 1, y + h - 1, c('steel', 0))
    m.rect(x + 1, y + 1, x + 2, y + 2, c('cyan', 4)); m.rect(x + w - 3, y + 1, x + w - 2, y + 2, c('cyan', 4))


def c1_visor_cursor():
    """The haloed cursor wears the agent's visor: the pointer is the agent, and it acts on the world."""
    m = Layer(64, 64)
    grass(m, 40, 30, 9, 16)
    halo(m, 26, 8, 12, 3)
    m.sprite(ARROW, 20, 13, {'4': c('steel', 4), '3': hexc('#F2F8FA'), '2': c('steel', 3)})
    visor(m, 22, 22, 8, 4)
    return fin(m, 'night', (32, 32, 28))


def c2_halo_bot_beam():
    """The haloed agent's eyes cast a beam onto a grass block: it sees and acts on the world."""
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    m.rect(6, 12, 29, 33, s(3)); m.rect(6, 12, 29, 13, s(4)); m.rect(6, 12, 7, 33, s(4)); m.rect(27, 14, 29, 33, s(2))
    m.rect(9, 18, 26, 25, s(0)); m.rect(9, 18, 26, 18, s(1))
    m.rect(12, 20, 15, 22, c('cyan', 4)); m.rect(20, 20, 23, 22, c('cyan', 4))
    halo(m, 17, 5, 11, 3)
    grass(m, 44, 34, 8, 14)
    for i in range(14):
        x, y = 26 + i, 24 + (i * 2) // 3 + 1
        m.px(x, y, c('cyan', 3) if i % 2 == 0 else c('cyan', 2))
    return fin(m, 'night', (34, 32, 28))


def c3_halo_bot_world():
    """The haloed agent looks out over the edge of the world (a grass block in front of it)."""
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    m.rect(14, 16, 49, 44, s(3)); m.rect(14, 16, 49, 17, s(4)); m.rect(14, 16, 15, 44, s(4)); m.rect(46, 18, 49, 44, s(2))
    m.rect(19, 23, 44, 32, s(0)); m.rect(19, 23, 44, 23, s(1))
    m.rect(23, 26, 27, 29, c('cyan', 4)); m.rect(36, 26, 40, 29, c('cyan', 4))
    halo(m, 32, 7, 17, 4)
    grass(m, 32, 38, 12, 6)
    return fin(m, 'night', (32, 34, 28))


def cloud_narrow(m, halo_on):
    cloud(m, [(14, 8, 27, 11), (31, 7, 44, 11), (10, 12, 53, 21), (13, 22, 50, 23)])
    m.rect(25, 14, 38, 18, c('steel', 0)); m.rect(25, 14, 38, 14, c('steel', 1))
    m.rect(27, 16, 29, 17, c('cyan', 4)); m.rect(34, 16, 36, 17, c('cyan', 4))
    if halo_on:
        halo(m, 32, 3, 13, 2)
    island(m, 32, 31, 9, sb=6, taper=2, tree=False, roots=False)
    for (x, y) in [(18, 41), (32, 33), (46, 41)]:
        for yy in range(24, y):
            m.px(x, yy, c('cyan', 3)); m.px(x + 1, yy, c('cyan', 1))
        m.px(x, y, c('cyan', 4)); m.px(x + 1, y, c('cyan', 3))


def c4_halo_cloud():
    m = Layer(64, 64)
    cloud_narrow(m, True)
    return finish_sky(m, (32, 34, 29))


def c5_narrow_cloud():
    m = Layer(64, 64)
    cloud_narrow(m, False)
    return finish_sky(m, (32, 34, 29))


COMBOS = {'visor-cursor': c1_visor_cursor, 'halo-bot-beam': c2_halo_bot_beam, 'halo-bot-world': c3_halo_bot_world,
          'halo-cloud': c4_halo_cloud, 'narrow-cloud': c5_narrow_cloud}
