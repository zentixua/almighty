"""Round-two craft layer: hue-shifted ramps, selective outlines, corner anti-aliasing.

Ramps run dark -> light; darks lean toward violet, lights toward warm or cyan, so shading changes hue as well
as value. Only the outer silhouette gets the ink outline; inner edges are drawn by hand in the darker step of the
material in front ("colored inner lines").
"""
from pix import Layer, hexc, T

INK = hexc('#0E0B16')
R = {
    'steel': [hexc(h) for h in ('#1F1A33', '#363856', '#545E80', '#8094B0', '#C6DAE4')],
    'gold':  [hexc(h) for h in ('#4A1E2C', '#8C3B2E', '#C9692C', '#EDA640', '#FFE69C')],
    'moss':  [hexc(h) for h in ('#163238', '#235A45', '#3F8A45', '#76B84A', '#C6E46E')],
    'soil':  [hexc(h) for h in ('#2A1828', '#4A2A33', '#704338', '#9A6640', '#C3925C')],
    'stone': [hexc(h) for h in ('#1E1B2D', '#343049', '#4F4A65', '#736C86', '#A39BAF')],
    'cyan':  [hexc(h) for h in ('#123349', '#1C6676', '#2FB0A6', '#7BEBCF', '#E2FFF3')],
    'night': [hexc(h) for h in ('#100D1A', '#16132A', '#1D1A36', '#262445', '#353457')],
}


def c(mat, i):
    return R[mat][i]


def mix(a, b, t=0.5):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(3)) + (255,)


def outline_outer(L, ink=INK):
    """Ink on every transparent pixel touching the silhouette (4-neighbourhood)."""
    return L.outline(ink)


def aa_corners(L, ink=INK, bg=None, strength=0.5):
    """Soften stair-steps of the outline.

    Inside: a fill pixel touching ink on two orthogonal sides (a concave step) is pulled toward the ink.
    Outside (only when bg is given, i.e. on a flat tile): a background pixel in the elbow of two ink pixels is
    pulled toward the ink. Applied once; it never chains.
    """
    src = L.im.copy()
    w, h = L.w, L.h

    def g(x, y):
        return src.getpixel((x, y)) if 0 <= x < w and 0 <= y < h else T

    def isink(p):
        return p[:3] == ink[:3] and p[3] == 255

    for y in range(h):
        for x in range(w):
            p = g(x, y)
            n = [g(x + 1, y), g(x - 1, y), g(x, y + 1), g(x, y - 1)]
            horiz = isink(n[0]) or isink(n[1])
            vert = isink(n[2]) or isink(n[3])
            if p[3] == 255 and not isink(p) and horiz and vert and sum(isink(q) for q in n) == 2:
                L.px(x, y, mix(p, ink, strength * 0.6))
            elif bg is not None and p[:3] == bg[:3] and horiz and vert and sum(isink(q) for q in n) == 2:
                L.px(x, y, mix(bg, ink, strength))
    return L


def finish(m, bg=None):
    outline_outer(m)
    if bg is not None:
        t = Layer(m.w, m.h, bg)
        t.paste(m, 0, 0)
        aa_corners(t, bg=bg)
        return t
    aa_corners(m)
    return m
