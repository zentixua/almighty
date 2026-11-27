"""The Almighty wordmark, drawn for the final mark.

Rows 0-21: ascenders reach row 0, the capital A spans rows 2-17, x-height rows 7-17, baseline after row 17,
descenders rows 18-21. Stems are 3 px. The A is a true capital with a peaked apex; the l is taller than the A
and ends in a foot that turns right, so 'Al' can never read as 'AI'. The dot of the i is a small halo.
Letters are shaded like the cloud: lit top row, white body, a violet-grey underside on every bottom edge.
"""
from pix import Layer
from fonts import DISPLAY
from final_mark import PAL, P, outline_outside


def _a():
    rows = []
    for r in range(16):
        x = 6 - (r * 2) // 5
        cells = set(range(x, x + 3)) | set(range(14 - x - 2, 14 - x + 1))
        if r in (10, 11):
            cells |= set(range(x, 15 - x))
        rows.append(''.join('#' if i in cells else '.' for i in range(15)))
    return [''] * 2 + rows


GLYPHS = {
    'A': _a(),
    'l': ['###.'] * 15 + ['###.', '####', '.###'],
    'm': [''] * 2 + DISPLAY['m'],
    'i': [''] * 2 + [''] * 5 + DISPLAY['i'][5:],
    'g': [''] * 2 + DISPLAY['g'],
    'h': ['###', '###'] + DISPLAY['h'],
    't': [''] * 2 + DISPLAY['t'],
    'y': [''] * 2 + DISPLAY['y'],
}
HALO_DOT = ['.###.', '#...#', '.###.']


def wordmark(shadow=True):
    text = 'Almighty'
    sp = 2
    widths = [max(len(r) for r in GLYPHS[ch]) for ch in text]
    W = sum(widths) + sp * (len(text) - 1) + 6
    L = Layer(W, 26)
    cells = set()
    x = 2
    ipos = None
    for ch, w in zip(text, widths):
        for y, row in enumerate(GLYPHS[ch]):
            for dx, cc in enumerate(row):
                if cc == '#':
                    cells.add((x + dx, y + 2))
        if ch == 'i':
            ipos = x
        x += w + sp
    for (cx, cy) in cells:
        col = PAL['cloud'][3]
        if (cx, cy - 1) not in cells:
            col = PAL['cloud'][4]
        if (cx, cy + 1) not in cells:
            col = PAL['cloud'][1]
        elif (cx, cy + 2) not in cells:
            col = PAL['cloud'][2]
        L.px(cx, cy, col)
    # the i's dot: a small gold halo floating over the stem
    for dy, row in enumerate(HALO_DOT):
        for dx, cc in enumerate(row):
            if cc == '#':
                L.px(ipos - 1 + dx, 2 + 3 + dy, PAL['gold'][3] if dy == 0 else PAL['gold'][2])
    outline_outside(L, PAL['ink'])
    if shadow:
        sh = Layer(L.w, L.h)
        for yy in range(L.h):
            for xx in range(L.w):
                if L.get(xx, yy)[3]:
                    sh.px(xx + 1, yy + 1, PAL['sky'][0])
        sh.paste(L, 0, 0)
        return sh
    return L
