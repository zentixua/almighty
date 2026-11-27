"""Round six refinements at 64x64: almighty from above."""
from pix import Layer, hexc, T
from art import R, c, mix, INK, outline_outer, aa_corners
import clouds32
from clouds32 import cloud
from marks2 import island
import concepts32

SKY0, SKY1 = hexc('#23417A'), hexc('#2D5592')


def finish_sky(m, disc=(32, 30, 29)):
    outline_outer(m)
    t = Layer(64, 64, SKY0)
    cx, cy, r = disc
    for y in range(64):
        for x in range(64):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                t.px(x, y, SKY1)
    t.paste(m, 0, 0)
    aa_corners(t, bg=SKY0); aa_corners(t, bg=SKY1)
    return t


def eye(m, x, y, w=6, h=6):
    m.rect(x, y, x + w - 1, y + h - 1, c('cyan', 3))
    m.rect(x, y, x + w - 1, y + 1, c('cyan', 4))
    m.rect(x, y + h - 1, x + w - 1, y + h - 1, c('cyan', 2))


def arrow_mouth(m, x, y, col):
    for i in range(4):
        m.px(x + i, y + i, col); m.px(x + i, y + 6 - i, col)


def k1_cloud_face():
    """The cloud is the agent: a square cloud with #2's eyes and arrow mouth, looking down."""
    m = Layer(64, 64)
    cloud(m, [(4, 14, 59, 46), (10, 8, 28, 13), (33, 10, 52, 13), (0, 22, 63, 40), (8, 47, 55, 52)])
    m.rect(12, 21, 51, 33, c('steel', 0)); m.rect(12, 21, 51, 21, c('steel', 1))   # a visor in the cloud
    m.rect(11, 22, 11, 32, c('cloud', 1)); m.rect(52, 22, 52, 32, c('cloud', 2))
    eye(m, 18, 24, 7, 6); eye(m, 39, 24, 7, 6)
    arrow_mouth(m, 29, 36, c('cmd', 3)); arrow_mouth(m, 30, 36, c('cmd', 2))
    return finish_sky(m)


def k2_eyes_in_sky():
    """A dark gap between clouds, like a terminal screen in the sky, with eyes looking down at a small island."""
    m = Layer(64, 64)
    m.rect(6, 10, 57, 27, c('steel', 0))
    m.rect(6, 10, 57, 10, c('steel', 1))
    for y in range(12, 27, 2):
        m.rect(6, y, 57, y, c('night', 2))
    eye(m, 16, 15, 7, 6); eye(m, 41, 15, 7, 6)
    cloud(m, [(0, 2, 63, 9), (8, 0, 26, 1), (36, 0, 55, 1)])
    cloud(m, [(0, 26, 63, 33), (10, 34, 52, 37)])
    island(m, 32, 44, 5, sb=5, taper=2, tree=False, roots=False)
    for x in range(24, 41, 4):
        m.px(x, 39, c('cyan', 2)); m.px(x + 1, 40, c('cyan', 2))
    return finish_sky(m, (32, 34, 30))


def k3_crowned_face():
    """#2's terminal face, crowned, rising above the clouds: the ruler of the sky."""
    m = Layer(64, 64)
    cm = lambda i: c('cmd', i)
    x0, y0, n = 16, 12, 32
    m.rect(x0, y0, x0 + n - 1, y0 + 27, cm(3)); m.rect(x0, y0, x0 + n - 1, y0 + 1, cm(4)); m.rect(x0, y0, x0 + 1, y0 + 27, cm(4))
    m.rect(x0 + n - 3, y0 + 2, x0 + n - 1, y0 + 27, cm(2))
    m.rect(x0 + 4, y0 + 5, x0 + n - 5, y0 + 22, c('steel', 0)); m.rect(x0 + 4, y0 + 5, x0 + n - 5, y0 + 5, c('steel', 2))
    eye(m, x0 + 8, y0 + 9, 5, 6); eye(m, x0 + n - 13, y0 + 9, 5, 6)
    arrow_mouth(m, x0 + 14, y0 + 15, cm(3))
    g = lambda i: c('gold', i)
    m.rect(14, 4, 49, 11, g(3)); m.rect(14, 4, 49, 5, g(4)); m.rect(14, 11, 49, 11, g(1))
    for px in (14, 30, 46):
        m.rect(px, 0, px + 3, 4, g(4)); m.px(px + 3, 1, g(3))
    m.rect(30, 6, 33, 9, c('cyan', 3))
    cloud(m, [(0, 34, 63, 46), (6, 31, 20, 33), (42, 30, 58, 33), (8, 47, 56, 52)])
    return finish_sky(m)


def k4_cloud_strings_island():
    """A cloud with the agent's eyes works a floating island on strings."""
    m = Layer(64, 64)
    cloud(m, [(2, 4, 61, 18), (8, 1, 26, 3), (34, 2, 54, 3)])
    eye(m, 20, 8, 6, 5); eye(m, 38, 8, 6, 5)
    island(m, 32, 36, 8, sb=8, taper=2, tree=True)
    for (x, y) in [(18, 44), (32, 40), (46, 44)]:
        m.line(x, 19, x, y, c('cyan', 3)); m.px(x, y, c('cyan', 4))
    return finish_sky(m, (32, 40, 26))


R6 = {
    'cloud-face': (k1_cloud_face, 'Cloud face: the cloud itself is the agent, with the terminal face\'s eyes and arrow mouth, looking down.'),
    'eyes-in-sky': (k2_eyes_in_sky, 'Eyes in the sky: a dark gap between clouds, like a screen in the sky, with eyes looking down on a small island.'),
    'crowned-face': (k3_crowned_face, 'Crowned terminal face: #2\'s face with a crown, rising above the clouds, the ruler of the sky.'),
    'cloud-strings': (k4_cloud_strings_island, 'Cloud on strings: a cloud with the agent\'s eyes works a floating island on strings.'),
}


def k5_visor_cloud_strings():
    """Merge: the visor cloud (the agent, with #2's eyes) works a floating island on strings."""
    m = Layer(64, 64)
    # cloud with more volume: a back row of puffs one tone darker, then the front body
    cloud(m, [(6, 1, 22, 6), (30, 0, 50, 5), (0, 6, 63, 19), (4, 20, 59, 22)])
    m.rect(11, 8, 52, 17, c('steel', 0)); m.rect(11, 8, 52, 8, c('steel', 1))
    m.rect(10, 9, 10, 16, c('cloud', 1)); m.rect(53, 9, 53, 16, c('cloud', 2))
    eye(m, 17, 10, 7, 5); eye(m, 40, 10, 7, 5)
    for x, y in [(17, 23), (32, 23), (46, 23)]:
        m.px(x, y, c('cyan', 4))
    island(m, 32, 35, 8, sb=7, taper=2, tree=False)
    for (x, y) in [(17, 43), (32, 38), (46, 43)]:
        m.line(x, 24, x, y, c('cyan', 3))
        m.px(x, y, c('cyan', 4)); m.px(x - 1, y, c('cyan', 2)); m.px(x + 1, y, c('cyan', 2))
    return finish_sky(m, (32, 40, 26))


R6['visor-cloud-strings'] = (k5_visor_cloud_strings, 'Visor cloud on strings: the cloud wears the terminal face\'s visor and eyes, and works a floating island on strings.')
