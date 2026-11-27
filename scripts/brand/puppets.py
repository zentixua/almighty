"""Round three: Puppeteer variants. python3 scripts/brand/puppets.py -> docs/brand/puppeteer.png + puppeteer/NN-*.png"""
import os, textwrap
from PIL import Image
from pix import Layer, hexc, up, T
from art import c, mix, finish, INK
from marks2 import island, hand2, TILE, cube
from fonts import draw_text, SMALL

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')


def player(m, cx, top, arms_up=True):
    """Small blocky figure on strings: 8 px wide, 19 tall."""
    skin, shirt, legs = c('soil', 4), c('steel', 3), c('night', 4)
    m.rect(cx - 3, top, cx + 2, top + 5, skin)
    m.rect(cx - 3, top, cx + 2, top, c('soil', 3))
    m.rect(cx + 2, top + 1, cx + 2, top + 5, c('soil', 3))
    m.px(cx - 2, top + 3, INK); m.px(cx + 1, top + 3, INK)
    m.rect(cx - 3, top + 6, cx + 2, top + 12, shirt)
    m.rect(cx + 1, top + 6, cx + 2, top + 12, c('steel', 2))
    for i in range(5):                                       # arms lifted by the strings
        m.px(cx - 4 - i // 2, top + 6 + (0 if arms_up else i) - (i if arms_up else 0) // 2, shirt)
        m.px(cx - 5 - i // 2, top + 6 - i // 2, shirt)
        m.px(cx + 3 + i // 2, top + 6 - i // 2, c('steel', 2))
        m.px(cx + 4 + i // 2, top + 6 - i // 2, c('steel', 2))
    m.rect(cx - 3, top + 13, cx - 1, top + 18, legs)
    m.rect(cx, top + 13, cx + 2, top + 18, c('night', 3))
    return [(cx - 7, top + 4), (cx, top - 1), (cx + 6, top + 4)]   # hand, head, hand anchors


def string(m, x0, y0, x1, y1, col=None):
    m.line(x0, y0, x1, y1, col or c('cyan', 3))


def crossbar(m, cx, y, half=12):
    """Marionette control: a horizontal bar with a short cross piece."""
    m.rect(cx - half, y, cx + half, y + 1, c('soil', 3))
    m.rect(cx - half, y + 1, cx + half, y + 1, c('soil', 2))
    m.rect(cx - 1, y - 3, cx, y + 4, c('soil', 3))
    m.px(cx, y - 3, c('soil', 4))
    return [(cx - half, y + 2), (cx, y + 5), (cx + half, y + 2)]


# 1 ------------------------------------------------------------ cursor arrow holding the control bar
def v_cursor():
    m = Layer(64, 64)
    arrow = [
        "4.........",
        "44........",
        "434.......",
        "4334......",
        "43334.....",
        "433334....",
        "4333334...",
        "43333334..",
        "433333334.",
        "4333332222",
        "433223....",
        "432.223...",
        "42..223...",
        "4....223..",
        ".....223..",
        "......22..",
    ]
    # bar first, the arrow's tip rests on it
    ends = crossbar(m, 32, 22, 14)
    m.sprite(arrow, 31, 6, {'4': c('steel', 4), '3': hexc('#E9F2F6'), '2': c('steel', 3)})
    anchors = player(m, 32, 38)
    string(m, ends[0][0], ends[0][1], anchors[0][0], anchors[0][1])
    string(m, ends[1][0], ends[1][1], anchors[1][0], anchors[1][1])
    string(m, ends[2][0], ends[2][1], anchors[2][0], anchors[2][1])
    m.rect(22, 59, 41, 60, c('stone', 2))                     # the stage the puppet stands above
    m.rect(22, 59, 41, 59, c('stone', 3))
    return finish(m, TILE)


# 2 ------------------------------------------------------------ chat bubble as the control bar
def v_bubble():
    m = Layer(64, 64)
    b = c('steel', 4)
    m.rect(12, 4, 51, 19, b)
    m.rect(11, 6, 52, 17, b)
    m.rect(12, 18, 51, 19, c('steel', 3))
    m.rect(51, 6, 52, 17, c('steel', 3))
    m.rect(18, 20, 22, 20, c('steel', 3)); m.rect(18, 21, 20, 21, c('steel', 3)); m.px(18, 22, c('steel', 3))
    for x in (21, 30, 39):                                     # "typing" dots
        m.rect(x, 10, x + 3, 13, c('cyan', 2))
        m.rect(x, 10, x + 1, 11, c('cyan', 3))
    island(m, 32, 38, 7, sb=8, taper=2, tree=True)
    for x0, x1, y1 in [(16, 20, 46), (28, 28, 51), (36, 36, 51), (47, 44, 47)]:
        string(m, x0, 20, x1, y1)
        m.px(x1, y1, c('cyan', 4))
    return finish(m, TILE)


# 3 ------------------------------------------------------------ holographic grid hand, cross bar, player
def gridify(src, x0=0, y0=0):
    """Turn a drawn shape into light: cyan cells on a 3-px grid, brighter on the lit side."""
    out = Layer(src.w, src.h)
    for y in range(src.h):
        for x in range(src.w):
            p = src.get(x, y)
            if not p[3]:
                continue
            if (x + x0) % 3 == 2 or (y + y0) % 3 == 2:
                out.px(x, y, c('cyan', 1))
            else:
                lum = sum(p[:3]) / 3
                out.px(x, y, c('cyan', 4 if lum > 150 else (3 if lum > 100 else 2)))
    return out


def v_hologram():
    m = Layer(64, 64)
    h = Layer(64, 64)
    hand2(h, 0, 0, emblem=False)
    g = gridify(h)
    m.paste(g, 0, 0)
    ends = crossbar(m, 32, 28, 15)
    anchors = player(m, 32, 41)
    for e, a in zip(ends, anchors):
        string(m, e[0], e[1], a[0], a[1])
    return finish(m, TILE)


# 4 ------------------------------------------------------------ strings that form a network
def v_network():
    m = Layer(64, 64)
    island(m, 32, 44, 6, sb=6, taper=2, tree=False)
    tips = hand2(m, 0, -2, emblem=False)
    nodes = [(16, 31), (26, 33), (38, 32), (47, 30), (21, 40), (32, 39), (43, 40)]
    links = [(0, 4), (1, 4), (1, 5), (2, 5), (2, 6), (3, 6), (4, 5), (5, 6)]
    for i, t in enumerate(tips):
        string(m, t[0], t[1], nodes[i][0], nodes[i][1])
    for a, b in links:
        string(m, *nodes[a], *nodes[b], c('cyan', 2))
    for a, (tx, ty) in zip([4, 5, 6], [(24, 50), (32, 52), (40, 50)]):
        string(m, *nodes[a], tx, ty, c('cyan', 2))
    for (x, y) in nodes:
        m.rect(x - 1, y - 1, x + 1, y + 1, c('cyan', 3))
        m.px(x - 1, y - 1, c('cyan', 4))
    return finish(m, TILE)


# 5 ------------------------------------------------------------ a hand reaching out of a screen, block on strings
def v_screen():
    m = Layer(64, 64)
    # monitor frame and screen
    m.rect(10, 0, 53, 15, c('steel', 2))
    m.rect(10, 0, 53, 0, c('steel', 3))
    m.rect(12, 1, 51, 13, c('cyan', 0))
    for y in range(2, 13, 2):
        m.rect(12, y, 51, y, c('night', 3))
    m.rect(10, 14, 53, 15, c('steel', 1))
    h = Layer(64, 64)
    hand2(h, 0, 5, emblem=False)
    for y in range(0, 12):                                   # the wrist is still inside the glass
        for x in range(64):
            if h.get(x, y)[3]:
                h.px(x, y, mix(h.get(x, y), c('cyan', 2), 0.55))
    m.paste(h, 0, 0)
    # a block with cut layers
    cx, ty, k = 32, 42, 6
    cube(m, cx, ty, k, 8, 'stone')
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c('moss', 4 if r < 2 or x <= -hw + 1 and r < k else 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(3):
            m.px(cx - 2 * k + u, y0 + v, c('moss', 2) if v < 2 else c('moss', 1))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('moss', 1) if v < 2 else c('moss', 0))
    for i, (tx, ty2) in enumerate([(24, 48), (29, 50), (35, 50), (40, 48)]):
        x, y = [(21, 35), (27, 37), (33, 36), (39, 33)][i]
        string(m, x, y, tx, ty2)
        m.px(tx, ty2, c('cyan', 4))
    return finish(m, TILE)


VARIANTS = [
    (v_cursor, 'cursor', 'Cursor: the computer\'s own arrow pointer holds the marionette bar and works a small player.'),
    (v_bubble, 'bubble', 'Chat bubble: a typing chat bubble is the control bar; its strings work a floating island.'),
    (v_hologram, 'hologram', 'Hologram hand: a hand made of light on a pixel grid holds the cross bar over a player.'),
    (v_network, 'network', 'Network strings: the steel hand\'s strings branch into a node network that holds the island.'),
    (v_screen, 'screen', 'Screen hand: a hand reaches out of a screen and works a cut-layer block on strings.'),
]


def caption(i, text):
    lines = textwrap.wrap(text.upper().replace("'", "'"), 40)
    L = Layer(256, 14 + 10 * len(lines), hexc('#0C0B14'))
    draw_text(L, f'{i}', 0, 0, c('cyan', 3), bold=True)
    for j, ln in enumerate(lines):
        ln = ''.join(ch if ch in SMALL or ch == ' ' else '-' for ch in ln)
        draw_text(L, ln, 0, 12 + j * 10, hexc('#ECEFF4') if j == 0 else hexc('#AEB6C6'))
    return L


def main():
    out = os.path.join(ROOT, 'puppeteer')
    os.makedirs(out, exist_ok=True)
    bg = hexc('#0C0B14')
    cw, pad, cols = 512, 32, 3
    icons = [(f(), n, idea) for f, n, idea in VARIANTS]
    caps = [caption(i + 1, idea) for i, (_, _, idea) in enumerate(icons)]
    capH = max(cp.h for cp in caps) * 2
    rows = (len(icons) + cols - 1) // cols
    cellw = cw + 16 + 64
    W = pad + cols * (cellw + pad)
    H = 80 + rows * (cw + 16 + capH + pad)
    sheet = Image.new('RGBA', (W, H), bg)
    head = Layer(W // 2, 30, bg)
    draw_text(head, 'ALMIGHTY - PUPPETEER VARIANTS', 0, 4, hexc('#ECEFF4'), bold=True)
    draw_text(head, 'NATIVE 64X64 AT X8, AND THE SAME ICON AT 32 PX ON THE RIGHT.', 0, 16, hexc('#AEB6C6'))
    sheet.alpha_composite(up(head, 2), (pad, 8))
    for i, (icon, n, idea) in enumerate(icons):
        icon.im.save(f'{out}/{i + 1:02d}-{n}-64.png')
        up(icon, 8).save(f'{out}/{i + 1:02d}-{n}-512.png')
        small = icon.im.resize((32, 32), Image.BOX)
        small.save(f'{out}/{i + 1:02d}-{n}-32.png')
        x = pad + (i % cols) * (cellw + pad)
        y = 80 + (i // cols) * (cw + 16 + capH + pad)
        sheet.alpha_composite(up(icon, 8), (x, y))
        sheet.alpha_composite(small, (x + cw + 16, y))
        sheet.alpha_composite(up(caps[i], 2), (x, y + cw + 16))
    sheet.save(os.path.join(ROOT, 'puppeteer.png'), optimize=True)


if __name__ == '__main__':
    main()
