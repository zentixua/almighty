#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "mcp>=1.2,<2",
# ]
# ///
"""Инструменты ведущего (MCP-сервер gm) для его сессий и командная строка для людей.

  uv run mcp/gm/gm.py init <каталог>             — завести каталог сервера (оверлей): config.toml, skill/, evals/, git
  uv run mcp/gm/gm.py mcp                       — MCP-сервер роли GM_ROLE (его запускает диспетчер в сессии)
  uv run mcp/gm/gm.py tasks                     — очередь задач
  uv run mcp/gm/gm.py log [--kind K] [--last N] — журнал опыта
  uv run mcp/gm/gm.py recipes                   — рецепты (и негодные — с ошибками)
  uv run mcp/gm/gm.py run <рецепт> ['{…}'] [--test] — выполнить рецепт (--test — на тестовом сервере)

Настройки — config.toml оверлея (GM_CONFIG или --config).
Роли: voice — reply, task, task_cancel; worker — progress, done, fail; teacher — experience, recipe_test;
всем — tasks, recipes, recipe_run (голосу — только instant), zones, lesson.
"""
import argparse
import functools
import hashlib
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bridge as bridgemod  # noqa: E402
import conf  # noqa: E402
import guard  # noqa: E402
import recipes as recipesmod  # noqa: E402
import store as storemod  # noqa: E402
import zones as zonesmod  # noqa: E402

READS = {"entities", "player", "blocks", "see", "script", "map", "look", "status", "bot", "bots", "job", "jobs",
         "rules", "areas", "events"}
MAX_REPLIES = 4


