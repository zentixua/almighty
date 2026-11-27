"""Round four finalists: python3 scripts/brand/finalists.py -> docs/brand/finalists.png + finalists/NN-*.png"""
import os, textwrap
from PIL import Image
from pix import Layer, hexc, up
from fonts import draw_text, SMALL
from art import c
from cand import t2_theater_screen, f1_theater_screen_doll, t1_theater_ai
from cards import card, ALMIGHTY_TAGS

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')
FINALISTS = [
    (t2_theater_screen, 'stage-screen', 'Stage, screen puppeteer: on a puppet stage, a screen with the agent\'s eyes works a player on strings; the player\'s eyes carry its light.'),
    (f1_theater_screen_doll, 'stage-marionette', 'Stage, marionette: the same screen puppeteer works a wooden block-built marionette with lit eyes, one knee lifted by its string.'),
    (t1_theater_ai, 'stage-ai', 'Stage, AI bar: big extruded AI letters are the control bar; their strings work a player whose eyes carry the light.'),
]


def caption(i, text):
    lines = textwrap.wrap(text.upper(), 40)
    L = Layer(256, 14 + 10 * len(lines), hexc('#0C0B14'))
    draw_text(L, f'{i}', 0, 0, c('cyan', 3), bold=True)
    for j, ln in enumerate(lines):
        ln = ''.join(ch if ch in SMALL or ch == ' ' else '-' for ch in ln)
        draw_text(L, ln, 0, 12 + j * 10, hexc('#ECEFF4') if j == 0 else hexc('#AEB6C6'))
    return L


SUMMARY = {
    'A': 'An AI agent runs your Minecraft server as a game master, within the permissions you give it.',
    'B': 'Give an AI a real player body and admin power on your server. You decide what it may do.',
}
PAIRS = {'stage-screen': 'A', 'stage-marionette': 'B', 'stage-ai': 'A'}


def main():
    out = os.path.join(ROOT, 'finalists')
    os.makedirs(out, exist_ok=True)
    bg = hexc('#0C0B14')
    pad, big = 32, 384
    rowh = big + pad
    W = pad + big + pad + 760 + pad
    H = 80 + len(FINALISTS) * rowh
    sheet = Image.new('RGBA', (W, H), bg)
    head = Layer(W // 2, 30, bg)
    draw_text(head, 'ALMIGHTY - FINALISTS AS LIST CARDS', 0, 4, hexc('#ECEFF4'), bold=True)
    draw_text(head, 'ICON X6, THE CARD AT REAL SIZE, THE ICON AT 64 AND 32 PX.', 0, 16, hexc('#AEB6C6'))
    sheet.alpha_composite(up(head, 2), (pad, 8))
    for i, (f, n, idea) in enumerate(FINALISTS):
        icon = f()
        icon.im.save(f'{out}/{i + 1:02d}-{n}-64.png')
        up(icon, 8).save(f'{out}/{i + 1:02d}-{n}-512.png')
        small = icon.im.resize((32, 32), Image.BOX)
        small.save(f'{out}/{i + 1:02d}-{n}-32.png')
        cd = card(icon.im, 'Almighty', SUMMARY[PAIRS[n]], ALMIGHTY_TAGS, 'new')
        cd.save(f'{out}/{i + 1:02d}-{n}-card.png')
        y = 80 + i * rowh
        sheet.alpha_composite(up(icon, 6), (pad, y))
        x = pad + big + pad
        sheet.alpha_composite(cd.convert('RGBA'), (x, y))
        sheet.alpha_composite(icon.im, (x, y + 124))
        sheet.alpha_composite(small, (x + 80, y + 124 + 16))
        cp = caption(i + 1, idea)
        sheet.alpha_composite(up(cp, 2), (x + 128, y + 124))
    sheet.save(os.path.join(ROOT, 'finalists.png'), optimize=True)


if __name__ == '__main__':
    main()
