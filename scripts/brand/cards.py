"""Mock mod-list cards (in the style of a dark store list), for blind tests and the finalists sheet.

The card frame and text use a system UI font on purpose: that is how a store shows a mod. Only the icon is ours.
"""
from PIL import Image, ImageDraw, ImageFont
from pix import Layer, hexc
from art import c, finish

F = '/usr/share/fonts/truetype/dejavu/'
BG, CARD, TXT, SUB, TAG = (22, 24, 28), (38, 41, 47), (236, 239, 244), (170, 176, 188), (60, 64, 72)
W, H = 760, 108


def font(size, bold=False):
    return ImageFont.truetype(F + ('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf'), size)


def wrap(d, text, f, width):
    words, lines, cur = text.split(), [], ''
    for w in words:
        t = (cur + ' ' + w).strip()
        if d.textlength(t, font=f) <= width:
            cur = t
        else:
            lines.append(cur); cur = w
    lines.append(cur)
    return lines


def card(icon_im, title, summary, tags, downloads):
    im = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, W - 1, H - 1], 10, fill=CARD)
    ic = icon_im.convert('RGBA').resize((80, 80), Image.NEAREST if icon_im.width <= 80 else Image.BOX)
    mask = Image.new('L', (80, 80), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 79, 79], 12, fill=255)
    im.paste(ic, (14, 14), mask)
    d.text((110, 12), title, font=font(19, True), fill=TXT)
    tw = d.textlength(title, font=font(19, True))
    d.text((110 + tw + 10, 17), downloads, font=font(12), fill=SUB)
    for j, ln in enumerate(wrap(d, summary, font(14), W - 130)[:2]):
        d.text((110, 40 + j * 19), ln, font=font(14), fill=SUB)
    x = 110
    for t in tags:
        w = d.textlength(t, font=font(11)) + 14
        d.rounded_rectangle([x, 82, x + w, 99], 8, fill=TAG)
        d.text((x + 7, 84), t, font=font(11), fill=SUB)
        x += w + 6
    return im


def filler_icon(kind):
    """Plain placeholder icons for the other cards (simple pixel objects, deliberately ordinary)."""
    m = Layer(32, 32)
    if kind == 'lantern':
        m.rect(13, 6, 18, 7, c('steel', 2)); m.rect(11, 8, 20, 24, c('steel', 2)); m.rect(13, 10, 18, 22, c('gold', 4))
        m.rect(13, 10, 14, 22, hexc('#FFF4C8'))
    elif kind == 'pickaxe':
        for i in range(16):
            m.rect(8 + i, 24 - i, 9 + i, 25 - i, c('soil', 3))
        m.rect(6, 6, 24, 9, c('steel', 4)); m.rect(4, 8, 7, 12, c('steel', 3)); m.rect(23, 8, 26, 12, c('steel', 3))
    elif kind == 'chest':
        m.rect(6, 10, 25, 25, c('soil', 3)); m.rect(6, 10, 25, 15, c('soil', 4)); m.rect(14, 14, 17, 18, c('steel', 4))
    elif kind == 'leaf':
        m.rect(10, 8, 21, 23, c('moss', 3)); m.rect(12, 6, 19, 25, c('moss', 3)); m.rect(12, 9, 16, 18, c('moss', 4))
    m.outline(hexc('#0E0B16'))
    t = Layer(32, 32, {'lantern': hexc('#2B2A3A'), 'pickaxe': hexc('#33424A'), 'chest': hexc('#3B2F2A'),
                       'leaf': hexc('#22382C')}[kind])
    t.paste(m, 0, 0)
    return t.im.resize((64, 64), Image.NEAREST)


FILLERS = [
    ('Glow Lanterns Extra', 'Adds hanging and wall lanterns in 16 colors, with soft light levels.', ['Decoration'], '412k downloads', 'lantern'),
    ('Vein Breaker', 'Mine a whole ore vein at once while sneaking. Configurable limits.', ['Utility', 'Game Mechanics'], '1.3M downloads', 'pickaxe'),
    ('Sorted Storage', 'Sort and search your chests with one key. Works on servers.', ['Storage', 'Utility'], '890k downloads', 'chest'),
    ('Leafy Trees', 'Fuller, bushier leaves and falling-leaf particles for every tree.', ['Decoration', 'Worldgen'], '2.1M downloads', 'leaf'),
]
ALMIGHTY_TAGS = ['Server', 'Utility']


def feed(icon_im, summary, slot=2, n=4):
    """A list of n cards; Almighty sits at position slot among ordinary mods."""
    cards = []
    fillers = list(FILLERS)
    for i in range(n):
        if i == slot:
            cards.append(card(icon_im, 'Almighty', summary, ALMIGHTY_TAGS, 'new'))
        else:
            t, s, tg, dl, k = fillers.pop(0)
            cards.append(card(filler_icon(k), t, s, tg, dl))
    im = Image.new('RGB', (W + 40, n * (H + 12) + 28), BG)
    for i, cd in enumerate(cards):
        im.paste(cd, (20, 20 + i * (H + 12)))
    return im
