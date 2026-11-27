"""Round six: 'almighty from above' at 32 px. Square clouds in the vanilla manner (our drawing)."""
from pix import Layer, hexc, T
from art import R, c, mix, INK
import concepts32
from concepts32 import cb, lead

R['cloud'] = [hexc(h) for h in ('#4B4E7A', '#7B80A8', '#B6BCD6', '#E4E9F4', '#FFFFFF')]
SKY = hexc('#2C4F8C')


def cloud(m, boxes):
    """A square cloud: union of boxes; lit top rows, violet-grey underside."""
    cells = set()
    for (x0, y0, x1, y1) in boxes:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                cells.add((x, y))
    for (x, y) in cells:
        up_open = (x, y - 1) not in cells
        down_open = (x, y + 1) not in cells
        down2 = (x, y + 2) not in cells
        col = c('cloud', 3)
        if up_open: col = c('cloud', 4)
        elif down_open: col = c('cloud', 1)
        elif down2: col = c('cloud', 2)
        m.px(x, y, col)
    return cells


def term_face(m, x, y, w=14, h=10):
    """#2's face, small: orange frame, dark screen, cyan eyes, arrow mouth."""
    m.rect(x, y, x + w - 1, y + h - 1, c('cmd', 3)); m.rect(x, y, x + w - 1, y, c('cmd', 4))
    m.rect(x + 1, y + 1, x + w - 2, y + h - 2, c('steel', 0))
    m.rect(x + 3, y + 3, x + 4, y + 4, c('cyan', 4)); m.rect(x + w - 5, y + 3, x + w - 4, y + 4, c('cyan', 4))
    m.px(x + w // 2 - 1, y + h - 3, c('cmd', 3)); m.px(x + w // 2, y + h - 4 + 2, c('cmd', 3))


def tiny_player(m, cx, top):
    m.rect(cx - 2, top, cx + 1, top + 3, c('skin', 3)); m.rect(cx - 2, top, cx + 1, top, c('night', 4))
    m.rect(cx - 2, top + 4, cx + 1, top + 8, c('gold', 2)); m.px(cx - 3, top + 4, c('gold', 2)); m.px(cx + 2, top + 4, c('gold', 2))
    m.px(cx - 4, top + 3, c('skin', 3)); m.px(cx + 3, top + 3, c('skin', 3))
    m.rect(cx - 2, top + 9, cx - 1, top + 12, c('night', 4)); m.rect(cx, top + 9, cx + 1, top + 12, c('night', 3))
    return [(cx - 4, top + 3), (cx, top - 1), (cx + 3, top + 3)]


def strings(m, pts, y0):
    for (x, y) in pts:
        m.line(x, y0, x, y, c('cyan', 3))


def tiny_block(m, cx, ty):
    for r in range(6):
        hw = 2 * (r + 1) if r < 3 else 2 * (6 - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c('moss', 4 if r == 0 else 3))
    for u in range(6):
        y0 = ty + 3 + u // 2 + 1
        for v in range(5):
            m.px(cx - 6 + u, y0 + v, c('moss', 2) if v < 1 else c('soil', 3))
            m.px(cx + 5 - u, y0 + v, c('moss', 1) if v < 1 else c('soil', 2))


BIG_CLOUD = [(2, 4, 29, 9), (5, 1, 14, 3), (16, 2, 26, 3), (0, 6, 31, 8)]
C = {}


def concept(name, idea):
    def deco(f):
        C[name] = (f, idea); return f
    return deco


@concept('face_in_clouds', 'The terminal face of #2 looking down through a gap in square clouds.')
def _(m):
    term_face(m, 9, 9, 14, 10)
    cloud(m, [(0, 14, 12, 19), (2, 12, 9, 13), (19, 14, 31, 19), (22, 12, 29, 13), (6, 20, 25, 23)])


@concept('cloud_strings_player', 'A square cloud works a player on strings: power from above.')
def _(m):
    cloud(m, BIG_CLOUD)
    a = tiny_player(m, 16, 18); strings(m, a, 10)


@concept('face_cloud_strings_player', 'The terminal face in the clouds works a player on strings.')
def _(m):
    term_face(m, 9, 1, 14, 9)
    cloud(m, [(0, 7, 31, 11), (3, 5, 8, 6), (24, 5, 29, 6)])
    a = tiny_player(m, 16, 19); strings(m, a, 12)


@concept('cloud_strings_block', 'A cloud works a floating block on strings.')
def _(m):
    cloud(m, BIG_CLOUD)
    tiny_block(m, 16, 18)
    strings(m, [(11, 21), (16, 18), (21, 21)], 10)


@concept('hand_from_clouds', 'A blocky hand reaches down out of the clouds, strings to a player.')
def _(m):
    m.rect(12, 0, 19, 12, c('skin', 3)); m.rect(12, 0, 13, 12, c('skin', 4))
    for x in (11, 14, 17, 20):
        m.rect(x, 12, x + 1, 15, c('skin', 3))
    cloud(m, [(0, 4, 31, 8), (3, 2, 10, 3), (22, 1, 29, 3)])
    a = tiny_player(m, 16, 20); strings(m, a, 16)


@concept('eyes_in_sky', 'Two glowing eyes in a dark gap between clouds: someone up there is watching.')
def _(m):
    cloud(m, [(0, 2, 31, 6), (4, 0, 12, 1)])
    m.rect(4, 7, 27, 16, c('night', 1))
    m.rect(8, 10, 11, 12, c('cyan', 4)); m.rect(20, 10, 23, 12, c('cyan', 4))
    cloud(m, [(0, 17, 31, 22), (6, 23, 25, 25)])


@concept('beam_from_cloud', 'A beam of light from a cloud onto a block: the hand of god in vanilla terms.')
def _(m):
    cloud(m, BIG_CLOUD)
    for y in range(10, 22):
        m.rect(14 - (y - 10) // 4, y, 17 + (y - 10) // 4, y, c('cyan', 2) if y % 2 else c('cyan', 3))
    tiny_block(m, 16, 20)


@concept('hand_places_block', 'A hand from the clouds places a block into the world.')
def _(m):
    m.rect(13, 0, 19, 13, c('skin', 3)); m.rect(13, 0, 14, 13, c('skin', 4))
    cloud(m, [(0, 4, 31, 8), (3, 2, 10, 3), (22, 1, 29, 3)])
    tiny_block(m, 16, 15)
    m.rect(0, 28, 31, 31, c('moss', 3)); m.rect(0, 28, 31, 28, c('moss', 4))


@concept('cb_in_clouds_strings', 'A command block sits in the clouds, working a player on strings.')
def _(m):
    cb(m, 16, 0, k=4, s=6)
    cloud(m, [(0, 8, 31, 12), (3, 6, 9, 7), (23, 6, 29, 7)])
    a = tiny_player(m, 16, 19); strings(m, a, 13)


@concept('crowned_face_clouds', 'The terminal face wears a crown above the clouds: the ruler of the sky.')
def _(m):
    concepts32.crown(m, 9, 0, 14)
    term_face(m, 9, 6, 14, 10)
    cloud(m, [(0, 14, 31, 19), (4, 12, 10, 13), (22, 12, 28, 13), (8, 20, 24, 23)])


@concept('cloud_face', 'The cloud itself is a face: two glowing eye holes and an arrow mouth.')
def _(m):
    cloud(m, [(1, 6, 30, 22), (5, 3, 14, 5), (17, 4, 26, 5), (4, 23, 27, 25)])
    m.rect(8, 11, 11, 14, c('cyan', 4)); m.rect(20, 11, 23, 14, c('cyan', 4))
    m.rect(8, 15, 11, 15, c('cyan', 2)); m.rect(20, 15, 23, 15, c('cyan', 2))
    m.px(15, 17, c('cloud', 1)); m.px(16, 18, c('cloud', 1)); m.px(15, 19, c('cloud', 1))


@concept('cloud_face_strings', 'A cloud with a face works a player on strings.')
def _(m):
    cloud(m, [(1, 2, 30, 12), (5, 0, 14, 1), (17, 1, 26, 1)])
    m.rect(9, 5, 11, 7, c('cyan', 4)); m.rect(20, 5, 22, 7, c('cyan', 4))
    a = tiny_player(m, 16, 19); strings(m, a, 13)


@concept('cursor_from_cloud', 'A cursor arrow points down out of the clouds at a player.')
def _(m):
    cloud(m, [(0, 3, 31, 8), (4, 1, 11, 2), (21, 0, 28, 2)])
    arrow = ["44444", ".433.", "..4..",]
    for i in range(6):
        m.rect(14 - (5 - i) // 2, 9 + i, 17 + (5 - i) // 2, 9 + i, c('steel', 4) if i < 5 else c('steel', 3))
    tiny_player(m, 16, 18)


def render(name):
    f, _ = C[name]
    m = Layer(32, 32)
    f(m)
    m.outline(INK)
    t = Layer(32, 32, SKY)
    t.paste(m, 0, 0)
    return t
