"""Render the whole brand kit: python3 scripts/brand/build.py (needs Pillow). Output: docs/brand/."""
import os
from PIL import Image
from pix import Layer, hexc, up
from fonts import wordmark, draw_text, text_width, SMALL
from marks import mark, INK, REGENT, GRASP, SOVEREIGN, PROMPT

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')
TAGLINE = 'INFINITE POWER. CONTROLLED USE.'
SUB = 'AN AI GAME MASTER FOR YOUR SERVER'

DIRS = {
    'grasp': GRASP, 'sovereign': SOVEREIGN,  # regent and prompt were dropped (docs/brand/blind-check.md)
}
ACCENT = {'regent': hexc('#E9AE36'), 'grasp': hexc('#43D9C2'), 'sovereign': hexc('#E9AE36'), 'prompt': hexc('#43D9C2')}
FACE, SHADE, MUTED = hexc('#ECEFF4'), hexc('#9AA3B5'), hexc('#AEB6C6')
HILLS = [3, 3, 5, 5, 5, 4, 4, 7, 7, 7, 7, 6, 4, 4, 3, 3, 3, 5, 6, 6, 8, 8, 6, 5, 5, 4, 3, 3, 4, 4, 6, 6, 5, 3]


def scene(w, h, pal):
    """Background: flat night tone with a sparse 8-px dot grid (a build grid, not texture)."""
    L = Layer(w, h, pal['bg'])
    dot = lighten(pal['bg'], 9)
    for y in range(4, h, 8):
        for x in range(4, w, 8):
            L.px(x, y, dot)
    return L


def lighten(c, d):
    return tuple(min(255, v + d) for v in c[:3]) + (255,)


def word():
    return wordmark(FACE, INK, SHADE)


def lockup_h(name, tag=True, sub=False):
    """Mark left, wordmark + tagline right. Returns a transparent layer."""
    m = mark(name, False)
    wm = word()
    lines = []
    if tag:
        lines.append((TAGLINE, ACCENT[name]))
    if sub:
        lines.append((SUB, MUTED))
    tw = max([wm.w] + [text_width(t) for t, _ in lines])
    w = 64 + 8 + tw
    L = Layer(w, 64)
    L.paste(m, 0, 0)
    th = wm.h + sum(10 for _ in lines)
    y = (64 - th) // 2
    L.paste(wm, 72 - 1, y)
    y += wm.h + 3
    for t, c in lines:
        draw_text(L, t, 72, y, c)
        y += 10
    return L


