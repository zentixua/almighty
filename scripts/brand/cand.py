"""Round four: candidates built on #5 (a hand reaching out of a screen) plus fresh concepts."""
from pix import Layer, hexc, up, T
from art import c, mix, finish, INK
from marks2 import island, hand2, TILE, cube, monogram
from puppets import player, string, crossbar


def monitor(m, x0, y0, x1, y1, face=True, stand=True, bar=False):
    """A plain screen: steel bezel, dark glass with scanlines; optional agent eyes and title bar."""
    m.rect(x0, y0, x1, y1, c('steel', 2))
    m.rect(x0, y0, x1, y0, c('steel', 4))
    m.rect(x0, y0, x0, y1, c('steel', 3))
    m.rect(x1, y0 + 1, x1, y1, c('steel', 1))
    gx0, gy0, gx1, gy1 = x0 + 2, y0 + (4 if bar else 2), x1 - 2, y1 - 2
    if bar:
        for i, col in enumerate(['gold', 'steel', 'steel']):
            m.rect(x0 + 3 + i * 3, y0 + 1, x0 + 4 + i * 3, y0 + 2, c(col, 3 if col == 'gold' else 4))
    m.rect(gx0, gy0, gx1, gy1, c('cyan', 0))
    for y in range(gy0 + 1, gy1 + 1, 2):
        m.rect(gx0, y, gx1, y, c('night', 3))
    if face:
        cx = (gx0 + gx1) // 2
        ey = gy0 + 3
        for ex in (cx - 7, cx + 3):
            m.rect(ex, ey, ex + 4, ey + 2, c('cyan', 3))
            m.rect(ex, ey, ex + 1, ey, c('cyan', 4))
            m.rect(ex, ey + 3, ex + 4, ey + 3, c('cyan', 1))
    if stand:
        m.rect((x0 + x1) // 2 - 3, y1 + 1, (x0 + x1) // 2 + 2, y1 + 2, c('steel', 1))
    return gx0, gy0, gx1, gy1


def emerging_hand(m, oy, glass_bottom):
    """hand2 drawn from oy; everything above glass_bottom is still inside the screen (tinted, then cropped)."""
    h = Layer(64, 64)
    tips = hand2(h, 0, oy, emblem=False)
    for y in range(64):
        for x in range(64):
            p = h.get(x, y)
            if p[3] and y <= glass_bottom:
                if y < glass_bottom - 3:
                    h.px(x, y, T)
                else:
                    h.px(x, y, mix(p, c('cyan', 2), 0.6))   # breaking through the glass
    m.paste(h, 0, 0)
    return tips


def block_cut(m, cx, ty, k, s):
    cube(m, cx, ty, k, s, 'stone')
    for r in range(2 * k):
        hw = 2 * (r + 1) if r < k else 2 * (2 * k - r)
        for x in range(-hw, hw):
            m.px(cx + x, ty + r, c('moss', 4 if (r < 2 or x <= -hw + 1) and r < k else 3))
    for u in range(2 * k):
        y0 = ty + k + u // 2 + 1
        for v in range(3):
            m.px(cx - 2 * k + u, y0 + v, c('moss', 2) if v < 2 else c('moss', 1))
            m.px(cx + 2 * k - 1 - u, y0 + v, c('moss', 1) if v < 2 else c('moss', 0))


def net(m, tips, nodes, links, ends):
    for t, n in zip(tips, nodes):
        string(m, t[0], t[1], n[0], n[1])
    for a, b in links:
        string(m, *nodes[a], *nodes[b], c('cyan', 2))
    for a, e in ends:
        string(m, *nodes[a], *e, c('cyan', 2))
        m.px(*e, c('cyan', 4))
    for (x, y) in nodes:
        m.rect(x - 1, y - 1, x + 1, y + 1, c('cyan', 3))
        m.px(x - 1, y - 1, c('cyan', 4))


# A: screen with agent eyes, hand out of it, strings to an island
def a_screen_island():
    m = Layer(64, 64)
    island(m, 32, 44, 6, sb=6, taper=2, tree=True)
    monitor(m, 9, 0, 54, 17, face=True, stand=False)
    tips = emerging_hand(m, 4, 15)
    for t, e in zip(tips, [(24, 49), (29, 52), (35, 52), (40, 49)]):
        string(m, t[0], t[1], *e); m.px(*e, c('cyan', 4))
    return finish(m, TILE)


# B: screen with eyes, hand, strings to a player standing on a block
def b_screen_player():
    m = Layer(64, 64)
    block_cut(m, 32, 52, 5, 3)
    monitor(m, 9, 0, 54, 17, face=True, stand=False)
    tips = emerging_hand(m, 4, 15)
    anchors = player(m, 32, 36)
    for t, a in zip([tips[0], tips[1], tips[3]], anchors):
        string(m, t[0], t[1], a[0], a[1])
    return finish(m, TILE)


# C: #5 + #4: screen hand, strings branch into a network that holds the block
def c_screen_net():
    m = Layer(64, 64)
    block_cut(m, 32, 46, 6, 6)
    monitor(m, 9, 0, 54, 17, face=True, stand=False)
    tips = emerging_hand(m, 4, 15)
    nodes = [(18, 37), (28, 39), (37, 38), (46, 36)]
    net(m, tips, nodes, [(0, 1), (1, 2), (2, 3)], [(0, (24, 50)), (1, (29, 52)), (2, (35, 52)), (3, (40, 50))])
    return finish(m, TILE)


# D: robot head puppeteer: strings from a control bar under a robot head to a player
def d_robot_puppeteer():
    m = Layer(64, 64)
    s = lambda i: c('steel', i)
    m.rect(22, 2, 41, 17, s(3)); m.rect(22, 2, 41, 3, s(4)); m.rect(22, 2, 23, 17, s(4)); m.rect(39, 4, 41, 17, s(2))
    m.rect(25, 7, 38, 12, c('cyan', 0))
    m.rect(26, 8, 30, 10, c('cyan', 3)); m.rect(33, 8, 37, 10, c('cyan', 3))
    m.rect(26, 8, 27, 8, c('cyan', 4)); m.rect(33, 8, 34, 8, c('cyan', 4))
    m.rect(29, 14, 34, 15, s(1))
    m.rect(19, 7, 21, 12, s(2)); m.rect(42, 7, 44, 12, s(1))
    ends = crossbar(m, 32, 22, 15)
    m.rect(31, 18, 32, 21, s(1))
    anchors = player(m, 32, 38)
    for e, a in zip(ends, anchors):
        string(m, e[0], e[1], a[0], a[1])
    m.rect(20, 59, 43, 60, c('stone', 2)); m.rect(20, 59, 43, 59, c('stone', 3))
    return finish(m, TILE)


# E: cursor holds the bar; the puppet's eyes carry the agent's light
def e_cursor_eyes():
    m = Layer(64, 64)
    arrow = ["4.........", "44........", "434.......", "4334......", "43334.....", "433334....", "4333334...",
             "43333334..", "433333334.", "4333332222", "433223....", "432.223...", "42..223...", "4....223..",
             ".....223..", "......22.."]
    island(m, 32, 52, 5, sb=4, taper=2, tree=False, roots=False)
    ends = crossbar(m, 32, 20, 14)
    m.sprite(arrow, 31, 4, {'4': c('steel', 4), '3': hexc('#E9F2F6'), '2': c('steel', 3)})
    anchors = player(m, 32, 35)
    m.px(30, 38, c('cyan', 4)); m.px(33, 38, c('cyan', 4))
    for e, a in zip(ends, anchors):
        string(m, e[0], e[1], a[0], a[1])
    return finish(m, TILE)


# F: laptop-style window (title bar) with the hand out of it, network strings, island
def f_window_net_island():
    m = Layer(64, 64)
    island(m, 32, 44, 6, sb=6, taper=2, tree=False)
    monitor(m, 9, 0, 54, 17, face=False, stand=False, bar=True)
    m.rect(14, 6, 16, 8, c('cyan', 3)); m.rect(18, 7, 19, 8, c('cyan', 2))     # a prompt mark: '>' and cursor
    tips = emerging_hand(m, 4, 15)
    nodes = [(18, 36), (28, 38), (37, 38), (46, 36)]
    net(m, tips, nodes, [(0, 1), (1, 2), (2, 3)], [(0, (22, 48)), (1, (29, 51)), (2, (35, 51)), (3, (42, 48))])
    return finish(m, TILE)


CANDS = {'A_screen_island': a_screen_island, 'B_screen_player': b_screen_player, 'C_screen_net': c_screen_net,
         'D_robot_puppeteer': d_robot_puppeteer, 'E_cursor_eyes': e_cursor_eyes, 'F_window_net': f_window_net_island}


def player2(m, cx, top, eyes=False):
    """Bigger puppet: 10 px wide, 25 tall, arms lifted to the strings. Returns (left hand, head, right hand)."""
    skin, skin2 = c('soil', 4), c('soil', 3)
    m.rect(cx - 4, top, cx + 3, top + 7, skin)
    m.rect(cx + 2, top + 1, cx + 3, top + 7, skin2)
    m.rect(cx - 4, top, cx + 3, top + 1, c('night', 4))          # hair
    ec = c('cyan', 4) if eyes else INK
    m.rect(cx - 3, top + 4, cx - 2, top + 4, ec); m.rect(cx + 1, top + 4, cx + 2, top + 4, ec)
    m.rect(cx - 4, top + 8, cx + 3, top + 16, c('gold', 2))
    m.rect(cx + 2, top + 8, cx + 3, top + 16, c('gold', 1))
    for i in range(6):
        m.rect(cx - 6 - i // 2, top + 8 - i, cx - 5 - i // 2, top + 8 - i, c('steel', 3))
        m.rect(cx + 4 + i // 2, top + 8 - i, cx + 5 + i // 2, top + 8 - i, c('steel', 2))
    m.rect(cx - 9, top + 1, cx - 7, top + 3, skin); m.rect(cx + 6, top + 1, cx + 8, top + 3, skin2)
    m.rect(cx - 4, top + 17, cx - 1, top + 24, c('night', 4))
    m.rect(cx, top + 17, cx + 3, top + 24, c('night', 3))
    return (cx - 8, top + 1), (cx, top - 1), (cx + 7, top + 1)


def hand_bar(m, oy):
    """Simplified big hand, palm down, holding a marionette cross under the fingers."""
    s = lambda i: c('steel', i)
    for y in range(oy, oy + 9):                      # back of hand
        a, b = 20 - (y - oy) // 3, 43 + (y - oy) // 3
        m.rect(a, y, b, y, s(3)); m.px(a, y, s(4)); m.rect(b - 1, y, b, y, s(2))
    m.rect(20, oy, 43, oy, s(4))
    for x0 in (18, 25, 32, 39):                      # four thick fingers, curled over the bar
        m.rect(x0, oy + 9, x0 + 5, oy + 14, s(3))
        m.rect(x0, oy + 9, x0, oy + 14, s(4)); m.rect(x0 + 5, oy + 9, x0 + 5, oy + 14, s(2))
        m.rect(x0 + 1, oy + 15, x0 + 4, oy + 15, s(2))
    m.rect(13, oy + 4, 17, oy + 10, s(3)); m.rect(13, oy + 4, 13, oy + 10, s(4))      # thumb
    # the bar, gripped: wood with the agent's light at the string eyes
    y = oy + 16
    m.rect(9, y, 54, y + 2, c('soil', 3)); m.rect(9, y + 2, 54, y + 2, c('soil', 1)); m.rect(9, y, 54, y, c('soil', 4))
    return [(10, y + 3), (31, y + 3), (53, y + 3)]


# G: screen, hand breaking out of it, gripping the marionette cross; strings to a player
def g_screen_bar_player():
    m = Layer(64, 64)
    monitor(m, 10, 0, 53, 12, face=True, stand=False)
    ends = hand_bar(m, 11)
    for y in range(11, 15):
        for x in range(14, 50):
            p = m.get(x, y)
            if p[3] and p[:3] != c('steel', 2)[:3]:
                m.px(x, y, mix(p, c('cyan', 2), 0.5))
    a = player2(m, 32, 38)
    for e, t in zip(ends, a):
        string(m, e[0], e[1], t[0], t[1])
    return finish(m, TILE)


# H: the monitor is the puppeteer: a screen face, the cross hanging from it, a player below
def h_monitor_puppeteer():
    m = Layer(64, 64)
    monitor(m, 12, 1, 51, 22, face=True, stand=False)
    m.rect(30, 23, 33, 26, c('steel', 1))
    y = 27
    m.rect(8, y, 55, y + 2, c('soil', 3)); m.rect(8, y + 2, 55, y + 2, c('soil', 1)); m.rect(8, y, 55, y, c('soil', 4))
    a = player2(m, 32, 38)
    for e, t in zip([(9, y + 3), (31, y + 3), (54, y + 3)], a):
        string(m, e[0], e[1], t[0], t[1])
    return finish(m, TILE)


# I: a hand made of light holding the cross; the puppet's eyes and shirt carry the same light
def i_light_hand_player():
    from puppets import gridify
    m = Layer(64, 64)
    h = Layer(64, 64)
    hand_bar(h, 1)
    for y in range(64):                               # keep only the hand (not the bar) for the light pass
        for x in range(64):
            if y >= 17:
                h.px(x, y, T)
    g = gridify(h)
    y = 17
    m.rect(9, y, 54, y + 2, c('soil', 3)); m.rect(9, y + 2, 54, y + 2, c('soil', 1)); m.rect(9, y, 54, y, c('soil', 4))
    m.paste(g, 0, 0)
    a = player2(m, 32, 36, eyes=True)
    for e, t in zip([(10, 20), (31, 20), (53, 20)], a):
        string(m, e[0], e[1], t[0], t[1])
    return finish(m, TILE)


CANDS.update({'G_screen_bar_player': g_screen_bar_player, 'H_monitor_puppeteer': h_monitor_puppeteer,
              'I_light_hand_player': i_light_hand_player})


# J: the agent as a player: a blocky body whose head is a screen with the agent's eyes, standing on an island
def j_screen_head_player():
    m = Layer(64, 64)
    island(m, 32, 46, 6, sb=5, taper=2, tree=False)
    s = lambda i: c('steel', i)
    monitor(m, 19, 2, 44, 21, face=True, stand=False)
    m.rect(28, 22, 35, 23, s(1))
    m.rect(25, 24, 38, 38, c('gold', 2)); m.rect(36, 24, 38, 38, c('gold', 1)); m.rect(25, 24, 26, 38, c('gold', 3))
    m.rect(19, 24, 23, 35, c('gold', 2)); m.rect(40, 24, 44, 35, c('gold', 1))
    m.rect(19, 36, 23, 38, c('soil', 4)); m.rect(40, 36, 44, 38, c('soil', 3))
    m.rect(25, 39, 31, 50, c('night', 4)); m.rect(32, 39, 38, 50, c('night', 3))
    return finish(m, TILE)


# K: a possessed puppet: strings rise out of frame from a player whose eyes carry the agent's light
def k_possessed():
    m = Layer(64, 64)
    a = player2(m, 32, 22, eyes=True)
    island(m, 32, 50, 5, sb=4, taper=2, tree=False, roots=False)
    for (x, y) in a:
        string(m, x, 0, x, y)
    for (x, y) in a:
        m.px(x, y, c('cyan', 4))
    return finish(m, TILE)


# O: RTS command: a selection box around a player and a cursor giving the order
def o_select_command():
    m = Layer(64, 64)
    island(m, 30, 44, 7, sb=6, taper=2, tree=False)
    player2(m, 30, 23)
    for x in range(14, 47):
        if x % 4 < 2:
            m.px(x, 18, c('cyan', 3)); m.px(x, 50, c('cyan', 3))
    for y in range(18, 51):
        if y % 4 < 2:
            m.px(14, y, c('cyan', 3)); m.px(46, y, c('cyan', 3))
    for (x, y) in [(14, 18), (46, 18), (14, 50), (46, 50)]:
        m.rect(x - 1, y - 1, x + 1, y + 1, c('cyan', 4))
    arrow = ["4.........", "44........", "434.......", "4334......", "43334.....", "433334....", "4333334...",
             "43333334..", "433333334.", "4333332222", "433223....", "432.223...", "42..223...", "4....223..",
             ".....223..", "......22.."]
    m.sprite(arrow, 44, 4, {'4': c('steel', 4), '3': hexc('#E9F2F6'), '2': c('steel', 3)})
    return finish(m, TILE)


CANDS.update({'J_screen_head': j_screen_head_player, 'K_possessed': k_possessed, 'O_select': o_select_command})


SPOT_BG, SPOT = hexc('#28224A'), hexc('#3A3368')


def finish_spot(m, cx=32, cy=44, r=20):
    """Brighter tile with a stage spotlight disc (flat, two tones, no gradient), then outline + AA."""
    from art import outline_outer, aa_corners
    outline_outer(m)
    t = Layer(64, 64, SPOT_BG)
    for y in range(64):
        for x in range(64):
            if (x - cx) ** 2 + ((y - cy) * 1.15) ** 2 <= r * r:
                t.px(x, y, SPOT)
    t.paste(m, 0, 0)
    aa_corners(t, bg=SPOT_BG)
    return t


def robot_hand_bar(m, oy):
    """A clearly mechanical hand: plated back, cyan joint lights, gripping the cross."""
    s = lambda i: c('steel', i)
    for y in range(oy, oy + 10):
        a, b = 19 - (y - oy) // 3, 44 + (y - oy) // 3
        m.rect(a, y, b, y, s(3)); m.px(a, y, s(4)); m.rect(b - 1, y, b, y, s(2))
    m.rect(19, oy, 44, oy, s(4))
    m.rect(26, oy + 2, 37, oy + 6, s(2)); m.rect(26, oy + 2, 37, oy + 2, s(1))
    m.rect(30, oy + 3, 33, oy + 5, c('cyan', 3)); m.px(30, oy + 3, c('cyan', 4))
    for x0 in (17, 24, 32, 39):
        m.rect(x0, oy + 10, x0 + 6, oy + 15, s(3))
        m.rect(x0, oy + 10, x0, oy + 15, s(4)); m.rect(x0 + 6, oy + 10, x0 + 6, oy + 15, s(2))
        m.rect(x0 + 2, oy + 11, x0 + 4, oy + 12, c('cyan', 3))           # joint lights
    m.rect(11, oy + 4, 16, oy + 11, s(3)); m.rect(11, oy + 4, 11, oy + 11, s(4)); m.rect(12, oy + 6, 14, oy + 7, c('cyan', 3))
    y = oy + 16
    m.rect(7, y, 56, y + 2, c('soil', 3)); m.rect(7, y + 2, 56, y + 2, c('soil', 1)); m.rect(7, y, 56, y, c('soil', 4))
    return [(8, y + 3), (31, y + 3), (55, y + 3)]


def puppet_strings(m, ends, a):
    for e, t in zip(ends, a):
        string(m, e[0], e[1], t[0], t[1], c('cyan', 4))


def p_monitor_spot():
    m = Layer(64, 64)
    monitor(m, 10, 1, 53, 21, face=True, stand=False)
    m.rect(30, 22, 33, 24, c('steel', 1))
    y = 25
    m.rect(6, y, 57, y + 2, c('soil', 3)); m.rect(6, y + 2, 57, y + 2, c('soil', 1)); m.rect(6, y, 57, y, c('soil', 4))
    a = player2(m, 32, 37, eyes=True)
    puppet_strings(m, [(7, y + 3), (31, y + 3), (56, y + 3)], a)
    return finish_spot(m)


def q_robot_hand_spot():
    m = Layer(64, 64)
    ends = robot_hand_bar(m, 2)
    a = player2(m, 32, 36, eyes=True)
    puppet_strings(m, ends, a)
    return finish_spot(m)


def r_cursor_spot():
    m = Layer(64, 64)
    arrow = ["4...........", "44..........", "434.........", "4334........", "43334.......", "433334......",
             "4333334.....", "43333334....", "433333334...", "4333333334..", "43333333334.", "433333322222",
             "4332233.....", "432.2233....", "42...223....", "4....2233...", ".....2233...", "......22...."]
    y = 22
    m.rect(6, y, 57, y + 2, c('soil', 3)); m.rect(6, y + 2, 57, y + 2, c('soil', 1)); m.rect(6, y, 57, y, c('soil', 4))
    m.sprite(arrow, 31, 3, {'4': c('steel', 4), '3': hexc('#F2F8FA'), '2': c('steel', 3)})
    a = player2(m, 32, 36, eyes=True)
    puppet_strings(m, [(7, y + 3), (31, y + 3), (56, y + 3)], a)
    return finish_spot(m)


CANDS.update({'P_monitor_spot': p_monitor_spot, 'Q_robot_hand_spot': q_robot_hand_spot, 'R_cursor_spot': r_cursor_spot})


def sparkle(m, cx, cy, r, mat='cyan', lit=True):
    """Four-point star with concave flanks: (|x|/r)^0.5 + (|y|/r)^0.5 <= 1, shaded upper-left light."""
    for y in range(-r, r + 1):
        for x in range(-r, r + 1):
            v = (abs(x) / r) ** 0.5 + (abs(y) / r) ** 0.5
            if v <= 1.0:
                if v > 0.86:
                    col = c(mat, 2)
                elif (x <= 0 and y <= 0):
                    col = c(mat, 4)
                elif x + y < 0:
                    col = c(mat, 3)
                else:
                    col = c(mat, 2) if (x > 0 and y > 0) else c(mat, 3)
                m.px(cx + x, cy + y, col)
    m.px(cx, cy, c(mat, 4))


def s1_island_sparkle():
    m = Layer(64, 64)
    island(m, 32, 36, 8, sb=8, taper=2, tree=False)
    sparkle(m, 30, 14, 12)
    sparkle(m, 46, 6, 5)
    for y in range(27, 38, 2):
        m.px(30, y, c('cyan', 3))
    return finish_spot(m, 32, 40, 24)


ARROW = ["4...........", "44..........", "434.........", "4334........", "43334.......", "433334......",
         "4333334.....", "43333334....", "433333334...", "4333333334..", "43333333334.", "433333322222",
         "4332233.....", "432.2233....", "42...223....", "4....2233...", ".....2233...", "......22...."]


def s3_cursor_sparkle_island():
    m = Layer(64, 64)
    island(m, 28, 30, 9, sb=9, taper=2, tree=False)
    m.sprite(ARROW, 36, 22, {'4': c('steel', 4), '3': hexc('#F2F8FA'), '2': c('steel', 3)})
    sparkle(m, 53, 12, 8)
    sparkle(m, 43, 5, 4)
    return finish_spot(m, 30, 40, 24)


def s6_sparkle_puppet():
    m = Layer(64, 64)
    sparkle(m, 32, 13, 12)
    sparkle(m, 50, 6, 4)
    a = player2(m, 32, 36)
    for t in a:
        string(m, 32 + (t[0] - 32) // 3, 22, t[0], t[1], c('cyan', 3))
    return finish_spot(m, 32, 46, 18)


def s7_sparkle_cube():
    """The agent's sparkle set into the world: a cut-layer cube whose top face carries the star."""
    m = Layer(64, 64)
    block_cut(m, 32, 26, 11, 14)
    sparkle(m, 32, 12, 11)
    sparkle(m, 49, 5, 4)
    return finish_spot(m, 32, 36, 26)


CANDS.update({'S1_island_sparkle': s1_island_sparkle, 'S3_cursor_sparkle': s3_cursor_sparkle_island,
              'S6_sparkle_puppet': s6_sparkle_puppet, 'S7_sparkle_cube': s7_sparkle_cube})


BIG_A = [
    "....######....",
    "...########...",
    "..##########..",
    ".#####..#####.",
    "#####....#####",
    "#####....#####",
    "#####....#####",
    "##############",
    "##############",
    "##############",
    "#####....#####",
    "#####....#####",
    "#####....#####",
    "#####....#####",
    "#####....#####",
    "#####....#####",
]
BIG_I = ["#####"] * 16


def big_ai(m, x, y, depth=3):
    """Extruded letters: cyan face lit from the top-left, the depth in the ramp's dark steps (part of the silhouette)."""
    cells = set()
    for gy, row in enumerate(BIG_A):
        for gx, ch in enumerate(row):
            if ch == '#':
                cells.add((x + gx, y + gy))
    for gy, row in enumerate(BIG_I):
        for gx, ch in enumerate(row):
            if ch == '#':
                cells.add((x + 17 + gx, y + gy))
    for d in range(depth, 0, -1):
        for (cx, cy) in cells:
            m.px(cx + d, cy + d, c('cyan', 0) if d > 1 else c('cyan', 1))
    for (cx, cy) in cells:
        top = (cx, cy - 1) not in cells
        left = (cx - 1, cy) not in cells
        m.px(cx, cy, c('cyan', 4) if top else (c('cyan', 3) if left else c('cyan', 2)))
    return cells


def l1_ai_strings_island():
    m = Layer(64, 64)
    island(m, 32, 40, 7, sb=7, taper=2, tree=True)
    big_ai(m, 20, 2)
    for x0, x1, y1 in [(22, 22, 47), (33, 31, 52), (39, 37, 51)]:
        string(m, x0, 21, x1, y1, c('cyan', 3)); m.px(x1, y1, c('cyan', 4))
    return finish_spot(m, 32, 44, 22)


def l3_ai_strings_player():
    m = Layer(64, 64)
    big_ai(m, 20, 1)
    a = player2(m, 32, 37)
    for x0, t in zip([22, 30, 40], a):
        string(m, x0, 20, t[0], t[1], c('cyan', 3))
    return finish_spot(m, 32, 48, 18)


CANDS.update({'L1_ai_island': l1_ai_strings_island, 'L3_ai_player': l3_ai_strings_player})


RED = [hexc(h) for h in ('#2A0F22', '#561A2E', '#8E2A35', '#C44A3E', '#E8805A')]


def theater(m):
    """Puppet stage: side curtains with folds, a valance with a gold fringe, a turf stage floor."""
    for x in range(0, 10):
        fold = [3, 2, 1, 2, 3, 3, 2, 1, 1, 2][x]
        for y in range(0, 64):
            m.px(x, y, RED[fold])
            m.px(63 - x, y, RED[max(0, fold - 1)])
    # curtains gather toward the floor: cut a curve so the stage opens wider at the bottom
    for y in range(40, 64):
        cut = (y - 40) // 4
        for x in range(10 - cut, 10):
            m.px(x, y, T); m.px(63 - x, y, T)
    m.rect(0, 0, 63, 4, RED[2]); m.rect(0, 0, 63, 0, RED[3]); m.rect(0, 4, 63, 4, RED[1])
    for x in range(0, 64, 2):
        m.px(x, 5, c('gold', 3)); m.px(x + 1, 5, c('gold', 2))
    # stage floor: a strip of the world
    m.rect(0, 57, 63, 58, c('moss', 3)); m.rect(0, 57, 63, 57, c('moss', 4))
    m.rect(0, 59, 63, 59, c('moss', 1))
    m.rect(0, 60, 63, 63, c('soil', 2)); m.rect(0, 62, 63, 62, c('soil', 1))


def wide_bar_strings(m, xs, ybar, a):
    for x0, t in zip(xs, a):
        string(m, x0, ybar, t[0], t[1], c('cyan', 3))
        m.px(t[0], t[1], c('cyan', 4))


def t1_theater_ai():
    m = Layer(64, 64)
    theater(m)
    big_ai(m, 20, 7)
    a = player2(m, 32, 33, eyes=True)
    wide_bar_strings(m, [21, 30, 40], 26, a)
    return finish_spot(m, 32, 46, 18)


def t2_theater_screen():
    m = Layer(64, 64)
    theater(m)
    monitor(m, 16, 6, 47, 21, face=True, stand=False)
    y = 22
    m.rect(12, y, 51, y + 1, c('steel', 3)); m.rect(12, y + 1, 51, y + 1, c('steel', 1))
    a = player2(m, 32, 34, eyes=True)
    wide_bar_strings(m, [13, 31, 50], y + 2, a)
    return finish_spot(m, 32, 46, 18)


def t3_ai_wide_player():
    m = Layer(64, 64)
    big_ai(m, 20, 2)
    m.rect(8, 21, 55, 23, c('steel', 3)); m.rect(8, 21, 55, 21, c('steel', 4)); m.rect(8, 23, 55, 23, c('steel', 1))
    a = player2(m, 32, 36, eyes=True)
    wide_bar_strings(m, [9, 31, 54], 24, a)
    return finish_spot(m, 32, 48, 18)


CANDS.update({'T1_theater_ai': t1_theater_ai, 'T2_theater_screen': t2_theater_screen, 'T3_ai_wide': t3_ai_wide_player})


def marionette(m, cx, top, eyes=True):
    """A blocky wooden marionette: cube head, joint pins, limp arms raised by strings, one knee lifted.
    Returns string anchors (left hand, head, right hand, right knee)."""
    w = lambda i: c('soil', i)
    pin = c('soil', 0)
    # head
    m.rect(cx - 4, top, cx + 3, top + 7, w(4)); m.rect(cx + 2, top + 1, cx + 3, top + 7, w(3))
    m.rect(cx - 4, top + 7, cx + 3, top + 7, w(3))
    ec = c('cyan', 4) if eyes else c('soil', 1)
    m.rect(cx - 3, top + 3, cx - 2, top + 4, ec); m.rect(cx + 1, top + 3, cx + 2, top + 4, ec)
    m.px(cx - 1, top + 8, pin)                                             # neck pin
    # torso
    m.rect(cx - 4, top + 9, cx + 3, top + 17, w(3)); m.rect(cx + 2, top + 9, cx + 3, top + 17, w(2))
    m.rect(cx - 4, top + 9, cx - 4, top + 17, w(4))
    m.rect(cx - 4, top + 13, cx + 3, top + 13, w(2))                       # waist seam
    # arms: upper arm out, forearm up, pins at shoulder and elbow
    m.px(cx - 5, top + 9, pin); m.px(cx + 4, top + 9, pin)
    m.rect(cx - 9, top + 9, cx - 6, top + 10, w(3)); m.rect(cx + 5, top + 9, cx + 8, top + 10, w(2))
    m.px(cx - 10, top + 9, pin); m.px(cx + 9, top + 9, pin)
    m.rect(cx - 11, top + 3, cx - 10, top + 8, w(4)); m.rect(cx + 9, top + 3, cx + 10, top + 8, w(2))
    # legs: left hangs, right knee lifted by its string
    m.px(cx - 2, top + 18, pin); m.px(cx + 1, top + 18, pin)
    m.rect(cx - 3, top + 19, cx - 1, top + 24, w(3)); m.px(cx - 2, top + 25, pin)
    m.rect(cx - 3, top + 26, cx - 1, top + 30, w(3)); m.rect(cx - 3, top + 30, cx, top + 30, w(2))
    m.rect(cx + 1, top + 19, cx + 5, top + 21, w(2)); m.px(cx + 6, top + 20, pin)
    m.rect(cx + 5, top + 22, cx + 7, top + 26, w(2)); m.rect(cx + 5, top + 26, cx + 8, top + 26, w(1))
    return [(cx - 11, top + 2), (cx - 1, top - 1), (cx + 10, top + 2), (cx + 6, top + 19)]


def f1_theater_screen_doll():
    m = Layer(64, 64)
    theater(m)
    monitor(m, 18, 6, 45, 19, face=True, stand=False)
    y = 20
    m.rect(11, y, 52, y + 1, c('steel', 3)); m.rect(11, y + 1, 52, y + 1, c('steel', 1)); m.rect(11, y, 52, y, c('steel', 4))
    a = marionette(m, 32, 26)
    for x0, t in zip([12, 30, 51, 46], a):
        string(m, x0, y + 2, t[0], t[1], c('cyan', 3))
    return finish_spot(m, 32, 46, 18)


def f2_theater_ai_doll():
    m = Layer(64, 64)
    theater(m)
    big_ai(m, 20, 6, depth=2)
    a = marionette(m, 32, 27)
    for x0, t in zip([21, 30, 40, 38], a):
        string(m, x0, 23, t[0], t[1], c('cyan', 3))
    return finish_spot(m, 32, 46, 18)


def f3_screen_hand_doll():
    m = Layer(64, 64)
    monitor(m, 9, 0, 54, 15, face=True, stand=False)
    tips = emerging_hand(m, 2, 13)
    a = marionette(m, 32, 30)
    m.rect(14, 61, 49, 62, c('moss', 3)); m.rect(14, 61, 49, 61, c('moss', 4)); m.rect(14, 63, 49, 63, c('soil', 2))
    for tp, t in zip([tips[0], tips[1], tips[3], tips[2]], a):
        string(m, tp[0], tp[1], t[0], t[1], c('cyan', 3))
    return finish_spot(m, 32, 46, 20)


CANDS.update({'F1_theater_screen_doll': f1_theater_screen_doll, 'F2_theater_ai_doll': f2_theater_ai_doll,
              'F3_screen_hand_doll': f3_screen_hand_doll})
