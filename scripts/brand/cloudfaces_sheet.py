"""Round seven sheet: python3 scripts/brand/cloudfaces_sheet.py -> docs/brand/exploration/cloud-faces.png, cloud-faces/"""
import os
from PIL import Image
from pix import Layer, hexc, up
from fonts import draw_text
from cards import card, ALMIGHTY_TAGS
from finalists import caption
from cloudfaces import FACES, base

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')
SUMMARY = 'An AI agent runs your Minecraft server as a game master, within the permissions you give it.'


def main():
    out = os.path.join(ROOT, 'cloud-faces')
    os.makedirs(out, exist_ok=True)
    bg = hexc('#0C0B14')
    pad, big = 32, 384
    rowh = big + pad
    W = pad + big + pad + 760 + pad
    H = 80 + len(FACES) * rowh
    sheet = Image.new('RGBA', (W, H), bg)
    head = Layer(W // 2, 30, bg)
    draw_text(head, 'ALMIGHTY - CLOUD ON STRINGS, FOUR FACES', 0, 4, hexc('#ECEFF4'), bold=True)
    draw_text(head, 'ICON X6, LIST CARD AT REAL SIZE, ICON AT 64 AND 32 PX.', 0, 16, hexc('#AEB6C6'))
    sheet.alpha_composite(up(head, 2), (pad, 8))
    for i, (n, f, idea) in enumerate(FACES):
        icon = base(f)
        icon.im.save(f'{out}/{i + 1:02d}-{n}-64.png')
        up(icon, 8).save(f'{out}/{i + 1:02d}-{n}-512.png')
        small = icon.im.resize((32, 32), Image.BOX)
        small.save(f'{out}/{i + 1:02d}-{n}-32.png')
        cd = card(icon.im, 'Almighty', SUMMARY, ALMIGHTY_TAGS, 'new')
        cd.save(f'{out}/{i + 1:02d}-{n}-card.png')
        y = 80 + i * rowh
        sheet.alpha_composite(up(icon, 6), (pad, y))
        x = pad + big + pad
        sheet.alpha_composite(cd.convert('RGBA'), (x, y))
        sheet.alpha_composite(icon.im, (x, y + 124))
        sheet.alpha_composite(small, (x + 80, y + 124 + 16))
        sheet.alpha_composite(up(caption(i + 1, idea), 2), (x + 128, y + 124))
    sheet.save(os.path.join(ROOT, 'cloud-faces.png'), optimize=True)


if __name__ == '__main__':
    main()
