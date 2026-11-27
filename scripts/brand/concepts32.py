"""Round five: 28 quick concepts drawn natively at 32x32 for the cut. Vanilla vocabulary, our own drawing."""
from pix import Layer, hexc, T
from art import R, c, mix, INK

R['cmd'] = [hexc(h) for h in ('#3B1A1C', '#7A3622', '#B9612C', '#E3964A', '#F8CB88')]
R['skin'] = [hexc(h) for h in ('#4A2626', '#8A4C3A', '#C27E5A', '#E2A77A', '#F6D0A6')]
R['lead'] = [hexc(h) for h in ('#3A2418', '#6E4A2C', '#A07A4C', '#CBA66E', '#EBD39C')]
R['ender'] = [hexc(h) for h in ('#0E2A26', '#155C4A', '#22936E', '#4ED19A', '#B8F7D2')]
BG = hexc('#241F3E')


def tile32(m):
    m.outline(INK)
    t = Layer(32, 32, BG)
    t.paste(m, 0, 0)
    return t


def cb(m, cx, ty, k=6, s=10, panel=True, face=None, mat='cmd'):
    """Command block: iso cube; every face framed, with a dark inset square; arrow mark on the right face;
    face() draws into the left face's inset (u, v coords)."""
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c(mat, 4 if (r == 0 or (x <= -hw + 1 and r < k)) else 3))
    if panel:   # recessed top: an inner rhombus one step darker
        kk = k - 2
        for r in range(2 * kk):
            hw = 2 * (r + 1) if r < kk else 2 * (2 * kk - r)
            for x in range(-hw, hw):
                m.px(cx + x, ty + 2 + r, c(mat, 2) if r < kk else c(mat, 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(s):
            m.px(cx - 2 * k + u, y0 + v, c(mat, 3) if (u == 0 or v == 0) else c(mat, 2))
            m.px(cx + 2 * k - 1 - u, y0 + v, c(mat, 2) if v == 0 else c(mat, 1))
    if panel:
        for u in range(2, 2 * k - 1):
            y0 = ty + k + u // 2 + 1
            for v in range(2, s - 1):
                m.px(cx - 2 * k + u, y0 + v, c('steel', 1) if v == 2 else c('steel', 0))
                m.px(cx + 2 * k - 1 - u, y0 + v, c('steel', 0))
        # arrow mark (chevron) on the right face's inset
        mid = s // 2 + 1
        for i in range(3):
            u = k - 1 + i
            y0 = ty + k + u // 2 + 1
            m.px(cx + 2 * k - 1 - u, y0 + mid - 1 + i, c(mat, 3))
            m.px(cx + 2 * k - 1 - u, y0 + mid + 1 - i, c(mat, 3))
    if face:
        face(lambda u, v, col: m.px(cx - 2 * k + u, ty + k + u // 2 + 1 + v, col))


def eyes_on(px, u0=3, v=4, gap=4, col=None):
    col = col or c('cyan', 4)
    for du in (0, 1):
        px(u0 + du, v, col); px(u0 + gap + du, v, col)
        px(u0 + du, v + 1, c('cyan', 2)); px(u0 + gap + du, v + 1, c('cyan', 2))


def head(m, x, y, n=14, eyes='cyan', hair=True, crown=False):
    m.rect(x, y, x + n - 1, y + n - 1, c('skin', 3))
    m.rect(x + n - 3, y + 1, x + n - 1, y + n - 1, c('skin', 2))
    if hair:
        m.rect(x, y, x + n - 1, y + 3, c('night', 4))
        m.rect(x, y + 4, x, y + 5, c('night', 4)); m.rect(x + n - 1, y + 4, x + n - 1, y + 5, c('night', 4))
    ey = y + n // 2
    for ex in (x + 2, x + n - 5):
        if eyes == 'cyan':
            m.rect(ex, ey, ex + 2, ey + 1, c('cyan', 4)); m.rect(ex, ey + 1, ex + 2, ey + 1, c('cyan', 2))
        else:
            m.rect(ex, ey, ex + 1, ey + 1, INK); m.px(ex + 2, ey, hexc('#FFFFFF'))
    m.rect(x + n // 2 - 2, y + n - 3, x + n // 2 + 1, y + n - 3, c('skin', 1))


def lead(m, pts):
    """A lead: twisted rope of two browns along a polyline."""
    k = 0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        n = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(n + 1):
            x = x0 + round((x1 - x0) * i / n); y = y0 + round((y1 - y0) * i / n)
            m.px(x, y, c('lead', 3) if (k // 2) % 2 else c('lead', 2)); k += 1


def knot(m, x, y):
    m.rect(x - 1, y - 1, x + 1, y + 1, c('lead', 3)); m.px(x - 1, y - 1, c('lead', 4)); m.px(x + 1, y + 1, c('lead', 1))


def ender(m, cx, cy, r=6):
    for y in range(-r, r + 1):
        for x in range(-r, r + 1):
            d = x * x + y * y
            if d <= r * r:
                col = c('ender', 3)
                if x + y > r // 2: col = c('ender', 2)
                if x + y < -r: col = c('ender', 4)
                m.px(cx + x, cy + y, col)
    m.rect(cx - 1, cy - r // 2, cx, cy + r // 2, c('ender', 0))
    m.px(cx - r // 2, cy - r // 2, c('ender', 4))


def crown(m, x, y, w=14):
    m.rect(x, y + 3, x + w - 1, y + 5, c('gold', 3)); m.rect(x, y + 5, x + w - 1, y + 5, c('gold', 1))
    for px in (x, x + w // 2 - 1, x + w - 2):
        m.rect(px, y, px + 1, y + 2, c('gold', 4))
    m.rect(x + 2, y + 2, x + w - 3, y + 2, c('gold', 3))


def arm(m, x, y, flip=False):
    """Blocky first-person arm reaching in from the lower right."""
    for i in range(12):
        xx = x + i if not flip else x - i
        m.rect(xx, y + i // 2, xx + 4, y + i // 2 + 4, c('skin', 3))
        m.px(xx, y + i // 2, c('skin', 4))
    m.rect(x + 12, y + 6, x + 16, y + 10, c('gold', 2))   # sleeve


C = {}


def concept(name, idea):
    def deco(f):
        C[name] = (f, idea)
        return f
    return deco


@concept('cb_eyes', 'A command block that is alive: two glowing eyes in its panel.')
def _(m): cb(m, 16, 4, face=lambda px: eyes_on(px))


@concept('cb_lead', 'A command block on a lead tied to a post: god power, on a leash.')
def _(m):
    cb(m, 18, 6, face=lambda px: eyes_on(px)); lead(m, [(8, 16), (4, 20), (3, 28)]); knot(m, 8, 16)
    m.rect(2, 26, 4, 31, c('soil', 3))


@concept('cb_lead_hand', 'A hand holds the lead of a living command block: the owner walks the AI.')
def _(m):
    cb(m, 20, 3, face=lambda px: eyes_on(px)); lead(m, [(10, 13), (6, 20), (5, 25)])
    m.rect(1, 24, 8, 30, c('skin', 3)); m.rect(1, 24, 8, 24, c('skin', 4))


@concept('head_cyan', 'A player head with glowing cyan eyes: a bot, a possessed player.')
def _(m): head(m, 7, 7, 18)


@concept('head_cb_face', 'A player head whose face is a command-block screen.')
def _(m):
    m.rect(7, 6, 24, 23, c('cmd', 3)); m.rect(7, 6, 24, 8, c('cmd', 4)); m.rect(22, 9, 24, 23, c('cmd', 2))
    m.rect(9, 11, 22, 20, c('steel', 0)); m.rect(11, 14, 13, 16, c('cyan', 4)); m.rect(18, 14, 20, 16, c('cyan', 4))
    m.rect(13, 24, 18, 26, c('cmd', 1))


@concept('head_lead', 'A glowing-eyed player head on a lead: a bot you keep on a leash.')
def _(m):
    head(m, 12, 4, 16); lead(m, [(12, 18), (6, 22), (3, 29)]); knot(m, 12, 18)


@concept('head_crown', 'A crowned player head with glowing eyes: an AI that rules the server.')
def _(m): crown(m, 8, 3, 16); head(m, 8, 10, 16)


@concept('ender_cb', 'An ender eye set into a command block: it sees and commands everything.')
def _(m):
    cb(m, 16, 6); ender(m, 10, 19, 4)


@concept('ender_lead', 'An ender eye on a lead: all-seeing, but held.')
def _(m): ender(m, 18, 12, 8); lead(m, [(12, 19), (7, 24), (4, 30)]); knot(m, 12, 19)


@concept('hand_place_cb', 'A hand placing a command block into the world: the AI acts.')
def _(m):
    cb(m, 13, 4, k=5, s=8, face=lambda px: eyes_on(px, 2, 3, 3)); arm(m, 14, 16)
    m.rect(0, 28, 31, 31, c('moss', 3)); m.rect(0, 28, 31, 28, c('moss', 4))


@concept('cb_strings_head', 'A command block works a player head on strings.')
def _(m):
    cb(m, 16, 0, k=5, s=7); head(m, 9, 18, 14)
    for x0, x1 in [(9, 10), (16, 16), (23, 22)]:
        m.line(x0, 14, x1, 18, c('cyan', 3))


@concept('mono_a_cb', 'Monogram: the letter A built from command-block orange with a panel crossbar.')
def _(m):
    for y in range(4, 28):
        w = 3 + (y - 4) // 2
        m.rect(16 - w, y, 16 - w + 4, y, c('cmd', 3)); m.rect(15 + w - 4, y, 15 + w, y, c('cmd', 2))
        m.px(16 - w, y, c('cmd', 4))
    m.rect(12, 4, 19, 6, c('cmd', 4))
    m.rect(8, 17, 23, 21, c('steel', 0)); m.rect(10, 18, 12, 19, c('cyan', 4)); m.rect(19, 18, 21, 19, c('cyan', 4))


@concept('totem_eyes', 'A totem with glowing eyes: an undying power that watches over the server.')
def _(m):
    g = lambda i: c('gold', i)
    m.rect(10, 3, 21, 13, g(3)); m.rect(10, 3, 21, 4, g(4)); m.rect(12, 7, 13, 8, c('cyan', 4)); m.rect(18, 7, 19, 8, c('cyan', 4))
    m.rect(8, 14, 23, 17, g(2)); m.rect(4, 14, 7, 16, g(3)); m.rect(24, 14, 27, 16, g(2))
    m.rect(11, 18, 20, 27, g(2)); m.rect(13, 21, 18, 23, c('ender', 3)); m.rect(12, 28, 19, 29, g(1))


@concept('cb_crown', 'A crowned command block with eyes: the server\'s ruler.')
def _(m): crown(m, 10, 1, 12); cb(m, 16, 8, face=lambda px: eyes_on(px))


@concept('cb_lightning', 'A living command block striking lightning: limitless power.')
def _(m):
    cb(m, 13, 8, face=lambda px: eyes_on(px))
    for i, (x, y) in enumerate([(27, 0), (26, 1), (25, 2), (24, 3), (25, 4), (26, 4), (25, 5), (24, 6), (23, 7), (22, 8)]):
        m.px(x, y, c('cyan', 4)); m.px(x + 1, y, c('cyan', 2))


@concept('head_half_cb', 'Half player face, half command block: the agent with a player body.')
def _(m):
    m.rect(6, 6, 15, 25, c('skin', 3)); m.rect(6, 6, 15, 9, c('night', 4)); m.rect(9, 14, 11, 15, INK)
    m.rect(16, 6, 25, 25, c('cmd', 3)); m.rect(16, 6, 25, 7, c('cmd', 4)); m.rect(17, 10, 24, 22, c('steel', 0))
    m.rect(19, 14, 21, 15, c('cyan', 4))


@concept('lead_owner_agent', 'A lead from a player head (owner) to a living command block (agent).')
def _(m):
    head(m, 1, 16, 12, eyes='dark'); cb(m, 23, 2, k=4, s=7, face=lambda px: eyes_on(px, 1, 2, 3))
    lead(m, [(13, 20), (16, 16), (18, 12)])


@concept('keyhole_eye', 'An ender eye whose pupil is a keyhole: it sees all, and you hold the key.')
def _(m):
    ender(m, 16, 16, 11)
    m.rect(14, 10, 17, 15, c('ender', 0)); m.rect(15, 16, 16, 22, c('ender', 0))


@concept('cb_cage', 'A living command block inside an iron cage: contained power.')
def _(m):
    cb(m, 16, 6, face=lambda px: eyes_on(px))
    for x in range(3, 30, 5):
        m.rect(x, 2, x, 30, c('steel', 3))
    m.rect(3, 2, 29, 2, c('steel', 4)); m.rect(3, 30, 29, 30, c('steel', 2))


@concept('head_cb_visor', 'A player head wearing a command-block visor: a player run by commands.')
def _(m):
    head(m, 8, 7, 16); m.rect(7, 12, 24, 17, c('cmd', 3)); m.rect(7, 12, 24, 12, c('cmd', 4))
    m.rect(9, 14, 22, 15, c('cyan', 4))


@concept('ring_cube', 'A world cube inside an orbit ring with an eye at its heart: power around the world.')
def _(m):
    cb(m, 16, 6, panel=False, mat='moss'); 
    for x in range(1, 31):
        y = 18 + round(4 * (1 - ((x - 15.5) / 15) ** 2) ** 0.5 if abs(x - 15.5) < 15 else 0)
        m.px(x, y, c('cyan', 3))
    ender(m, 16, 6, 3)


@concept('pickaxe_cb', 'A pickaxe and a command block crossed: it can play AND command.')
def _(m):
    cb(m, 19, 9, k=5, s=8, face=lambda px: eyes_on(px, 2, 3, 3))
    for i in range(16):
        m.rect(3 + i, 27 - i, 4 + i, 28 - i, c('soil', 3))
    m.rect(9, 9, 22, 11, c('steel', 4)); m.rect(7, 11, 9, 14, c('steel', 3))


@concept('cursor_cb', 'A cursor clicking a command block: an agent operating the game.')
def _(m):
    cb(m, 14, 6, face=lambda px: eyes_on(px))
    arrow = ["4.....", "44....", "434...", "4334..", "43334.", "433334", "4332..", "42.32.", "4..32."]
    m.sprite(arrow, 20, 16, {'4': c('steel', 4), '3': hexc('#F2F8FA'), '2': c('steel', 3)})


@concept('head_ender_eyes', 'A player head with ender-eye eyes: a player that sees everything.')
def _(m):
    head(m, 6, 6, 20, eyes='none')
    for ex in (9, 18):
        m.rect(ex, 15, ex + 4, 18, c('ender', 3)); m.rect(ex + 1, 15, ex + 2, 18, c('ender', 0)); m.px(ex, 15, c('ender', 4))


@concept('cb_face_smile', 'A command block with a calm face: a friendly, obedient power.')
def _(m):
    cb(m, 16, 4, face=lambda px: (eyes_on(px), [px(u, 7, c('cyan', 3)) for u in (4, 5, 6, 7)]))


@concept('mono_a_lead', 'Monogram: an A drawn by a lead, its crossbar a knot holding a glowing eye.')
def _(m):
    lead(m, [(6, 28), (16, 3), (26, 28)]); lead(m, [(10, 19), (22, 19)]); knot(m, 16, 19)
    m.rect(14, 10, 17, 12, c('cyan', 4))


@concept('player_on_cb', 'A glowing-eyed player standing on a command block: the agent, embodied, on its power.')
def _(m):
    cb(m, 16, 16, k=5, s=6)
    head(m, 11, 0, 10); m.rect(12, 10, 19, 16, c('steel', 3)); m.rect(12, 17, 19, 21, c('night', 4))


@concept('head_glitch', 'A player head split by a bright scan line: a digital mind inside a player.')
def _(m):
    head(m, 7, 7, 18); m.rect(4, 15, 27, 15, c('cyan', 4)); m.rect(25, 15, 29, 15, c('cyan', 2)); m.rect(2, 15, 3, 15, c('cyan', 2))


def render(name):
    f, _ = C[name]
    m = Layer(32, 32)
    f(m)
    return tile32(m)