def lockup_v(name, tag=True):
    m = mark(name, False)
    wm = word()
    tw = max(wm.w, text_width(TAGLINE) if tag else 0)
    L = Layer(tw, 64 + 2 + wm.h + (11 if tag else 0))
    L.paste(m, (tw - 64) // 2, 0)
    L.paste(wm, (tw - wm.w) // 2, 66)
    if tag:
        draw_text(L, TAGLINE, (tw - text_width(TAGLINE)) // 2, 66 + wm.h + 3, ACCENT[name])
    return L


def place(bg, item, cx=None, cy=None, x=None, y=None):
    x = x if x is not None else (bg.w - item.w) // 2 if cx is None else cx - item.w // 2
    y = y if y is not None else (bg.h - item.h) // 2 if cy is None else cy - item.h // 2
    bg.paste(item, x, y)


def save(im, path, size):
    im = im.im if isinstance(im, Layer) else im
    assert im.size == size, (path, im.size, size)
    im.save(path, optimize=True)


def build(name):
    pal = DIRS[name]
    out = os.path.join(ROOT, name)
    os.makedirs(out, exist_ok=True)
    icon = mark(name)
    save(icon, f'{out}/icon-64.png', (64, 64))
    save(up(icon, 2), f'{out}/icon-128.png', (128, 128))
    save(up(icon, 8), f'{out}/icon-512.png', (512, 512))
    # Logo on transparency (works on dark and light: every shape has an ink edge).
    lg = lockup_h(name, tag=False)
    save(lg.im, f'{out}/logo-native.png', lg.im.size)
    save(up(lg, 4), f'{out}/logo.png', (lg.w * 4, 256))
    # Banner 1280x320 (320x80 x4).
    b = scene(320, 80, pal); place(b, lockup_h(name, sub=True))
    save(up(b, 4), f'{out}/banner-1280x320.png', (1280, 320))
    # Modrinth listing banner 1920x480 (320x80 x6) and CurseForge header 1600x400 (320x80 x5).
    save(up(b, 6), f'{out}/modrinth-banner-1920x480.png', (1920, 480))
    save(up(b, 5), f'{out}/curseforge-header-1600x400.png', (1600, 400))
    # Modrinth gallery cover 1920x1080 (240x135 x8).
    c = scene(240, 135, pal); place(c, lockup_v(name), cy=60)
    save(up(c, 8), f'{out}/modrinth-cover-1920x1080.png', (1920, 1080))
    # GitHub social preview 1280x640 (256x128 x5).
    g = scene(256, 128, pal); place(g, lockup_v(name), cy=57)
    save(up(g, 5), f'{out}/github-social-1280x640.png', (1280, 640))
    # Avatar 400x400 (80x80 x5): the icon tile, extended.
    a = Layer(80, 80, pal['bg']); a.rect(0, 58, 79, 79, pal['bg2']); a.paste(mark(name, False), 8, 8)
    save(up(a, 5), f'{out}/avatar-400.png', (400, 400))
    # X header 1500x500 (300x100 x5): the profile picture covers the lower left, so the lockup sits right of centre.
    x = scene(300, 100, pal); place(x, lockup_h(name), x=300 - 14 - lockup_h(name).w, cy=44)
    save(up(x, 5), f'{out}/x-header-1500x500.png', (1500, 500))
    # YouTube channel art 2560x1440 (512x288 x5); everything inside the 1546x423 safe area (309x84 native).
    yt = scene(512, 288, pal); lk = lockup_h(name); assert lk.w <= 309 and lk.h <= 84; place(yt, lk)
    save(up(yt, 5), f'{out}/youtube-2560x1440.png', (2560, 1440))
    # Thumbnails 1280x720 (160x90 x8).
    t = scene(160, 90, pal); t.paste(mark(name, False), 6, 10)
    lines = ['AI GAME', 'MASTER']
    for i, ln in enumerate(lines):
        draw_text(t, ln, 76, 22 + i * 11, FACE, bold=True)
    draw_text(t, 'ALMIGHTY', 76, 50, ACCENT[name])
    save(up(t, 8), f'{out}/thumbnail-title-1280x720.png', (1280, 720))
    t2 = scene(160, 90, pal); t2.paste(mark(name, False), 48, 8)
    save(up(t2, 8), f'{out}/thumbnail-plain-1280x720.png', (1280, 720))
    return {'icon': icon, 'banner': b, 'thumb': t}


def overview(sets):
    W = 8 + 32 + 8 + 64 + 8 + 128 + 12 + 640 + 12 + 320 + 8
    rowh = 192
    H = 30 + rowh * len(sets) + 8
    L = Layer(W, H, hexc('#0C0D12'))
    sheet = L.im
    d = Layer(W, 30, hexc('#0C0D12'))
    draw_text(d, 'ALMIGHTY BRAND KIT - DIRECTIONS AT REAL USE SIZES', 8, 6, FACE)
    draw_text(d, 'ICON 32 / 64 / 128 . BANNER 1280X320 AT 1/2 . THUMBNAIL 1280X720 AT 1/4', 8, 17, MUTED)
    sheet.alpha_composite(d.im, (0, 0))
    for i, (name, s) in enumerate(sets.items()):
        y = 30 + i * rowh
        lab = Layer(200, 9, hexc('#0C0D12'))
        draw_text(lab, name.upper(), 0, 1, ACCENT[name])
        sheet.alpha_composite(lab.im, (8, y + 4))
        y += 16
        x = 8
        sheet.alpha_composite(s['icon'].im.resize((32, 32), Image.BOX), (x, y)); x += 40
        sheet.alpha_composite(s['icon'].im, (x, y)); x += 72
        sheet.alpha_composite(up(s['icon'], 2), (x, y)); x += 140
        sheet.alpha_composite(up(s['banner'], 2), (x, y)); x += 652
        sheet.alpha_composite(up(s['thumb'], 2), (x, y))
    sheet.save(os.path.join(ROOT, 'overview.png'), optimize=True)


if __name__ == '__main__':
    import sys
    names = sys.argv[1:] or list(DIRS)
    overview({n: build(n) for n in names})
