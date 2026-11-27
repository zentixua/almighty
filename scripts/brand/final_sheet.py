"""Presentation sheet: python3 scripts/brand/final_sheet.py -> docs/brand/final.png.

Shows the package as a user meets it: a store project page, a mod list, a GitHub repository, a YouTube channel.
The page chrome is a neutral mock in a system font (not any real site's branding); only the artwork is ours."""
import os
from PIL import Image, ImageDraw, ImageFont
from cards import card, FILLERS, filler_icon, ALMIGHTY_TAGS

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand')
F = '/usr/share/fonts/truetype/dejavu/'
f = lambda s, b=False: ImageFont.truetype(F + ('DejaVuSans-Bold.ttf' if b else 'DejaVuSans.ttf'), s)
BG, PANEL, TXT, SUB, LINE = (13, 14, 19), (26, 28, 34), (236, 239, 244), (160, 166, 178), (48, 52, 60)
SUMMARY = 'An AI game master for your Minecraft server: it plays, builds and runs events, within the permissions you give it.'


def img(p):
    return Image.open(os.path.join(ROOT, p)).convert('RGBA')


def fit(im, w):
    return im.resize((w, round(im.height * w / im.width)), Image.LANCZOS if im.width > w else Image.NEAREST)


def label(d, x, y, text):
    d.text((x, y), text, font=f(15, True), fill=(123, 235, 207))


