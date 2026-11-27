"""Round six sheet: python3 scripts/brand/finalists3.py -> docs/brand/finalists3.png, finalists3/, clouds32.png"""
import os
from PIL import Image
from pix import Layer, hexc, up
from fonts import draw_text
from art import c
from cards import card, ALMIGHTY_TAGS
from finalists import caption
from round5 import r2b_block_head
from round6 import k5_visor_cloud_strings, k4_cloud_strings_island, k3_crowned_face, k1_cloud_face
import clouds32

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand')
SUMMARY = 'An AI agent runs your Minecraft server as a game master, within the permissions you give it.'
ROWS = [
    (r2b_block_head, 'block-head', 'Benchmark no. 2, block head: the current favourite, a head built from a command block with a terminal face.'),
    (k5_visor_cloud_strings, 'visor-cloud-strings', 'Visor cloud on strings: a cloud wearing the terminal face\'s visor and eyes works a floating island on strings.'),
    (k4_cloud_strings_island, 'cloud-strings', 'Cloud on strings: a white cloud with small eyes works a floating island on strings.'),
    (k3_crowned_face, 'crowned-face', 'Crowned terminal face: no. 2\'s face with a crown, rising above the clouds.'),
    (k1_cloud_face, 'cloud-face', 'Cloud face: the cloud itself is the agent, with a visor, eyes and an arrow mouth.'),
]


def concept_sheet():
    names = list(clouds32.C)
    cols = 7
    W = 24 + cols * 136
    H = 60 + ((len(names) + cols - 1) // cols) * 156
    sheet = Image.new('RGBA', (W, H), hexc('#0C0B14'))
    head = Layer(W // 2, 24, hexc('#0C0B14'))
    draw_text(head, 'ROUND SIX - 13 CONCEPTS FROM ABOVE, NATIVE 32 PX SHOWN X4', 0, 4, hexc('#ECEFF4'), bold=True)
    sheet.alpha_composite(up(head, 2), (24, 4))
    for i, n in enumerate(names):
        im = clouds32.render(n)
        x, y = 24 + (i % cols) * 136, 60 + (i // cols) * 156
        sheet.alpha_composite(up(im, 4), (x, y))
        lab = Layer(128, 8, hexc('#0C0B14'))
        draw_text(lab, f'{i + 1:02d}', 0, 0, c('cyan', 3))
        sheet.alpha_composite(up(lab, 2), (x, y + 132))
    sheet.save(os.path.join(ROOT, 'clouds32.png'), optimize=True)


def main():
    out = os.path.join(ROOT, 'finalists3')
    os.makedirs(out, exist_ok=True)
    bg = hexc('#0C0B14')
    pad, big = 32, 384
    rowh = big + pad
    W = pad + big + pad + 760 + pad
    H = 80 + len(ROWS) * rowh
    sheet = Image.new('RGBA', (W, H), bg)
    head = Layer(W // 2, 30, bg)
    draw_text(head, 'ALMIGHTY - ROUND SIX: FROM ABOVE, AGAINST NO. 2', 0, 4, hexc('#ECEFF4'), bold=True)
    draw_text(head, 'ROW 0 IS THE BENCHMARK. ICON X6, LIST CARD AT REAL SIZE, ICON AT 64 AND 32 PX.', 0, 16, hexc('#AEB6C6'))
    sheet.alpha_composite(up(head, 2), (pad, 8))
    for i, (f, n, idea) in enumerate(ROWS):
        icon = f()
        icon.im.save(f'{out}/{i:02d}-{n}-64.png')
        up(icon, 8).save(f'{out}/{i:02d}-{n}-512.png')
        small = icon.im.resize((32, 32), Image.BOX)
        small.save(f'{out}/{i:02d}-{n}-32.png')
        cd = card(icon.im, 'Almighty', SUMMARY, ALMIGHTY_TAGS, 'new')
        cd.save(f'{out}/{i:02d}-{n}-card.png')
        y = 80 + i * rowh
        sheet.alpha_composite(up(icon, 6), (pad, y))
        x = pad + big + pad
        sheet.alpha_composite(cd.convert('RGBA'), (x, y))
        sheet.alpha_composite(icon.im, (x, y + 124))
        sheet.alpha_composite(small, (x + 80, y + 124 + 16))
        sheet.alpha_composite(up(caption(i, idea), 2), (x + 128, y + 124))
    sheet.save(os.path.join(ROOT, 'finalists3.png'), optimize=True)
    concept_sheet()


if __name__ == '__main__':
    main()
