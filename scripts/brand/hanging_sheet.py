"""Follow-up test sheet: python3 scripts/brand/hanging_sheet.py <runA results> <runA pool> <runB results> <runB pool>
-> docs/brand/exploration/hanging-bot.png and hanging-bot/ icons."""
import json, os, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')
F = '/usr/share/fonts/truetype/dejavu/'


def main(ra, pa, rb, pb):
    out = os.path.join(ROOT, 'hanging-bot'); os.makedirs(out, exist_ok=True)
    fb, fn, fs = ImageFont.truetype(F + 'DejaVuSans-Bold.ttf', 30), ImageFont.truetype(F + 'DejaVuSans.ttf', 18), ImageFont.truetype(F + 'DejaVuSans-Bold.ttf', 22)
    runs = [('Run B: humanoid robot pulling the strings (5 variants) vs the approved mark', json.load(open(rb)), json.load(open(pb))),
            ('Run A: robot hanging upside down from above (3 variants) vs the approved mark', json.load(open(ra)), json.load(open(pa)))]
    rh, W = 156, 1100
    H = 40 + sum(70 + rh * len(R['rows']) for _, R, _ in runs)
    im = Image.new('RGB', (W, H), (16, 17, 22)); d = ImageDraw.Draw(im)
    y = 20
    for title, R, P in runs:
        d.text((24, y), title, font=fs, fill=(236, 239, 244))
        d.text((24, y + 32), 'Single-mark scoring, icon + "Almighty" only, 7 fresh judges per mark: average of clarity, click, look',
               font=fn, fill=(170, 176, 188))
        y += 70
        for i, (avg, n, a, b, c, k) in enumerate(R['rows']):
            d.rounded_rectangle([16, y, W - 16, y + rh - 12], 12, fill=(32, 35, 41))
            ic = Image.open(P[n]).convert('RGBA')
            ic.save(os.path.join(out, n.replace(' (approved)', '-approved') + '-64.png'))
            big = ic.resize((128, 128), Image.NEAREST)
            im.paste(big, (100, y + 8), big)
            d.text((36, y + 50), f'{i + 1}', font=fs, fill=(123, 235, 207))
            d.text((250, y + 22), 'Almighty', font=fb, fill=(236, 239, 244))
            d.text((250, y + 72), f'average {avg:.2f}   clarity {a:.2f}   click {b:.2f}   look {c:.2f}', font=fn, fill=(170, 176, 188))
            d.text((250, y + 100), n, font=fn, fill=(110, 116, 130))
            y += rh
        y += 10
    im.save(os.path.join(ROOT, 'hanging-bot.png'), optimize=True)


if __name__ == '__main__':
    main(*sys.argv[1:])
