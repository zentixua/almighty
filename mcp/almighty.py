#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "mcp>=1.2,<2",
#     "httpx>=0.27",
# ]
# ///
"""Связь ИИ-агента (ведущего) с модом Almighty на сервере: MCP-сервер и командная строка.

Мод слушает HTTP на сервере (по умолчанию 127.0.0.1:25650, только POST /rpc с токеном), этот скрипт — мост к нему:

  uv run mcp/almighty.py mcp           — MCP-сервер (stdio) для Claude Code:
                                             claude mcp add gm -- uv run /путь/mcp/almighty.py mcp
  uv run mcp/almighty.py call status   — один вызов: метод и параметры JSON, ответ — JSON
  uv run mcp/almighty.py call map '{"center": [0, 0], "size": 256}' --png карта.png
  uv run mcp/almighty.py follow        — лента событий: строка JSON на событие (для Monitor);
                                             --from-start — с начала кольца, иначе — с этой минуты

Адрес — ALMIGHTY_URL (по умолчанию http://127.0.0.1:25650), токен — ALMIGHTY_TOKEN или файл из
ALMIGHTY_TOKEN_FILE (мод пишет его в config/almighty/token каталога сервера при первом запуске).
Методы и их параметры — CLAUDE.md.
"""
import argparse
import base64
import json
import logging
import os
import sys
import time

import httpx

URL = os.environ.get("ALMIGHTY_URL", "http://127.0.0.1:25650").rstrip("/") + "/rpc"


class GmError(Exception):
    pass


def _token():
    token = os.environ.get("ALMIGHTY_TOKEN")
    if token:
        return token.strip()
    path = os.environ.get("ALMIGHTY_TOKEN_FILE")
    if not path:
        raise GmError("нет токена: ALMIGHTY_TOKEN или ALMIGHTY_TOKEN_FILE (config/almighty/token на сервере)")
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


_client = None


def rpc(method, params=None, timeout=None):
    """Вызов метода мода; ошибка мода — GmError с его текстом. timeout — свой срок ответа в секундах."""
    global _client
    if _client is None:
        # мост ждёт ответа сервера до 60 с; прокси окружения к петле не нужен
        _client = httpx.Client(timeout=70, trust_env=False, headers={"Authorization": "Bearer " + _token()})
    body = {"method": method, "params": {k: v for k, v in (params or {}).items() if v is not None}}
    try:
        r = _client.post(URL, json=body, **({"timeout": timeout} if timeout else {}))
    except httpx.HTTPError as e:
        raise GmError(f"мост {URL} недоступен: {e}") from e
    try:
        data = r.json()
    except ValueError:
        raise GmError(f"мост ответил {r.status_code}: {r.text[:300]}")
    if not data.get("ok"):
        err = data.get("error") or {}
        raise GmError(f"{err.get('code', r.status_code)}: {err.get('message', r.text[:300])}")
    return data["result"]


def _split_image(result):
    """Ответ снимка → (байты PNG или None, остальное описание)."""
    if isinstance(result, dict) and "png_base64" in result:
        rest = dict(result)
        png = base64.b64decode(rest.pop("png_base64"))
        return png, rest
    return None, result


def _notes():
    """Памятки модов и датапаков сервера — в конец инструкций MCP; сервер недоступен — строка, где их взять."""
    try:
        notes = rpc("notes", timeout=5)
    except (GmError, OSError) as e:
        return f"\nПамятки модов и датапаков сервера — инструмент notes (при запуске не получены: {e})."
    if not notes:
        return ""
    return "\nПамятки модов и датапаков сервера (свежие — инструмент notes):\n\n" + "\n\n".join(
        f"[{n['id']}]\n{n.get('text') or n.get('error', '')}" for n in notes)