class Gm:
    """Общее для инструментов: настройки, память, мост, навык этой сессии."""

    def __init__(self, cfg, role):
        self.cfg, self.role = cfg, role
        self.store = storemod.Store(cfg["paths"]["db"])
        self.live = bridgemod.of(cfg, "bridge")
        self.test = bridgemod.of(cfg, "test_bridge")
        self.skill = os.environ.get("GM_SKILL") or os.path.join(cfg["paths"]["overlay"], "skill")
        self.task_id = int(os.environ["GM_TASK"]) if os.environ.get("GM_TASK") else None

    def requester(self):
        return guard.requester(self.cfg, self.role, self.store)

    def ctx(self):
        return guard.Ctx(self.cfg, self.role, self.requester())

    def recipes(self):
        return recipesmod.load_all([os.path.join(self.cfg["paths"]["kit"], "skill", "recipes"),
                                    os.path.join(self.skill, "recipes")])

    # ---------------------------------------------------------------- голос

    def reply(self, text, to=None, style="chat"):
        turn = self.store.get("voice.turn") or {}
        player = turn.get("player")
        replies = [e for e in self.store.logs(turn.get("log_id", 0), ["reply"])]
        if len(replies) >= MAX_REPLIES:
            raise GmError(f"уже {len(replies)} сообщения за ход: хватит, одно ясное сообщение лучше потока")
        if to is None and turn.get("channel") == "gm" and player:
            to = [player]  # личное /gm — ответ лично
        self.live.call("say", {"text": text, "to": to, "style": style})
        self.store.log("voice", "reply", player, text=text, to=to, style=style,
                       ms=round((time.time() - turn["started"]) * 1000) if turn.get("started") else None)
        return "сказано" + (f" ({', '.join(to)})" if to else " всем")

    def task(self, text, recipe=None, params=None, resources=None, minutes=None):
        player = self.requester()
        if not player:
            raise GmError("нет игрока: чей это запрос?")
        norm = " ".join(text.lower().split())
        for t in self.store.tasks(player, open_only=True):
            other = " ".join(t["text"].lower().split())
            if norm == other or norm in other or other in norm:
                raise GmError(f"у {player} уже есть такая задача #{t['id']} ({t['status']}): «{t['text']}» — "
                              "не дублировать; отменить — task_cancel")
        if recipe:
            found, errors = self.recipes()
            if recipe not in found:
                raise GmError(f"рецепта {recipe!r} нет" + (f": {errors[recipe]}" if recipe in errors else ""))
            r = found[recipe]
            values = recipesmod.bind(r, params)
            resources = sorted(set(resources or []) | set(recipesmod.resources(r, values)))
        limit = self.cfg["limits"]["task_minutes"]
        minutes = min(minutes or limit, limit * 3)
        turn = self.store.get("voice.turn") or {}
        channel = turn.get("channel") if turn.get("player") == player else None
        task_id = self.store.task_add(player, text, channel=channel, recipe=recipe, params=params, resources=resources,
                                      minutes=minutes)
        self.store.log(self.role, "task_add", player, task_id, text=text, recipe=recipe, params=params)
        ahead = len([t for t in self.store.tasks(open_only=True, limit=100) if t["id"] < task_id])
        return f"задача #{task_id} в очереди" + (f" (впереди {ahead})" if ahead else " — исполнитель берёт сейчас")

    def task_cancel(self, task_id):
        t = self.store.task(task_id)
        if not t:
            raise GmError(f"задачи #{task_id} нет")
        if t["status"] not in storemod.OPEN:
            return f"задача #{task_id} уже {t['status']}"
        ctx = self.ctx()
        if ctx.requester != t["player"] and not ctx.owner():
            raise GmError(f"задача #{task_id} — {t['player']}: отменить её может он сам или владелец")
        self.store.task_update(task_id, cancel=1)
        self.store.log(self.role, "task_cancel", t["player"], task_id)
        return f"задача #{task_id} отменяется"

    # ---------------------------------------------------------------- исполнитель

    def _my_task(self):
        if self.task_id is None:
            raise GmError("нет задачи этой сессии")
        return self.store.task(self.task_id)

    def progress(self, text):
        t = self._my_task()
        self.store.task_update(t["id"], progress=text)
        self.store.log("worker", "progress", t["player"], t["id"], text=text)
        return "записано"

    def done(self, summary, check_method, check_params=None):
        t = self._my_task()
        if check_method not in READS:
            raise GmError(f"проверка — чтение мира ({', '.join(sorted(READS))}), а не {check_method}")
        reason = guard.check_call(check_method, check_params or {}, self.ctx())
        if reason:
            raise GmError("проверка не прошла охрану: " + reason)
        params = dict(check_params or {})
        if check_method == "see":
            params["image"] = False  # доказательство — описание, картинка игроку не уходит
        result = self.live.call(check_method, params)
        evidence = {"method": check_method, "params": check_params, "result": _strip(result)}
        if not self.store.finish(t["id"], "done", summary, evidence):
            return f"задача #{t['id']} уже закрыта ({self.store.task(t['id'])['status']}) — итог не записан"
        self.store.log("worker", "task_done", t["player"], t["id"], summary=summary, evidence=evidence)
        return "готово записано; проверка в мире:\n" + json.dumps(evidence["result"], ensure_ascii=False)[:1500]

    def fail(self, reason):
        t = self._my_task()
        self.store.finish(t["id"], "failed", reason)
        self.store.log("worker", "task_failed", t["player"], t["id"], reason=reason)
        return "записано: не вышло"

    # ---------------------------------------------------------------- наставник

    def experience(self, after=None, limit=300):
        after = self.store.get("teacher.seen", 0) if after is None else after
        rows = self.store.logs(after, limit=limit)
        lines = []
        for e in rows:
            who = f" {e['player']}" if e["player"] else ""
            task = f" #{e['task']}" if e["task"] else ""
            lines.append(f"[{e['id']}] {time.strftime('%d.%m %H:%M:%S', time.localtime(e['ts']))} {e['role']} "
                         f"{e['kind']}{who}{task}: {json.dumps(e['data'], ensure_ascii=False)[:600]}")
        return "\n".join(lines) or "нового опыта нет"

    def recipe_test(self, name, params=None):
        if not self.test:
            raise GmError("тестового сервера нет (test_bridge в config.toml): рецепт не проверить")
        found, errors = self.recipes()
        if name not in found:
            raise GmError(f"рецепта {name!r} нет" + (f": {errors[name]}" if name in errors else ""))
        r = found[name]
        with open(r.path, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        try:
            result = recipesmod.run(r, params, self.test, guard.Ctx(self.cfg, "teacher"), guard.check_call)
        except (recipesmod.RecipeError, bridgemod.BridgeError) as e:
            result = {"ok": False, "error": str(e)}
        self.store.log("teacher", "recipe_test", None, None, recipe=name, sha256=digest, ok=result["ok"],
                       params=params, error=result.get("error"))
        return result

    # ---------------------------------------------------------------- всем

    def recipe_run(self, name, params=None):
        found, errors = self.recipes()
        if name not in found:
            raise GmError(f"рецепта {name!r} нет" + (f": {errors[name]}" if name in errors else ""))
        r = found[name]
        if self.role == "voice" and r.kind != "instant":
            raise GmError(f"{name} — {r.kind}: это задача исполнителю (task с recipe={name!r})")
        bridge = self.test if self.role == "teacher" else self.live
        if bridge is None:
            raise GmError("моста для этой роли нет в config.toml (наставнику — test_bridge)")
        ctx = self.ctx()
        check = guard.check_call if self.role == "teacher" else guard.checker(self.store)
        try:
            result = recipesmod.run(r, params, bridge, ctx, check)
        except recipesmod.RecipeError as e:
            self.store.log(self.role, "recipe_error", ctx.requester, self.task_id, recipe=name, error=str(e))
            raise GmError(str(e)) from e
        self.store.log(self.role, "recipe_run", ctx.requester, self.task_id, recipe=name, params=params,
                       ok=result["ok"], error=result.get("error"))
        return result

    def tasks(self, player=None):
        rows = self.store.tasks(player, limit=15)
        now = time.time()
        out = []
        for t in rows:
            age = round((now - (t["started"] or t["created"])) / 60)
            line = f"#{t['id']} {t['player']}: «{t['text']}» — {t['status']}"
            if t["status"] in storemod.OPEN:
                line += f" {age} мин" + (f"; {t['progress']}" if t["progress"] else "")
            elif t["summary"]:
                line += f": {t['summary']}"
            out.append(line)
        return "\n".join(out) or "задач нет"

    def recipe_list(self, query=None):
        found, errors = self.recipes()
        lines = [r.summary() for r in found.values()
                 if not query or query.lower() in (r.name + " " + r.description).lower()]
        lines += [f"НЕГОДЕН {name}: {'; '.join(e)}" for name, e in errors.items()]
        return "\n".join(lines) or "рецептов нет"

    def zones(self, action="list", name=None, x=None, z=None, r=None, note="", ttl_minutes=None, start=None,
              via=None, target=None, spread=0):
        path = self.cfg["paths"]["zones"]
        with zonesmod.Locked(path):
            zones = zonesmod.load(path)
            if action == "list":
                return json.dumps(zones, ensure_ascii=False) if zones else "зон нет"
            if action == "check":
                if not target:
                    raise GmError("zones check: target [x, z]")
                found = zonesmod.conflicts(zones, target, start, via or [], spread, self.cfg["guard"]["margin"])
                return "НЕЛЬЗЯ:\n" + "\n".join(found) if found else f"можно: зоны не задеты (зон {len(zones)})"
            if action == "add":
                if None in (name, x, z, r):
                    raise GmError("zones add: name, x, z, r")
                if not 0 < r <= 10000:
                    raise GmError("zones add: радиус r — от 1 до 10000 блоков")
                if name in zones:
                    self._may_change(zones[name], name)
                zone = {"x": x, "z": z, "r": r, "note": note, "set": time.strftime("%H:%M"), "by": self.requester()}
                if ttl_minutes:
                    zone["expires"] = time.time() + ttl_minutes * 60
                zones[name] = zone
                zonesmod.save(zones, path)
                self.store.log(self.role, "zone_add", self.requester(), self.task_id, name=name, zone=zone)
                return f"зона «{name}»: ({x}, {z}), r={r}"
            if action == "remove":
                if name not in zones:
                    return f"зоны «{name}» нет"
                self._may_change(zones[name], name)
                del zones[name]
                zonesmod.save(zones, path)
                self.store.log(self.role, "zone_remove", self.requester(), self.task_id, name=name)
                return f"зона «{name}» убрана"
        raise GmError("zones: action — list, check, add или remove")

    def _may_change(self, zone, name):
        """Ослабить охрану (убрать или переставить зону) может владелец или тот, по чьей просьбе она стоит."""
        ctx = self.ctx()
        if not ctx.owner() and not (zone.get("by") and zone.get("by") == ctx.requester):
            raise GmError(f"зона «{name}» охраняет игроков: убрать или передвинуть её может только владелец "
                          f"({', '.join(self.cfg['gm']['owners']) or 'не задан'})")

    def lesson(self, text):
        self.store.log(self.role, "lesson", self.requester(), self.task_id, text=text)
        return "урок записан: наставник разберёт"


class GmError(Exception):
    pass


def _strip(result):
    """Ответ моста без картинки и не длиннее 4 КБ — для журнала и итога задачи."""
    if isinstance(result, dict):
        result = {k: v for k, v in result.items() if k != "png_base64"}
    text = json.dumps(result, ensure_ascii=False)
    return result if len(text) <= 4000 else text[:4000] + "…"


def serve(cfg, role):
    from mcp.server.fastmcp import FastMCP

    gm = Gm(cfg, role)
    mcp = FastMCP("gm", instructions="Инструменты ведущего: очередь задач, рецепты, охраняемые зоны, уроки.")

    def safe(fn):
        @functools.wraps(fn)
        def wrapped(*a, **kw):
            try:
                out = fn(*a, **kw)
            except (GmError, recipesmod.RecipeError, bridgemod.BridgeError) as e:
                gm.store.log(role, "tool_error", None, gm.task_id, tool=fn.__name__, error=str(e))
                raise
            return out if isinstance(out, str) else json.dumps(out, ensure_ascii=False, indent=1)
        return mcp.tool()(wrapped)

    if role == "voice":
        @safe
        def reply(text: str, to: list[str] | None = None, style: str = "chat") -> str:
            """Сказать игрокам — единственный способ говорить. Ответ на личное /gm уходит лично сам; to — кому
            (по умолчанию: лично на /gm, всем на общий чат). style: chat, title, subtitle, actionbar. Одно ясное
            сообщение на ход; не больше 4."""
            return gm.reply(text, to, style)

        @safe
        def task(text: str, recipe: str | None = None, params: dict | None = None,
                 resources: list[str] | None = None, minutes: float | None = None) -> str:
            """Поставить задачу исполнителю: всё, что дольше пары вызовов или строит, водит ботов, грузит районы,
            ставит правила. Задача — того, кто просит сейчас (для другого игрока — так и написать в text).
            text — что сделать, своими словами, со всем, что сказал игрок и что ты уже узнал (место, кому,
            сколько); recipe и params — если есть рецепт; resources — что займёт (место, корабль: "ship:NagaAI");
            minutes — срок. Дубль задачи отклоняется."""
            return gm.task(text, recipe, params, resources, minutes)

        @safe
        def task_cancel(id: int) -> str:
            """Отменить задачу по номеру (игрок передумал)."""
            return gm.task_cancel(id)

    if role == "worker":
        @safe
        def progress(text: str) -> str:
            """Где задача сейчас (одна строка) — голос скажет игроку, если тот спросит."""
            return gm.progress(text)

        @safe
        def done(summary: str, check_method: str, check_params: dict | None = None) -> str:
            """Задача сделана — только после проверки в мире: check_method и check_params — чтение моста, которое
            показывает результат (entities у места, player, blocks постройки, see глазами игрока, script с
            ответом). Мост выполняет проверку сам, её ответ — доказательство для игрока. summary — что вышло, для
            игрока, коротко."""
            return gm.done(summary, check_method, check_params)

        @safe
        def fail(reason: str) -> str:
            """Не получилось — честно, с причиной и тем, что можно иначе."""
            return gm.fail(reason)

    if role == "teacher":
        @safe
        def experience(after: int | None = None, limit: int = 300) -> str:
            """Опыт после номера after (по умолчанию — после разобранного прошлым уроком): сообщения игроков,
            ответы со временем, задачи и их итоги, отказы охраны, ошибки, уроки сессий."""
            return gm.experience(after, limit)

        @safe
        def recipe_test(name: str, params: dict | None = None) -> str:
            """Выполнить рецепт из своего навыка на тестовом сервере с проверкой. Новый или изменённый рецепт
            проходит в навык только с удачной пробой этого же текста."""
            return gm.recipe_test(name, params)

    @safe
    def tasks(player: str | None = None) -> str:
        """Задачи: открытые и последние, с прогрессом и итогами; player — только его."""
        return gm.tasks(player)

    @safe
    def recipes(query: str | None = None) -> str:
        """Рецепты — проверенные способы: имя, вид (instant/task/show), параметры, когда брать."""
        return gm.recipe_list(query)

    @safe
    def recipe_run(name: str, params: dict | None = None) -> dict:
        """Выполнить рецепт: шаги через охрану и мост, потом проверка в мире; ответ — ok и что вернули шаги."""
        return gm.recipe_run(name, params)

    @safe
    def zones(action: str = "list", name: str | None = None, x: int | None = None, z: int | None = None,
              r: int | None = None, note: str = "", ttl_minutes: float | None = None, start: list[int] | None = None,
              via: list[list[int]] | None = None, target: list[int] | None = None, spread: int = 0) -> str:
        """Охраняемые зоны — места, куда удары и полёты не должны попадать (стоянки кораблей игроков, базы,
        зрители). action: list; check — путь start [x, z] → via → target [x, z] с разбросом spread (удары охрана
        проверяет и сама); add — name, x, z, r, note, ttl_minutes; remove — name. Кто сажает игроков, ставит
        корабль или собирает зрителей — сам ставит зону. Убрать или передвинуть чужую зону — только по слову
        владельца."""
        return gm.zones(action, name, x, z, r, note, ttl_minutes, start, via, target, spread)

    @safe
    def lesson(text: str) -> str:
        """Урок на будущее: что не знал, что вышло не так, что сработало лучше. Наставник превратит его в навык."""
        return gm.lesson(text)

    mcp.run()


def init(path):
    """Каталог сервера: настройки из образца, пустые знания, git с первым коммитом. Существующее не трогает."""
    import shutil
    import subprocess
    path = os.path.abspath(os.path.expanduser(path))
    for d in ("skill/knowledge", "skill/recipes", "evals", "runs"):
        os.makedirs(os.path.join(path, d), exist_ok=True)
    kit = os.path.dirname(os.path.abspath(__file__))
    files = {
        "config.toml": None,
        ".gitignore": "gm.db*\nruns/\nzones.json*\n",
        "skill/CORE.md": "",
        "skill/rules.local.md": "",
        "skill/knowledge/README.md": ("# Знания сервера\n\nМеста, игроки, корабли и машины, как здесь что "
                                      "делается — по файлу на тему. Пишет наставник по опыту; люди тоже могут.\n"),
        "evals/learned.jsonl": "",
    }
    for rel, text in files.items():
        full = os.path.join(path, rel)
        if os.path.exists(full):
            continue
        if text is None:
            shutil.copy(os.path.join(kit, "config.example.toml"), full)
        else:
            with open(full, "w", encoding="utf-8") as f:
                f.write(text)
    if not os.path.isdir(os.path.join(path, ".git")):
        git = ["git", "-c", "user.name=Ведущий", "-c", "user.email=gm@localhost"]
        subprocess.run(git + ["init", "-q"], cwd=path, check=True)
        subprocess.run(git + ["add", "-A"], cwd=path, check=True)
        subprocess.run(git + ["commit", "-qm", "Каталог ведущего"], cwd=path, check=True)
    print(f"каталог ведущего: {path} — дальше config.toml (мост, владельцы, языки), затем gmd.py --config")


def main():
    p = argparse.ArgumentParser(description="Инструменты ведущего")
    p.add_argument("--config", help="config.toml оверлея (иначе GM_CONFIG)")
    sub = p.add_subparsers(dest="cmd", required=True)
    i = sub.add_parser("init")
    i.add_argument("path")
    sub.add_parser("mcp")
    sub.add_parser("tasks")
    lg = sub.add_parser("log")
    lg.add_argument("--kind", action="append")
    lg.add_argument("--last", type=int, default=50)
    sub.add_parser("recipes")
    rn = sub.add_parser("run")
    rn.add_argument("name")
    rn.add_argument("params", nargs="?", default="{}")
    rn.add_argument("--test", action="store_true")
    a = p.parse_args()
    if a.cmd == "init":
        init(a.path)
        return 0
    cfg = conf.load(a.config)
    if a.cmd == "mcp":
        serve(cfg, os.environ.get("GM_ROLE", "worker"))
        return 0
    gm = Gm(cfg, "teacher" if getattr(a, "test", False) else "owner")
    if a.cmd == "tasks":
        print(gm.tasks())
    elif a.cmd == "log":
        rows = gm.store.logs(max(0, gm.store.last_log_id() - a.last), a.kind, a.last)
        for e in rows:
            print(f"[{e['id']}] {time.strftime('%d.%m %H:%M:%S', time.localtime(e['ts']))} {e['role']} {e['kind']} "
                  f"{e['player'] or ''} {json.dumps(e['data'], ensure_ascii=False)}")
    elif a.cmd == "recipes":
        print(gm.recipe_list())
    else:
        found, errors = gm.recipes()
        if a.name not in found:
            print(f"рецепта {a.name} нет {errors.get(a.name, '')}", file=sys.stderr)
            return 1
        b = gm.test if a.test else gm.live
        # человек у консоли — владелец: охрана всё равно проверяет шаги (зоны, запреты)
        ctx = guard.Ctx(cfg, "owner", cfg["gm"]["owners"][0] if cfg["gm"]["owners"] else None)
        check = guard.check_call if a.test else guard.checker(gm.store)
        print(json.dumps(recipesmod.run(found[a.name], json.loads(a.params), b, ctx, check),
                         ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
