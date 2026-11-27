"""Follow-up test (Artem's idea): a puppeteer robot hanging upside down from the sky, working the world on strings.
Same palette and craft rules as the approved mark (final_mark.py)."""
from pix import Layer, T
from art import c
from final_mark import PAL, P, sky, outline_outside, aa_corners, draw_island


def steel(i):
    return c('steel', i)


def cloud_band(m, y1):
    """A square cloud across the top, its lower edge stepped, shaded like the mark's cloud."""
    cells = set()
    edge = [y1, y1, y1 + 1, y1 + 1, y1 + 2, y1 + 2, y1 + 1, y1 + 1, y1, y1, y1 - 1, y1 - 1, y1, y1, y1 + 1, y1 + 1]
    for x in range(64):
        for y in range(0, edge[(x // 2) % len(edge)] + 1):
            cells.add((x, y))
    for (x, y) in cells:
        col = P('cloud', 3)
        if (x, y + 1) not in cells:
            col = P('cloud', 1)
        elif (x, y + 2) not in cells:
            col = P('cloud', 2)
        if y == 0:
            col = P('cloud', 4)
        m.px(x, y, col)


def hanging_bot(m, cx=32, shoulders_y=7, halo=False):
    """A robot hanging upside down from above, feet to the north: the torso runs up out of the top edge, the
    head hangs below it upside down (chin up, towards the body), its eyes low in the face, looking down at the
    world; the antenna points down. Returns the two hands' string points."""
    s = steel
    # torso and shoulders, continuing out of the top of the frame
    m.rect(cx - 9, 0, cx + 8, shoulders_y, s(2))
    m.rect(cx - 9, 0, cx - 8, shoulders_y, s(3)); m.rect(cx + 7, 0, cx + 8, shoulders_y, s(1))
    m.rect(cx - 13, shoulders_y - 3, cx + 12, shoulders_y, s(3))      # shoulder bar
    m.rect(cx - 13, shoulders_y, cx + 12, shoulders_y, s(1))
    m.rect(cx - 3, 1, cx + 2, 4, P('cyan', 1)); m.rect(cx - 2, 2, cx + 1, 3, P('cyan', 2))   # chest light
    m.rect(cx - 2, shoulders_y + 1, cx + 1, shoulders_y + 2, s(1))   # neck
    # the head, upside down: the chin (with a grille) is at the top, the crown and antenna at the bottom
    top, h, w = shoulders_y + 3, 17, 24
    x0, x1 = cx - w // 2, cx + w // 2 - 1
    m.rect(x0, top, x1, top + h - 1, s(3))
    m.rect(x0, top + h - 2, x1, top + h - 1, s(2))                   # the crown (bottom) turns away from the light
    m.rect(x0, top, x1, top + 1, s(4))                               # light from the sky lands on the chin side
    m.rect(x0, top, x0 + 1, top + h - 1, s(4)); m.rect(x1 - 1, top + 1, x1, top + h - 1, s(2))
    for gx in range(cx - 4, cx + 4, 2):                              # mouth grille near the chin (top)
        m.rect(gx, top + 3, gx, top + 4, s(1))
    m.rect(x0 + 3, top + 7, x1 - 3, top + 13, PAL['visor'][0])       # visor, low in the inverted face
    m.rect(x0 + 3, top + 7, x1 - 3, top + 7, PAL['visor'][1])
    for ex in (x0 + 5, x1 - 8):                                      # eyes look down: lit at the bottom
        m.rect(ex, top + 11, ex + 3, top + 11, P('cyan', 4)); m.rect(ex, top + 12, ex + 3, top + 12, P('cyan', 3))
        m.rect(ex, top + 10, ex + 3, top + 10, P('cyan', 1))
    m.rect(cx - 1, top + h, cx, top + h + 2, s(2))                   # antenna hangs down
    m.rect(cx - 2, top + h + 3, cx + 1, top + h + 4, P('cyan', 3)); m.px(cx - 2, top + h + 3, P('cyan', 4))
    if halo:                                                          # the halo hangs under the inverted head
        hy = top + h + 7
        for x in range(cx - 9, cx + 9):
            m.px(x, hy, P('gold', 3)); m.px(x, hy + 2, P('gold', 2))
        m.px(cx - 10, hy + 1, P('gold', 3)); m.px(cx + 9, hy + 1, P('gold', 2))
        m.px(cx - 6, hy, P('gold', 4)); m.px(cx - 5, hy, P('gold', 4))
    # arms dangle from the shoulders, hands low, pulling the strings
    h1 = arm_down(m, cx - 13, shoulders_y, cx - 22, top + 14, 0)
    h2 = arm_down(m, cx + 12, shoulders_y, cx + 21, top + 14, 1)
    return h1, h2


def arm_down(m, x_top, y_top, x_hand, y_hand, side):
    """A jointed arm from the cloud down to a hand; returns the hand's string point."""
    n = max(1, y_hand - y_top)
    for i in range(n + 1):
        x = x_top + round((x_hand - x_top) * i / n)
        y = y_top + i
        m.rect(x - 1, y, x + 1, y, steel(3) if side == 0 else steel(2))
        m.px(x - 1, y, steel(4) if side == 0 else steel(3))
    jx = x_top + (x_hand - x_top) // 2; jy = y_top + n // 2
    m.rect(jx - 2, jy - 1, jx + 2, jy + 1, steel(1))                     # elbow
    m.rect(x_hand - 2, y_hand, x_hand + 2, y_hand + 2, steel(3))          # hand
    m.rect(x_hand - 1, y_hand + 3, x_hand + 1, y_hand + 3, P('cyan', 3))
    return (x_hand, y_hand + 4)


def strings(m, pts):
    for (x0, y0), (x1, y1) in pts:
        m.line(x0, y0, x1, y1, P('cyan', 3))
        m.px(x1, y1, P('cyan', 4))


def tile(m):
    outline_outside(m, PAL['ink'])
    aa_corners(m, PAL['ink'])
    t = sky(64, 64, clouds=False)
    t.paste(m, 0, 0)
    return t


def h1_edge_island():
    """Hanging from the top edge, feet to the north, looking down; strings work a floating island."""
    m = Layer(64, 64)
    anchors = draw_island(m, k=8, ty=35)
    h1, h2 = hanging_bot(m)
    strings(m, [(h1, anchors[0]), (h2, anchors[2])])
    return tile(m)


def h2_cloud_halo_island():
    """The same robot hanging from a cloud ceiling, its halo under its upside-down head, working an island."""
    m = Layer(64, 64)
    anchors = draw_island(m, k=8, ty=36)
    h1, h2 = hanging_bot(m, shoulders_y=5, halo=True)
    cloud_band(m, 3)
    strings(m, [(h1, anchors[0]), (h2, anchors[2])])
    return tile(m)


def h3_edge_player():
    """Hanging from the top edge, working a small player standing on a block."""
    from puppets import player
    from round9 import grass
    m = Layer(64, 64)
    grass(m, 32, 52, 5, 4)
    a = player(m, 32, 38)
    h1, h2 = hanging_bot(m)
    strings(m, [(h1, a[0]), (h2, a[2])])
    return tile(m)


VARIANTS = {'hang-edge-island': h1_edge_island, 'hang-cloud-halo': h2_cloud_halo_island, 'hang-edge-player': h3_edge_player}