def serve_mcp():
    from mcp.server.fastmcp import FastMCP, Image

    # строка INFO на каждый запрос httpx — шум в журнале MCP
    logging.getLogger("httpx").setLevel(logging.WARNING)

    mcp = FastMCP("almighty", instructions=(
        "Ведущий на сервере Minecraft (мод Almighty). Рычаги: command — команды и функции, script — код Groovy "
        "в игре, rule — реакция на любое событие игры прямо на сервере, events — лента событий,\n"
        "view — карта, вид и блоки текстом, build — постройка с откатом, area — загрузить место, где никого нет, "
        "job — работы, status — сервер и игрок, entities — сущности, say — слова игрокам,\n"
        "bot — свои игроки-боты: входят как игроки и действуют только тем, что есть у игрока (клавиши, мышь, меню, "
        "чат); view eye — что видит глаз игрока, бота или свободной камеры.\n"
        "Игроки пишут ведущему лично командой /gm <текст> (событие gm в ленте), отвечать лично — say с to.\n"
        "Далёкое место загрузить — area prepare. Скрипт не отдаёт игре свои "
        "замыкания и объекты (тикеты, сравнения, слушатели, задачи на потом) и не грузит чанки сам: реакции — rule.\n"
        "Координаты — блоки. Долгие работы (постройка, снимок) отвечают описанием, если не успели за wait секунд: "
        "дальше — job(id, wait)." + _notes()))

    def _out(result):
        png, rest = _split_image(result)
        text = json.dumps(rest, ensure_ascii=False, indent=1)
        return [Image(data=png, format="png"), text] if png else text

    # Проверки здесь, а не в моде: мост назвал бы from/to вместо start/end, а лишний параметр просто пропустил бы.
    def _need(where, **given):
        missing = [k for k, v in given.items() if v is None]
        if missing:
            raise GmError(f"{where}: нет параметра {', '.join(missing)}")

    def _only(where, **given):
        extra = [k for k, v in given.items() if v is not None]
        if extra:
            raise GmError(f"{where}: не берёт {', '.join(extra)}")

    def _points(where, n, **given):
        for k, v in given.items():
            if v is not None and len(v) != n:
                raise GmError(f"{where}: {k} — {'[x, z]' if n == 2 else '[x, y, z]'}")

    @mcp.tool()
    def status(player: str | None = None) -> str:
        """Без player — сервер: темп тика (mspt, tps), имена игроков, по измерениям чанки, сущности, время и погода,
        работы и чанки ведущего. С player (имя) — игрок подробно: где, здоровье, режим, инвентарь по слотам, эффекты,
        точка возрождения, на какой блок смотрит."""
        if player is None:
            return _out(rpc("status"))
        return _out(rpc("player", {"name": player}))

    @mcp.tool()
    def entities(center: list[int] | None = None, radius: int | None = None, start: list[int] | None = None,
                 end: list[int] | None = None, type: str | None = None, limit: int = 50,
                 dimension: str | None = None) -> str:
        """Сущности: вокруг center [x, y, z] в радиусе radius или в рамке start..end; type — фильтр
        (minecraft:zombie или zombie). Ближние первыми, счёт по типам — по всем. Только загруженные места."""
        return _out(rpc("entities", {"center": center, "radius": radius, "from": start, "to": end, "type": type,
                                     "limit": limit, "dimension": dimension}))

    @mcp.tool()
    def command(commands: list[str] | None = None, lines: list[str] | None = None, args: dict | None = None,
                pos: list[int] | None = None, dimension: str | None = None) -> str:
        """От имени ведущего (права 4), одно из двух:
        commands — команды по порядку, у каждой ok, result, output, errors;
        lines — функция из строк .mcfunction без файла и без /reload: строки на $ — макросы $(имя) из args,
        ошибка разбора называет строку.
        Источник — точка появления мира или pos [x, y, z]. Тяжёлое (большой /fill) — через build."""
        if (commands is None) == (lines is None):
            raise GmError("command: нужно одно из двух — commands (команды) или lines (строки функции)")
        if commands is not None:
            if args is not None:
                raise GmError("command: args — только для lines (макросы функции)")
            return _out(rpc("command", {"commands": commands, "pos": pos, "dimension": dimension}))
        return _out(rpc("function", {"lines": lines, "args": args, "pos": pos, "dimension": dimension}))

    @mcp.tool()
    def say(text: str | None = None, component: dict | None = None, to: list[str] | None = None,
            style: str = "chat") -> str:
        """Слова игрокам: style chat (с именем ведущего), title, subtitle или actionbar; to — кому (по умолчанию
        всем); component — текст JSON как у /tellraw вместо text."""
        return _out(rpc("say", {"text": text, "component": component, "to": to, "style": style}))

    @mcp.tool()
    def script(code: str, args: dict | None = None, timeout_ms: int = 1000) -> str:
        """Код Groovy в потоке сервера: доступно всё в игре и в любом моде. Переменные: server (MinecraftServer);
        gm — помощник: gm.command('…') и gm.commandAs(entity, '…') — итоги как у command, gm.emit(data) — в ленту
        событием emit, gm.player(имя), gm.level('minecraft:the_nether'); state — общая карта всех скриптов и правил
        до перезапуска сервера; args. Частые классы уже импортированы (BlockPos, Blocks, Items, ServerPlayer, Entity,
        Vec3, AABB, Component, BuiltInRegistries…).
        Что код вернёт — JSON в value, println — в output; ошибка — ok=false, error и line.
        Предел timeout_ms (до 10000): цикл прерывается, сделанное остаётся. Отката нет — большие правки блоков
        через build. Срок идёт, только пока идёт сам вызов: замыкание или объект скрипта, отданный игре (TicketType с
        замыканием-сравнением, слушатель, задача на потом), игра зовёт позже без срока, и его ошибка роняет тик мира —
        не отдавать; реакция на события — rule. Чанки не грузить (свой TicketType, level.getChunk — синхронно):
        далёкое место — area prepare."""
        return _out(rpc("script", {"code": code, "args": args, "timeout_ms": timeout_ms}))

    @mcp.tool()
    def rule(action: str = "list", event: str | None = None, script: str | None = None, name: str | None = None,
             every: int | None = None, limit: int | None = None, priority: str | None = None,
             canceled: bool | None = None, budget_ms: float | None = None, persist: bool | None = None,
             id: int | None = None, query: str | None = None) -> str:
        """Правило — реакция на любое событие игры прямо на сервере, в том же тике, без ведущего. action:
        add — event: класс события NeoForge игры или любого мода коротким именем ("BlockEvent.BreakEvent",
        "LivingDamageEvent.Pre", "CommandEvent") или "tick" — каждый тик сервера. Без script свойства события идут в
        ленту (тип emit); script — Groovy с event и переменными script: gm.emit(data) пишет в ленту,
        event.canceled = true отменяет отменяемое событие (запреты — с priority "highest"). canceled — получать и уже
        отменённые. every — не чаще раза в N тиков (у tick — каждый N-й); limit — снять после N срабатываний;
        budget_ms (по умолчанию 5) — среднее время за тик по последним 20 тикам, один запуск — не дольше 20×budget_ms
        (не меньше 50 мс; первый запуск не в счёт). Превышение, 10 ошибок подряд или больше 200 записей в ленту за
        секунду выключают правило (в ленте rule.off; ошибки — rule.error). name — то же имя заменяет прежнее
        правило; persist — пережить перезапуск (хранится с миром).
        remove — по id или name. list — все правила (выключенное — state off с причиной; сохранённое, что не встало
        при запуске, — unloaded, убрать по name). types — найти классы событий по query (имя, мод, отменяемое)."""
        given = {"event": event, "script": script, "name": name, "every": every, "limit": limit, "priority": priority,
                 "canceled": canceled, "budget_ms": budget_ms, "persist": persist, "id": id, "query": query}
        takes = {"add": ("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist"),
                 "remove": ("id", "name"), "list": (), "types": ("query",)}
        if action not in takes:
            raise GmError(f"rule: action — add, remove, list или types, а не {action!r}")
        _only(f"rule {action}", **{k: v for k, v in given.items() if k not in takes[action]})
        if action == "add":
            _need("rule add", event=event)
            return _out(rpc("rule.add", {k: given[k] for k in takes["add"]}))
        if action == "remove":
            if id is None and name is None:
                raise GmError("rule remove: нужен id или name")
            return _out(rpc("rule.remove", {"id": id, "name": name}))
        if action == "list":
            return _out(rpc("rules"))
        return _out(rpc("event.types", {"query": query}))

    @mcp.tool()
    def events(after: int = 0, wait: int = 0, limit: int = 200) -> str:
        """События после номера after: чат, входы и выходы, смерти с причиной, достижения, смена измерения, команды
        игроков, gm (игрок написал ведущему лично: /gm <текст>; ответить лично — say с to), паузы сервера (lag), конец работ (job), emit (правила и скрипты), rule.off и rule.error (правило
        выключено, ошибка правила). wait — ждать новых до 25 с. boot меняется при перезапуске."""
        return _out(rpc("events", {"after": after, "wait": wait, "limit": limit}))

    @mcp.tool()
    def view(kind: str, center: list[int] | None = None, size: int = 128, start: list[int] | None = None,
             end: list[int] | None = None, below: int | None = None, marks: list[dict] | None = None,
             direction: str | None = None, properties: bool = True, dimension: str | None = None, wait: int = 30,
             who: str | None = None, eye: list[float] | None = None, yaw: float | None = None,
             pitch: float | None = None, image: bool = True, width: int | None = None, height: int | None = None,
             fov: float | None = None, distance: int | None = None, radius: int | None = None):
        """Посмотреть на мир; kind:
        map — карта сверху, как ванильная карта: center [x, z] и size (до 1024) или углы start/end [x, z]; север
        вверху. below — смотреть под потолок с этой высоты (пещеры, Незер, этажи); marks — свои метки [{label, x, z}].
        Игроки — цветными квадратами (легенда). Серая шахматка — чанк не загружен (area prepare).
        look — вид на рамку start..end [x, y, z] с direction: north/south/east/west (фасад, разрез), down (план), up.
        Ближе — ярче; голубой — насквозь пусто. Грань до 256×256, глубина до 256.
        blocks — блоки рамки start..end [x, y, z] текстом (до 64 по оси, до 32768): палитра символ → блок, слои снизу
        вверх, строки с севера на юг, символы с запада на восток; '.' воздух, '?' не загружено; properties=false — без
        свойств блоков. Тот же вид принимает build (op layers).
        eye — глазами: who — имя игрока (бота тоже) или UUID сущности, либо свободная камера eye [x, y, z] с yaw
        (0 — юг, 90 — запад, −90 — восток) и pitch (вниз +). Ответ: куда смотрит, свет, aim — блок или сущность под
        прицелом в досягаемости рук, aim_far — первое на луче до distance (96), entities — сущности в радиусе radius
        (48) с расстоянием, углом от взгляда (yaw_offset вправо +, pitch_offset вниз +), in_view и visible (не за
        блоками); у бота — sounds, что он слышал. image (по умолчанию да) — картинка width×height (224×126, до
        480×270) с углом обзора fov (70): блоки цветом карты со светом, сущности рамками, перекрестье — центр.
        map, look и eye — картинка; не успели за wait секунд — описание работы, дальше job."""
        eye_only = {"who": who, "eye": eye, "yaw": yaw, "pitch": pitch, "width": width, "height": height, "fov": fov,
                    "distance": distance, "radius": radius}
        if kind == "eye":
            _only("view eye", center=center, start=start, end=end, below=below, marks=marks, direction=direction)
            if (who is None) == (eye is None):
                raise GmError("view eye: who (имя или UUID) или eye [x, y, z] — одно из двух")
            if who is None:
                _points("view eye", 3, eye=eye)
            elif yaw is not None or pitch is not None or dimension is not None:
                raise GmError("view eye: yaw, pitch и dimension — только для свободной камеры eye")
            ident = {}
            if who is not None:
                ident = {"uuid": who} if len(who) == 36 and who.count("-") == 4 else {"name": who}
            return _out(rpc("see", {**ident, "at": eye, "yaw": yaw, "pitch": pitch, "dimension": dimension,
                                    "image": image, "width": width, "height": height, "fov": fov,
                                    "distance": distance, "radius": radius, "wait": wait}))
        _only(f"view {kind}", **{k: v for k, v in eye_only.items() if v is not None})
        if kind == "map":
            _only("view map", direction=direction)
            by_center = center is not None and start is None and end is None
            by_corners = center is None and start is not None and end is not None
            if not (by_center or by_corners):
                raise GmError("view map: center [x, z] или оба угла start и end [x, z]")
            _points("view map", 2, center=center, start=start, end=end)
            return _out(rpc("map", {"center": center, "size": size if center else None, "from": start, "to": end,
                                    "below": below, "marks": marks, "dimension": dimension, "wait": wait}))
        if kind == "look":
            _only("view look", center=center, below=below, marks=marks)
            _need("view look", start=start, end=end, direction=direction)
            _points("view look", 3, start=start, end=end)
            return _out(rpc("look", {"from": start, "to": end, "look": direction, "dimension": dimension,
                                     "wait": wait}))
        if kind == "blocks":
            _only("view blocks", center=center, below=below, marks=marks, direction=direction)
            _need("view blocks", start=start, end=end)
            _points("view blocks", 3, start=start, end=end)
            return _out(rpc("blocks", {"from": start, "to": end, "properties": properties, "dimension": dimension}))
        raise GmError(f"view: kind — map, look, blocks или eye, а не {kind!r}")

    @mcp.tool()
    def build(ops: list[dict] | None = None, undo: int | None = None, dimension: str | None = None,
              wait: int = 30) -> str:
        """Постройка частями между тиками (сервер не встаёт), с откатом; одно из двух:
        ops — по порядку:
        {"op": "fill", "from": [x,y,z], "to": [x,y,z], "block": "stone", "mode": "replace|keep|hollow|outline"},
        {"op": "set", "pos": [x,y,z], "block": "oak_stairs[facing=east]"},
        {"op": "layers", "origin": [x,y,z], "palette": {"#": "stone"}, "layers": [["#.#", ...], ...]}
        (символ не из палитры — не трогать). Ждёт загрузки чанков сам. Ответ — номер работы для отката.
        undo — номер работы: откатить её (постройку или откат), вернуть блоки и содержимое блок-сущностей."""
        if (ops is None) == (undo is None):
            raise GmError("build: нужно одно из двух — ops (постройка) или undo (номер работы для отката)")
        if undo is not None:
            _only("build undo", dimension=dimension)
            return _out(rpc("undo", {"job": undo, "wait": wait}))
        return _out(rpc("build", {"ops": ops, "dimension": dimension, "wait": wait}))

    @mcp.tool()
    def area(action: str = "list", start: list[int] | None = None, end: list[int] | None = None,
             ttl_seconds: int = 300, wait: int = 0, id: int | None = None, dimension: str | None = None) -> str:
        """Чанки ведущего; action:
        prepare — загрузить чанки рамки start..end [x, z] без тика (в фоне) и держать ttl_seconds (до 3600): чтобы
        увидеть картой или прочитать то, где никого нет; wait — ждать готовности. Ответ — район с номером.
        release — отпустить район id. list — что держит ведущий: районы и постройки."""
        if action == "prepare":
            _only("area prepare", id=id)
            _need("area prepare", start=start, end=end)
            _points("area prepare", 2, start=start, end=end)
            return _out(rpc("area.prepare", {"from": start, "to": end, "ttl_seconds": ttl_seconds, "wait": wait,
                                             "dimension": dimension}))
        if action == "release":
            _only("area release", start=start, end=end, dimension=dimension)
            _need("area release", id=id)
            return _out(rpc("area.release", {"id": id}))
        if action == "list":
            _only("area list", start=start, end=end, id=id, dimension=dimension)
            return _out(rpc("areas"))
        raise GmError(f"area: action — prepare, release или list, а не {action!r}")

    @mcp.tool()
    def job(id: int | None = None, wait: int = 0, cancel: bool = False):
        """Работы ведущего. Без id — идущие и последние кончившиеся. С id — работа по номеру; wait — ждать её конца
        до стольких секунд; снимок отдаёт картинку один раз. С id и cancel=true — остановить работу (сделанное
        остаётся, откат — build undo)."""
        if id is None:
            if cancel or wait:
                raise GmError("job: cancel и wait — только с id работы")
            return _out(rpc("jobs"))
        if cancel:
            if wait:
                raise GmError("job: cancel не ждёт — без wait")
            return _out(rpc("cancel", {"id": id}))
        return _out(rpc("job", {"id": id, "wait": wait}))

    @mcp.tool()
    def bot(action: str = "list", name: str | None = None, actions: list[dict] | None = None, replace: bool = False,
            wait: int = 30, pos: list[float] | None = None, dimension: str | None = None, yaw: float | None = None,
            pitch: float | None = None, gamemode: str | None = None, skin: str | dict | None = None,
            marker: bool | None = None, auto_respawn: bool | None = None, after: int | None = None):
        """Свои игроки-боты: входят на сервер как игроки (в табе — с пометкой) и делают то же, что игрок руками:
        клавиши и мышь, хотбар, меню, чат и команды (с правами своего игрока), табличка, возрождение — через те же
        пакеты, что клиент, поэтому сервер и моды видят обычного игрока. action:
        spawn — name (3–16 латинских букв, цифр, _), pos [x, y, z], dimension, yaw, pitch, gamemode, skin (имя аккаунта
        или {value, signature}), auto_respawn (да); место входа загружается до входа (до 30 с). Имя или скин игрока
        сервера и вход без пометки (marker=false) —
        только если владелец включил bots.allow_disguise; игрок с именем бота заходит — бот уступает.
        remove — name. list — все боты. state — бот подробно: тело, инвентарь, транспорт (vehicle: тип, ведёт ли,
        клавиши мода keys), открытое меню (ячейки по номерам), клавиши, программа, почта (чат, шёпот, экраны, титры,
        смерть) после номера after.
        act — программа actions по шагам; мгновенные идут подряд в одном тике, ждут только wait, walk_to и hold
        до release. Шаги: {"hold": "forward"|["forward","sprint"]} (forward back left right jump sneak sprint attack
        use), {"release": "all"|клавиши}, {"wait": тиков}, {"look": [yaw, pitch]}, {"turn": [dyaw, dpitch]},
        {"look_at": [x, y, z] | UUID | имя} (целые — центр блока), {"click": "attack"|"use", "at": …},
        {"slot": 0–8}, {"drop": "one"|"stack"}, {"swap_hands": true}, {"menu": ячейка, "button": 0, "mode":
        "pickup"|"quick_move"|"swap"|"throw"|"pickup_all"|"clone"|"quick_craft"} (−999 — вне окна),
        {"menu_button": n}, {"close": true}, {"chat": "текст" | "/команда"}, {"sign": [x, y, z], "lines": [...]},
        {"respawn": true}, {"fly": true|false}, {"player_command": "start_fall_flying"|…}, {"walk_to": [x, z],
        "sprint": false, "within": 0.6}, {"press": "key.immersive_aircraft.dismount"} (клавиша транспорта мода по
        имени в настройках управления; какие есть — keys в state), {"payload": "мод:пакет", "data": base64} (пакет мода
        от клиента: байты после id, как по сети, — по исходникам мода или его памятке; так бот жмёт кнопки модов, у
        которых клиент шлёт свои пакеты). Ошибка шага останавливает программу. replace —
        снять идущую программу, иначе новая ждёт в очереди. wait — ждать конца до стольких секунд (до 50); не
        дождались — описание, конец программы — событие bot_done в ленте, итог — в state.
        Транспорт: сесть — click use по нему, слезть — sneak. Лошадь, лодку, свинью, верблюда бот ведёт клавишами,
        как клиент (прыжок верхом — удержать и отпустить jump); транспорт мода, который ведёт ввод игрока, — после
        метки сущностей almighty:driven_by_rider (датапак). Транспорт Immersive Aircraft: left/right, jump/sneak,
        forward/back — его рули, как у игрока (у самолёта — курс, тяга больше/меньше с тормозом, штурвал); выйти —
        press key.immersive_aircraft.dismount (в воздухе — дважды за секунду), ускоритель —
        key.immersive_aircraft.boost."""
        given = {"name": name, "actions": actions, "pos": pos, "dimension": dimension, "yaw": yaw, "pitch": pitch,
                 "gamemode": gamemode, "skin": skin, "marker": marker, "auto_respawn": auto_respawn, "after": after}
        takes = {"spawn": ("name", "pos", "dimension", "yaw", "pitch", "gamemode", "skin", "marker", "auto_respawn"),
                 "remove": ("name",), "list": (), "state": ("name", "after"), "act": ("name", "actions")}
        if action not in takes:
            raise GmError(f"bot: action — spawn, remove, list, state или act, а не {action!r}")
        _only(f"bot {action}", **{k: v for k, v in given.items() if k not in takes[action]})
        if action == "list":
            return _out(rpc("bots"))
        _need(f"bot {action}", name=name)
        if action == "spawn":
            _points("bot spawn", 3, pos=pos)
            return _out(rpc("bot.spawn", {k: given[k] for k in takes["spawn"]}))
        if action == "remove":
            return _out(rpc("bot.remove", {"name": name}))
        if action == "state":
            return _out(rpc("bot", {"name": name, "after": after}))
        _need("bot act", actions=actions)
        return _out(rpc("bot.act", {"name": name, "actions": actions, "replace": replace, "wait": wait}))

    @mcp.tool()
    def notes() -> str:
        """Памятки модов и датапаков сервера: как работать с их механиками (команды, правила). При запуске адаптера
        они уже в инструкциях; здесь — из текущих данных сервера, после /reload или смены модов."""
        return _out(rpc("notes"))

    @mcp.tool()
    def call(method: str, params: dict | None = None):
        """Любой метод мода как есть (на случай нового метода, которого ещё нет среди инструментов)."""
        return _out(rpc(method, params))

    mcp.run()


