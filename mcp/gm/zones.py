#!/usr/bin/env python3
"""Охраняемые зоны ведущего: места, куда его разрушительные действия не должны ни лететь, ни падать — стоянки
кораблей и самолётов игроков, базы, место сбора. Удары, взрывы, полёты с маршрутом и постройки сверяются с ними
до пуска.

Зоны — круги на карте (x, z, радиус в блоках) в zones.json рядом со скриптом (или в файле из GM_ZONES), общие для
всех сессий ведущего (запись под flock); высота не учитывается — с запасом. Проверка пути: от места пуска через
точки via к цели, у цели — ещё круг разброса; конфликт — код выхода 1 и точка обхода для via.

  zones.py add <имя> <x> <z> <радиус> [--note текст] [--ttl минут]   добавить или передвинуть зону
  zones.py rm <имя>                                                     убрать
  zones.py list                                                         все зоны (истёкшие убираются)
  zones.py check --from X Z [--via X Z]... --to X Z [--spread N] [--margin M]
  zones.py check --to X Z [--spread N]                                  только место падения (удар сверху)

Запас по умолчанию — 64 блока: снаряды уходят с прямой на манёвр захода, а взрыв задевает округу.
"""
import argparse
import fcntl
import json
import math
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
FILE = os.environ.get("GM_ZONES", os.path.join(HERE, "zones.json"))


class Locked:
    def __init__(self, path=None):
        self.lock = (path or FILE) + ".lock"

    def __enter__(self):
        self.fd = open(self.lock, "a")
        fcntl.flock(self.fd, fcntl.LOCK_EX)
        return self

    def __exit__(self, *exc):
        fcntl.flock(self.fd, fcntl.LOCK_UN)
        self.fd.close()


def load(path=None):
    try:
        with open(path or FILE, encoding="utf-8") as f:
            zones = json.load(f)
    except FileNotFoundError:
        return {}
    now = time.time()
    return {k: v for k, v in zones.items() if not v.get("expires") or v["expires"] > now}


def save(zones, path=None):
    path = path or FILE
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(zones, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def segment_distance(p, a, b):
    """Расстояние от точки p до отрезка a→b и доля пути t (0 — a, 1 — b) ближайшей точки."""
    ax, az = a
    bx, bz = b
    dx, dz = bx - ax, bz - az
    length2 = dx * dx + dz * dz
    t = 0.0 if length2 == 0 else max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - az) * dz) / length2))
    cx, cz = ax + t * dx, az + t * dz
    return math.hypot(p[0] - cx, p[1] - cz), t


def detour(center, clearance, a, b):
    """Точка обхода: от центра зоны в сторону, куда отрезок a→b ближе всего к краю, на clearance × 1.25."""
    _, t = segment_distance(center, a, b)
    cx, cz = a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])
    ox, oz = cx - center[0], cz - center[1]
    norm = math.hypot(ox, oz)
    if norm < 1e-6:  # путь идёт прямо через центр: в сторону, перпендикулярно пути
        dx, dz = b[0] - a[0], b[1] - a[1]
        norm = math.hypot(dx, dz) or 1.0
        ox, oz = -dz, dx
    k = clearance * 1.25 / norm
    return round(center[0] + ox * k), round(center[1] + oz * k)


def conflicts(zones, target, frm=None, via=(), spread=0, margin=64):
    """Чем путь от frm через via к target (x, z) и круг разброса у цели задевают зоны; пусто — можно."""
    target = tuple(target)
    points = ([tuple(frm)] if frm else []) + [tuple(v) for v in via] + [target]
    found = []
    for name, zone in sorted(zones.items()):
        center = (zone["x"], zone["z"])
        r = zone["r"]
        hit = math.hypot(center[0] - target[0], center[1] - target[1])
        if hit < r + spread + margin:
            where = "в зоне" if hit < r else f"в {round(hit - r)} блоках от края зоны"
            found.append(f"цель ({target[0]}, {target[1]}) с разбросом {spread} {where} «{name}» "
                         f"({zone['x']}, {zone['z']}, r={r}) — выбрать другую цель")
            continue
        legs = list(zip(points, points[1:]))
        for i, (a, b) in enumerate(legs):
            d, t = segment_distance(center, a, b)
            # снаряды расходятся к разбросу цели на последнем отрезке: у цели — на весь разброс
            need = r + margin + (spread * t if i == len(legs) - 1 else 0)
            if d < need:
                v = detour(center, need, a, b)
                where = "проходит через зону" if d < r else f"в {round(d - r)} блоках от края зоны"
                found.append(f"путь ({a[0]}, {a[1]})→({b[0]}, {b[1]}) {where} «{name}» "
                             f"({zone['x']}, {zone['z']}, r={r}); обход: --via {v[0]} {v[1]} (проверить снова)")
                break
    return found


def check(args, zones):
    found = conflicts(zones, args.to, args.frm, args.via or [], args.spread, args.margin)
    if found:
        print("НЕЛЬЗЯ:")
        for c in found:
            print("  " + c)
        return 1
    print(f"можно: зоны не задеты (зон {len(zones)}, запас {args.margin}, разброс {args.spread})")
    return 0


def main():
    p = argparse.ArgumentParser(description="Охраняемые зоны ведущего")
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("name")
    a.add_argument("x", type=int)
    a.add_argument("z", type=int)
    a.add_argument("r", type=int)
    a.add_argument("--note", default="")
    a.add_argument("--ttl", type=float, help="минут до снятия; без него — пока не уберут")
    r = sub.add_parser("rm")
    r.add_argument("name")
    sub.add_parser("list")
    c = sub.add_parser("check")
    c.add_argument("--from", dest="frm", nargs=2, type=int, metavar=("X", "Z"))
    c.add_argument("--via", nargs=2, type=int, action="append", metavar=("X", "Z"))
    c.add_argument("--to", nargs=2, type=int, required=True, metavar=("X", "Z"))
    c.add_argument("--spread", type=int, default=0)
    c.add_argument("--margin", type=int, default=64)
    args = p.parse_args()

    with Locked():
        zones = load()
        if args.cmd == "add":
            zone = {"x": args.x, "z": args.z, "r": args.r, "note": args.note, "set": time.strftime("%H:%M")}
            if args.ttl:
                zone["expires"] = time.time() + args.ttl * 60
            zones[args.name] = zone
            save(zones)
            print(f"зона «{args.name}»: ({args.x}, {args.z}), r={args.r}")
        elif args.cmd == "rm":
            if zones.pop(args.name, None) is None:
                print(f"зоны «{args.name}» нет")
                return 1
            save(zones)
            print(f"зона «{args.name}» убрана")
        elif args.cmd == "list":
            save(zones)
            if not zones:
                print("зон нет")
            for name, z in sorted(zones.items()):
                left = f", ещё {round((z['expires'] - time.time()) / 60)} мин" if z.get("expires") else ""
                print(f"{name}: ({z['x']}, {z['z']}), r={z['r']}{left} {z.get('note', '')}".rstrip())
        else:
            return check(args, zones)
    return 0


if __name__ == "__main__":
    sys.exit(main())
