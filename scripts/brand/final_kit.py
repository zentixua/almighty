"""Build the final Almighty brand package: python3 scripts/brand/final_kit.py (needs Pillow) -> docs/brand/.

Every image is a native pixel grid enlarged by a whole nearest-neighbour factor (noted per file in the guide)."""
import os
from PIL import Image
from pix import Layer, up
from fonts import draw_text, text_width
from final_mark import PAL, P, mark_layer, icon64, sky, outline_outside
from final_type import wordmark

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand')
TAG = 'AN AI GAME MASTER FOR YOUR SERVER'
TAG2 = ('AN AI GAME MASTER', 'FOR YOUR MINECRAFT SERVER')


def far_island(L, cx, ty, k, tone=2):
    """A distant island: sky-tinted, low contrast, the mark's 2:1 geometry with a short keel."""
    top, side_l, side_r = P('sky', min(4, tone + 2)), P('sky', min(4, tone + 1)), P('sky', tone)
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        L.rect(cx - hw, ty + r, cx + hw - 1, ty + r, top)
    body = max(2, k // 2)
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        v = 0
        while True:
            if v >= body and u < (v - body + 1) * 3:
                break
            L.px(cx - 2 * k + u, y0 + v, side_l)
            L.px(cx + 2 * k - 1 - u, y0 + v, side_r)
            v += 1


def scene(w, h, islands=()):
    L = sky(w, h)
    for (fx, fy, k, tone) in islands:
        far_island(L, int(w * fx), int(h * fy), k, tone)
    return L


def tagline(text, col=None):
    col = col or P('cloud', 2)
    L = Layer(text_width(text) + 2, 9)
    draw_text(L, text, 1, 1, col)
    return L


def lockup_h(tag=True):
    m, wm = mark_layer(), wordmark()
    tl = tagline(TAG) if tag else None
    tw = max(wm.w, tl.w if tl else 0)
    L = Layer(64 + 6 + tw, 64)
    L.paste(m, 0, 0)
    th = wm.h + (10 if tl else 0)
    y = (64 - th) // 2
    L.paste(wm, 66, y)
    if tl:
        L.paste(tl, 70, y + wm.h + 1)
    return L


def lockup_v(tag=True, two_lines=True):
    m, wm = mark_layer(), wordmark()
    lines = [tagline(t) for t in (TAG2 if two_lines else (TAG,))] if tag else []
    tw = max([wm.w, 64] + [l.w for l in lines])
    L = Layer(tw, 64 + 2 + wm.h + sum(l.h + 1 for l in lines))
    L.paste(m, (tw - 64) // 2, 0)
    L.paste(wm, (tw - wm.w) // 2, 66)
    y = 66 + wm.h + 1
    for l in lines:
        L.paste(l, (tw - l.w) // 2, y)
        y += l.h + 1
    return L


def place(bg, item, x=None, y=None):
    bg.paste(item, (bg.w - item.w) // 2 if x is None else x, (bg.h - item.h) // 2 if y is None else y)
    return bg


def save(layer_or_im, path, size=None):
    im = layer_or_im.im if isinstance(layer_or_im, Layer) else layer_or_im
    if size:
        assert im.size == size, (path, im.size, size)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)


def build():
    d = ROOT
    # ---- icons
    ic = icon64()
    save(ic, f'{d}/icon/icon-64.png', (64, 64))
    save(up(ic, 2), f'{d}/icon/icon-128.png', (128, 128))
    save(up(ic, 8), f'{d}/icon/icon-512.png', (512, 512))
    save(up(mark_layer(), 8), f'{d}/icon/mark-transparent-512.png', (512, 512))
    # ---- logos (transparent)
    lh, lv, wm = lockup_h(tag=False), lockup_v(tag=False), wordmark()
    save(lh, f'{d}/logo/logo-horizontal-native.png')
    save(up(lh, 4), f'{d}/logo/logo-horizontal.png')
    save(up(lv, 4), f'{d}/logo/logo-stacked.png')
    save(up(wm, 4), f'{d}/logo/wordmark.png')
    save(up(lh, 2), f'{d}/logo/modlist-logo.png')
    # the mod's own logo in the jar (logoFile in neoforge.mods.toml) is the mod-list logo; keep them identical
    save(up(lh, 2), os.path.join(d, '..', '..', 'src', 'main', 'resources', 'almighty.png'))
    wide = [(0.86, 0.30, 4, 1), (0.95, 0.62, 3, 0), (0.70, 0.70, 3, 0)]
    # ---- banner family: 320 x 80 native
    b = scene(320, 80, wide)
    place(b, lockup_h(), x=16)
    save(up(b, 4), f'{d}/banner/readme-banner-1280x320.png', (1280, 320))
    save(up(b, 6), f'{d}/store/modrinth-banner-1920x480.png', (1920, 480))
    save(up(b, 5), f'{d}/store/curseforge-header-1600x400.png', (1600, 400))
    # ---- Modrinth gallery cover: 240 x 135 x8
    c = scene(240, 135, [(0.12, 0.62, 4, 1), (0.88, 0.58, 4, 1), (0.05, 0.25, 2, 0), (0.94, 0.22, 2, 0)])
    place(c, lockup_v(), y=8)
    save(up(c, 8), f'{d}/store/modrinth-cover-1920x1080.png', (1920, 1080))
    # ---- GitHub social preview: 256 x 128 x5
    g = scene(256, 128, [(0.12, 0.6, 4, 1), (0.88, 0.6, 4, 1)])
    place(g, lockup_v(), y=5)
    save(up(g, 5), f'{d}/social/github-social-1280x640.png', (1280, 640))
    # ---- avatar: 80 x 80 x5
    a = sky(80, 80, clouds=False)
    for (x, y, w) in [(3, 36, 8), (68, 58, 8)]:
        a.rect(x, y, x + w, y + 1, P('sky', 3))
    a.paste(mark_layer(), 8, 10)
    save(up(a, 5), f'{d}/social/avatar-400.png', (400, 400))
    # ---- X header: 300 x 100 x5; the profile picture covers the lower left, so the lockup sits high and right
    x = scene(300, 100, [(0.06, 0.30, 3, 0), (0.30, 0.66, 3, 0), (0.95, 0.70, 3, 0)])
    lk = lockup_h(tag=False)
    place(x, lk, x=300 - lk.w - 16, y=8)
    tl = tagline(TAG)
    place(x, tl, x=300 - tl.w - 12, y=78)
    save(up(x, 5), f'{d}/social/x-header-1500x500.png', (1500, 500))
    # ---- YouTube channel art: 512 x 288 x5; the lockup stays inside the 1546 x 423 safe area (309 x 84 native)
    yt = scene(512, 288, [(0.13, 0.52, 12, 1), (0.87, 0.48, 12, 1), (0.30, 0.74, 6, 1), (0.70, 0.76, 6, 1),
                          (0.24, 0.22, 4, 0), (0.76, 0.20, 4, 0), (0.04, 0.84, 5, 0), (0.96, 0.86, 5, 0)])
    lk = lockup_h()
    assert lk.w <= 309 and lk.h <= 84
    place(yt, lk)
    save(up(yt, 5), f'{d}/social/youtube-2560x1440.png', (2560, 1440))
    # ---- video thumbnails: 160 x 90 x8
    t = scene(160, 90, [(0.9, 0.78, 3, 0), (0.62, 0.86, 2, 0)])
    t.paste(mark_layer(), 4, 13)
    draw_text(t, 'AI GAME', 76, 26, P('cloud', 4), bold=True)
    draw_text(t, 'MASTER', 76, 37, P('cloud', 4), bold=True)
    draw_text(t, 'ALMIGHTY', 76, 52, P('gold', 3))
    save(up(t, 8), f'{d}/video/thumbnail-title-1280x720.png', (1280, 720))
    t2 = scene(160, 90, [(0.14, 0.7, 4, 1), (0.86, 0.66, 4, 1), (0.30, 0.25, 2, 0), (0.72, 0.2, 2, 0)])
    t2.paste(mark_layer(), 48, 13)
    save(up(t2, 8), f'{d}/video/thumbnail-plain-1280x720.png', (1280, 720))
    return ic


if __name__ == '__main__':
    build()
