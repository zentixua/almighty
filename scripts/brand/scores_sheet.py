"""Round eight: ranked sheet from the blind scoring pool -> docs/brand/scores.png, scores/.
Needs the scoring results (results.json) and the pool icons produced in the scoring run."""
import json, os, shutil, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand')
F = '/usr/share/fonts/truetype/dejavu/'


def main(results, pool, name='scores', title='Almighty: blind scoring pool, name + icon only (7 judges)'):
    R = json.load(open(results)); P = json.load(open(pool))
    out = os.path.join(ROOT, name); os.makedirs(out, exist_ok=True)
    rows = R['rows']
    fb, fn, fs = ImageFont.truetype(F + 'DejaVuSans-Bold.ttf', 30), ImageFont.truetype(F + 'DejaVuSans.ttf', 18), ImageFont.truetype(F + 'DejaVuSans-Bold.ttf', 22)
    rh, W = 156, 1100
    im = Image.new('RGB', (W, 90 + rh * len(rows)), (16, 17, 22)); d = ImageDraw.Draw(im)
    d.text((24, 18), title, font=fs, fill=(236, 239, 244))
    d.text((24, 50), 'Average of 1-10 scores: clarity (name + icon say what it does), click, professional look', font=fn, fill=(170, 176, 188))
    for i, (avg, n, a, b, c, k) in enumerate(rows):
        y = 90 + i * rh
        d.rounded_rectangle([16, y, W - 16, y + rh - 12], 12, fill=(32, 35, 41))
        ic = Image.open(P[n]).convert('RGBA')
        ic.save(f'{out}/{i + 1:02d}-{n}-64.png')
        big = ic.resize((128, 128), Image.NEAREST)
        im.paste(big, (100, y + 8), big)
        d.text((36, y + 50), f'{i + 1}', font=fs, fill=(123, 235, 207))
        d.text((250, y + 22), 'Almighty', font=fb, fill=(236, 239, 244))
        d.text((250, y + 72), f'average {avg:.2f}   clarity {a:.2f}   click {b:.2f}   look {c:.2f}', font=fn, fill=(170, 176, 188))
        d.text((250, y + 100), n, font=fn, fill=(110, 116, 130))
    im.save(os.path.join(ROOT, name + '.png'), optimize=True)


if __name__ == '__main__':
    main(*sys.argv[1:])