def main():
    W, H = 2000, 1830
    S = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(S)
    d.text((40, 28), 'Almighty: brand package, final proposal', font=f(30, True), fill=TXT)
    d.text((40, 70), 'The haloed cloud: an almighty AI that works your world on strings. Every image is hand-placed pixel art.',
           font=f(17), fill=SUB)

    # 1 -- store project page (mock)
    x0, y0, w = 40, 120, 1180
    label(d, x0, y0, '1  STORE PROJECT PAGE (MOCK)')
    y = y0 + 28
    d.rounded_rectangle([x0, y, x0 + w, y + 700], 14, fill=PANEL)
    ban = fit(img('store/modrinth-banner-1920x480.png'), w - 40)
    S.alpha_composite(ban, (x0 + 20, y + 20))
    icon = img('icon/icon-128.png').resize((112, 112), Image.LANCZOS)
    yy = y + 20 + ban.height + 18
    S.alpha_composite(img('icon/icon-512.png').resize((112, 112), Image.LANCZOS), (x0 + 24, yy))
    d.text((x0 + 156, yy + 4), 'Almighty', font=f(34, True), fill=TXT)
    d.text((x0 + 156, yy + 52), SUMMARY, font=f(16), fill=SUB)
    for i, t in enumerate(['Server', 'Utility', 'NeoForge 1.21.1']):
        d.rounded_rectangle([x0 + 156 + i * 150, yy + 82, x0 + 290 + i * 150, yy + 106], 12, fill=LINE)
        d.text((x0 + 168 + i * 150, yy + 86), t, font=f(14), fill=SUB)
    d.rounded_rectangle([x0 + w - 220, yy + 10, x0 + w - 24, yy + 54], 10, fill=(46, 120, 110))
    d.text((x0 + w - 182, yy + 20), 'Download', font=f(18, True), fill=TXT)
    gy = yy + 130
    d.text((x0 + 24, gy), 'Gallery', font=f(16, True), fill=TXT)
    cov = fit(img('store/modrinth-cover-1920x1080.png'), 300)
    S.alpha_composite(cov, (x0 + 24, gy + 26))
    S.alpha_composite(fit(img('video/thumbnail-plain-1280x720.png'), 300), (x0 + 340, gy + 26))
    S.alpha_composite(fit(img('banner/readme-banner-1280x320.png'), 480), (x0 + 656, gy + 26))

    # 2 -- mod list
    x1, y1 = 1260, 120
    label(d, x1, y1, '2  MOD LIST (ICON AT REAL SIZE)')
    yy = y1 + 28
    ic = img('icon/icon-64.png')
    fillers = list(FILLERS)
    for i in range(4):
        if i == 1:
            cd = card(ic, 'Almighty', SUMMARY, ALMIGHTY_TAGS, 'new')
        else:
            t, s_, tg, dl, k = fillers.pop(0)
            cd = card(filler_icon(k), t, s_, tg, dl)
        cd = cd.resize((700, round(cd.height * 700 / cd.width)), Image.LANCZOS)
        S.alpha_composite(cd.convert('RGBA'), (x1, yy))
        yy += cd.height + 10
    d.text((x1, yy + 6), 'Sizes: 16, 32, 64, 128 px', font=f(14), fill=SUB)
    xx = x1
    for s_ in (16, 32, 64, 128):
        im = ic.resize((s_, s_), Image.BOX) if s_ < 64 else ic.resize((s_, s_), Image.NEAREST)
        S.alpha_composite(im, (xx, yy + 30 + (128 - s_)))
        xx += s_ + 24
    S.alpha_composite(fit(img('logo/logo-horizontal.png'), 330), (xx + 10, yy + 70))

    # 3 -- GitHub repository (mock)
    x2, y2 = 40, 880
    label(d, x2, y2, '3  GITHUB README + SOCIAL PREVIEW')
    y = y2 + 28
    d.rounded_rectangle([x2, y, x2 + 1180, y + 400], 14, fill=PANEL)
    d.text((x2 + 24, y + 16), 'README.md', font=f(15, True), fill=SUB)
    d.line([x2, y + 44, x2 + 1180, y + 44], fill=LINE, width=1)
    rb = fit(img('banner/readme-banner-1280x320.png'), 700)
    S.alpha_composite(rb, (x2 + 24, y + 64))
    d.text((x2 + 24, y + 64 + rb.height + 16), 'An AI game master for your Minecraft server.', font=f(18), fill=TXT)
    gs = fit(img('social/github-social-1280x640.png'), 420)
    S.alpha_composite(gs, (x2 + 740, y + 64))
    d.text((x2 + 740, y + 64 + gs.height + 8), 'Link preview (social card)', font=f(14), fill=SUB)

    # 4 -- YouTube channel (mock)
    x3, y3 = 1260, 880
    label(d, x3, y3, '4  YOUTUBE CHANNEL')
    y = y3 + 28
    d.rounded_rectangle([x3, y, x3 + 700, y + 860], 14, fill=PANEL)
    art = img('social/youtube-2560x1440.png')
    # desktop banner = the safe-area band of the channel art
    band = art.crop((0, (1440 - 423) // 2, 2560, (1440 + 423) // 2))
    S.alpha_composite(fit(band, 660), (x3 + 20, y + 20))
    av = img('social/avatar-400.png').resize((96, 96), Image.LANCZOS)
    mask = Image.new('L', (96, 96), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 95, 95], fill=255)
    S.paste(av, (x3 + 24, y + 150), mask)
    d.text((x3 + 136, y + 166), 'Almighty', font=f(26, True), fill=TXT)
    d.text((x3 + 136, y + 202), 'Server builds, AI game master sessions', font=f(14), fill=SUB)
    t1, t2 = fit(img('video/thumbnail-title-1280x720.png'), 320), fit(img('video/thumbnail-plain-1280x720.png'), 320)
    S.alpha_composite(t1, (x3 + 20, y + 270)); S.alpha_composite(t2, (x3 + 360, y + 270))
    d.text((x3 + 20, y + 270 + t1.height + 8), 'Thumbnail with title', font=f(14), fill=SUB)
    d.text((x3 + 360, y + 270 + t2.height + 8), 'Thumbnail without title', font=f(14), fill=SUB)
    xh = fit(img('social/x-header-1500x500.png'), 660)
    d.text((x3 + 20, y + 500), 'X header (profile picture sits at the lower left)', font=f(14), fill=SUB)
    S.alpha_composite(xh, (x3 + 20, y + 524))
    S.paste(av.resize((80, 80)), (x3 + 40, y + 524 + xh.height - 40), mask.resize((80, 80)))

    # logos strip
    x4, y4 = 40, 1320
    label(d, x4, y4, '5  LOGO, LOCKUPS, MARK')
    y = y4 + 28
    d.rounded_rectangle([x4, y, x4 + 1180, y + 450], 14, fill=PANEL)
    lh = fit(img('logo/logo-horizontal.png'), 480)
    S.alpha_composite(lh, (x4 + 30, y + 30))
    lt = Image.new('RGBA', (lh.width + 40, lh.height + 40), (236, 238, 242, 255))
    lt.alpha_composite(lh, (20, 20))
    S.alpha_composite(lt, (x4 + 10, y + 60 + lh.height))
    d.text((x4 + 30, y + 110 + 2 * lh.height), 'The logo carries its own outline, so it works on light and dark grounds.', font=f(14), fill=SUB)
    st_ = fit(img('logo/logo-stacked.png'), 240)
    S.alpha_composite(st_, (x4 + 600, y + 30))
    S.alpha_composite(img('icon/mark-transparent-512.png').resize((256, 256), Image.NEAREST), (x4 + 880, y + 30))
    S.save(os.path.join(ROOT, 'final.png'), optimize=True)


if __name__ == '__main__':
    main()