def follow(from_start):
    """Лента построчно; мост недоступен или сервер перезапущен — строка об этом, дальше ждать."""
    after, boot, down = 0, None, False
    if not from_start:
        while True:
            try:
                page = rpc("events", {"after": after, "limit": 1000})
            except GmError as e:
                print(json.dumps({"type": "bridge_down", "error": str(e)}, ensure_ascii=False), flush=True)
                down = True
                time.sleep(10)
                continue
            boot, after = page["boot"], page["next"]
            if len(page["events"]) < 1000:
                break
    pause = 2
    while True:
        try:
            page = rpc("events", {"after": after, "wait": 25, "limit": 500})
        except GmError as e:
            if not down:
                print(json.dumps({"type": "bridge_down", "error": str(e)}, ensure_ascii=False), flush=True)
                down = True
            time.sleep(pause)
            pause = min(pause * 2, 30)
            continue
        if down:
            print(json.dumps({"type": "bridge_up"}), flush=True)
            down, pause = False, 2
        if boot is not None and page["boot"] != boot:
            print(json.dumps({"type": "server_restarted", "boot": page["boot"]}), flush=True)
        boot = page["boot"]
        if page.get("dropped"):
            print(json.dumps({"type": "dropped", "count": page["dropped"]}), flush=True)
        for event in page["events"]:
            print(json.dumps(event, ensure_ascii=False), flush=True)
        after = page["next"]


def main():
    parser = argparse.ArgumentParser(description="Almighty: MCP-сервер, вызов метода, лента событий")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("mcp", help="MCP-сервер (stdio)")
    c = sub.add_parser("call", help="вызвать метод")
    c.add_argument("method")
    c.add_argument("params", nargs="?", default="{}", help="параметры JSON")
    c.add_argument("--png", help="картинку снимка — в этот файл")
    f = sub.add_parser("follow", help="лента событий построчно")
    f.add_argument("--from-start", action="store_true", help="с начала кольца событий")
    a = parser.parse_args()
    try:
        if a.cmd == "mcp":
            serve_mcp()
        elif a.cmd == "call":
            png, rest = _split_image(rpc(a.method, json.loads(a.params)))
            if png is not None:
                if not a.png:
                    raise GmError("ответ с картинкой: указать --png файл")
                with open(a.png, "wb") as out:
                    out.write(png)
                rest["png"] = a.png
            print(json.dumps(rest, ensure_ascii=False, indent=1))
        else:
            follow(a.from_start)
    except GmError as e:
        print(f"gm: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
