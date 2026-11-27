"""Round-two sketch sheet: python3 scripts/brand/sketches.py -> docs/brand/exploration/sketches.png (+ sketches/NN-name.png)."""
import os, textwrap
from PIL import Image
from pix import Layer, hexc, up
from fonts import draw_text, SMALL
from marks2 import MARKS2, SKETCHES

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'docs', 'brand', 'exploration')
BG, FACE, MUTED, ACC = hexc('#0C0B14'), hexc('#ECEFF4'), hexc('#AEB6C6'), hexc('#7BEBCF')


def caption(i, text, width_chars=40):
    lines = textwrap.wrap(text.upper().replace('Λ', 'A').replace("\\'", "'"), width_chars)
    L = Layer(256, 14 + 10 * len(lines), BG)
    draw_text(L, f'{i}', 0, 0, ACC, bold=True)
    for j, ln in enumerate(lines):
        ln = ''.join(ch if ch in SMALL or ch == ' ' else '-' for ch in ln)
        draw_text(L, ln, 0, 12 + j * 10, MUTED if j else FACE)
    return L


def main():
    os.makedirs(os.path.join(ROOT, 'sketches'), exist_ok=True)
    cols, cw, pad = 3, 512, 32
    caps = [caption(i + 1, idea) for i, (_, idea) in enumerate(SKETCHES)]
    capH = max(cp.h for cp in caps) * 2
    rows = (len(SKETCHES) + cols - 1) // cols
    W = pad + cols * (cw + pad)
    H = 80 + rows * (cw + 16 + capH + pad)
    sheet = Image.new('RGBA', (W, H), BG)
    head = Layer(W // 2, 30, BG)
    draw_text(head, 'ALMIGHTY - MARK SKETCHES, ROUND TWO', 0, 4, FACE, bold=True)
    draw_text(head, 'NATIVE 64X64, SHOWN X8 NEAREST-NEIGHBOUR. ARTEM PICKS ONE.', 0, 16, MUTED)
    sheet.alpha_composite(up(head, 2), (pad, 8))
    for i, (name, idea) in enumerate(SKETCHES):
        icon = MARKS2[name]()
        icon.im.save(os.path.join(ROOT, 'sketches', f'{i + 1:02d}-{name}-64.png'))
        up(icon, 8).save(os.path.join(ROOT, 'sketches', f'{i + 1:02d}-{name}-512.png'))
        x = pad + (i % cols) * (cw + pad)
        y = 80 + (i // cols) * (cw + 16 + capH + pad)
        sheet.alpha_composite(up(icon, 8), (x, y))
        sheet.alpha_composite(icon.im.resize((32, 32), Image.BOX), (x + cw - 40, y + cw + 16))
        sheet.alpha_composite(up(caps[i], 2), (x, y + cw + 16))
    sheet.save(os.path.join(ROOT, 'sketches.png'), optimize=True)


if __name__ == '__main__':
    main()
