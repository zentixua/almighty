"""Round seven: the visor-cloud-on-strings mark with four face treatments (icon only).

Base fixes: the whole cloud sits inside the tile, the island is larger, the strings are a lit line plus a
shadow line so they hold at 32 px. The face is kept small and crisp: secondary to the silhouette."""
from pix import Layer, hexc, T
from art import c, mix, INK
from clouds32 import cloud
from marks2 import island
from round6 import finish_sky


def base(face):
    m = Layer(64, 64)
    cloud(m, [(9, 4, 24, 7), (30, 3, 47, 7), (4, 8, 59, 18), (8, 19, 55, 20)])
    face(m)
    island(m, 32, 29, 9, sb=6, taper=2, tree=False, roots=False)
    for (x, y) in [(16, 39), (32, 32), (48, 39)]:
        for yy in range(21, y):
            m.px(x, yy, c('cyan', 3)); m.px(x + 1, yy, c('cyan', 1))
        m.px(x, y, c('cyan', 4)); m.px(x + 1, y, c('cyan', 3))
    return finish_sky(m, (32, 34, 29))


def eye_px(m, x, y, w=3, h=2):
    m.rect(x, y, x + w - 1, y + h - 1, c('cyan', 4))
    m.rect(x, y + h - 1, x + w - 1, y + h - 1, c('cyan', 3))


def f_small_visor(m):
    """A small crisp visor: an 18x5 dark band, two 3x2 eyes."""
    m.rect(23, 11, 40, 15, c('steel', 0)); m.rect(23, 11, 40, 11, c('steel', 1))
    m.px(22, 12, c('cloud', 1)); m.px(41, 12, c('cloud', 2))
    eye_px(m, 26, 13); eye_px(m, 35, 13)


def f_two_eyes(m):
    """Just two small sharp eyes, each with a one-pixel dark rim (no band)."""
    for x in (24, 36):
        m.rect(x - 1, 11, x + 3, 15, c('steel', 0))
        m.rect(x, 12, x + 2, 14, c('cyan', 4)); m.rect(x, 14, x + 2, 14, c('cyan', 2))


def f_slit(m):
    """A slim visor slit: a two-pixel dark line across the cloud, the eyes lit segments inside it."""
    m.rect(14, 13, 49, 14, c('steel', 0))
    m.rect(24, 13, 28, 13, c('cyan', 4)); m.rect(24, 14, 28, 14, c('cyan', 2))
    m.rect(35, 13, 39, 13, c('cyan', 4)); m.rect(35, 14, 39, 14, c('cyan', 2))


def f_shading(m):
    """Eyes inside the cloud's underside shadow: two lit slits in the darker band, no extra shape."""
    m.rect(8, 16, 55, 18, c('cloud', 1))
    eye_px(m, 23, 16, 5, 2); eye_px(m, 36, 16, 5, 2)


FACES = [
    ('small-visor', f_small_visor, 'Small visor: a short, crisp dark visor with two small eyes.'),
    ('two-eyes', f_two_eyes, 'Two eyes: just two small sharp eyes in the cloud, nothing else.'),
    ('slit', f_slit, 'Slit: a slim two-pixel visor slit across the cloud, the eyes lit inside it.'),
    ('shading', f_shading, 'In the shading: the eyes are lit slits in the cloud\'s darker underside.'),
]
