"""Round five finalists: python3 scripts/brand/finalists2.py -> docs/brand/exploration/finalists2.png, finalists2/, concepts32.png"""
import os
from PIL import Image
from pix import Layer, hexc, up
from fonts import draw_text
from art import c
from cards import card, ALMIGHTY_TAGS
from finalists import caption
from round5 import r1_living_block, r2b_block_head, r6b_monogram, r5_puppeteer
import concepts32

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')
SUMMARY = 'An AI agent runs your Minecraft server as a game master, within the permissions you give it.'
FIN = [
    (r1_living_block, 'living-block', 'Living command block: the block every player knows as admin power, awake, the agent\'s eyes in its panel.'),
    (r2b_block_head, 'block-head', 'Block head: a head built from a command block; its screen is the agent\'s face, its arrow the mouth.'),
    (r6b_monogram, 'monogram', 'Monogram A: an A in command-block orange; its crossbar is the block\'s inset, holding the agent\'s eyes.'),
    (r5_puppeteer, 'puppeteer', 'Puppeteer: on a puppet stage, a screen with the agent\'s eyes works a player on strings.'),
]


def concept_sheet():
    names = list(concepts32.C)
    cols = 7
    W = 24 + cols * 136
    H = 60 + ((len(names) + cols - 1) // cols) * 156
    sheet = Image.new('RGBA', (W, H), hexc('#0C0B14'))
    head = Layer(W // 2, 24, hexc('#0C0B14'))
    draw_text(head, 'ROUND FIVE - 28 CONCEPTS, NATIVE 32 PX SHOWN X4', 0, 4, hexc('#ECEFF4'), bold=True)
    sheet.alpha_composite(up(head, 2), (24, 4))
    for i, n in enumerate(names):
        im = concepts32.render(n)
        x, y = 24 + (i % cols) * 136, 60 + (i // cols) * 156
        sheet.alpha_composite(up(im, 4), (x, y))
        lab = Layer(128, 8, hexc('#0C0B14'))
        draw_text(lab, f'{i + 1:02d}', 0, 0, c('cyan', 3))
        sheet.alpha_composite(up(lab, 2), (x, y + 132))
    sheet.save(os.path.join(ROOT, 'concepts32.png'), optimize=True)


def main():
    out = os.path.join(ROOT, 'finalists2')
    os.makedirs(out, exist_ok=True)
    bg = hexc('#0C0B14')
    pad, big = 32, 384
    rowh = big + pad
    W = pad + big + pad + 760 + pad
    H = 80 + len(FIN) * rowh
    sheet = Image.new('RGBA', (W, H), bg)
    head = Layer(W // 2, 30, bg)
    draw_text(head, 'ALMIGHTY - FINALISTS, ROUND FIVE', 0, 4, hexc('#ECEFF4'), bold=True)
    draw_text(head, 'FOUR CONCEPT FAMILIES. ICON X6, LIST CARD AT REAL SIZE, ICON AT 64 AND 32 PX.', 0, 16, hexc('#AEB6C6'))
    sheet.alpha_composite(up(head, 2), (pad, 8))
    for i, (f, n, idea) in enumerate(FIN):
        icon = f()
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
    sheet.save(os.path.join(ROOT, 'finalists2.png'), optimize=True)
    concept_sheet()


if __name__ == '__main__':
    main()
