#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "mcp>=1.2,<2",
#     "httpx>=0.27",
# ]
# ///
"""Связь ведущего-Claude с модом ведущего (airstrike_gm) на сервере: MCP-сервер и командная строка.

Мод слушает HTTP на сервере (по умолчанию 127.0.0.1:25650, только POST /rpc с токеном), этот скрипт — мост к нему:

  uv run tools/gm.py mcp                          — MCP-сервер (stdio) для Claude Code:
                                                    claude mcp add gm -- uv run /путь/tools/gm.py mcp
  uv run tools/gm.py call status                  — один вызов: метод и параметры JSON, ответ — JSON
  uv run tools/gm.py call map '{"center": [0, 0], "size": 256}' --png карта.png
  uv run tools/gm.py follow                       — лента событий: строка JSON на событие (для Monitor);
                                                    --from-start — с начала кольца, иначе — с этой минуты

Адрес — AIRSTRIKE_GM_URL (по умолчанию http://127.0.0.1:25650), токен — AIRSTRIKE_GM_TOKEN или файл из
AIRSTRIKE_GM_TOKEN_FILE (мод пишет его в config/airstrike_gm/token каталога сервера при первом запуске).
Методы и их параметры — .claude/rules/gm.md.
"""
import argparse
import base64
import json
import logging
import os
import sys
import time

import httpx

URL = os.environ.get("AIRSTRIKE_GM_URL", "http://127.0.0.1:25650").rstrip("/") + "/rpc"


class GmError(Exception):
    pass


def _token():
    token = os.environ.get("AIRSTRIKE_GM_TOKEN")
    if token:
        return token.strip()
    path = os.environ.get("AIRSTRIKE_GM_TOKEN_FILE")
    if not path:
        raise GmError("нет токена: AIRSTRIKE_GM_TOKEN или AIRSTRIKE_GM_TOKEN_FILE (config/airstrike_gm/token на сервере)")
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


_client = None


def rpc(method, params=None):
    """Вызов метода мода; ошибка мода — GmError с его текстом."""
    global _client
    if _client is None:
        # мост ждёт ответа сервера до 60 с; прокси окружения к петле не нужен
        _client = httpx.Client(timeout=70, trust_env=False, headers={"Authorization": "Bearer " + _token()})
    body = {"method": method, "params": {k: v for k, v in (params or {}).items() if v is not None}}
    try:
        r = _client.post(URL, json=body)
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


def serve_mcp():
    from mcp.server.fastmcp import FastMCP, Image

    # строка INFO на каждый запрос httpx — шум в журнале MCP
    logging.getLogger("httpx").setLevel(logging.WARNING)

    mcp = FastMCP("airstrike-gm", instructions=(
        "Ведущий на сервере Minecraft (мод airstrike_gm): видеть мир, игроков и события, выполнять команды и функции, "
        "строить частями с откатом. Координаты — блоки [x, y, z]; карта и вид — картинка с легендой. Долгие работы "
        "(постройка, снимок) отвечают описанием, если не успели за wait секунд: дальше — job(id, wait)."))

    def _out(result):
        png, rest = _split_image(result)
        text = json.dumps(rest, ensure_ascii=False, indent=1)
        return [Image(data=png, format="png"), text] if png else text

    @mcp.tool()
    def status() -> str:
        """Сервер: темп тика (mspt, tps), игроки, чанки и сущности по измерениям, работы и чанки ведущего."""
        return _out(rpc("status"))

    @mcp.tool()
    def players() -> str:
        """Игроки в игре: где, здоровье, еда, режим, права, пинг."""
        return _out(rpc("players"))

    @mcp.tool()
    def player(name: str) -> str:
        """Игрок подробно: инвентарь по слотам, эффекты, точка возрождения, на какой блок смотрит."""
        return _out(rpc("player", {"name": name}))

    @mcp.tool()
    def entities(center: list[int] | None = None, radius: int | None = None, start: list[int] | None = None,
                 end: list[int] | None = None, type: str | None = None, limit: int = 50,
                 dimension: str | None = None) -> str:
        """Сущности: вокруг center [x, y, z] в радиусе radius или в рамке start..end; type — фильтр
        (minecraft:zombie или zombie). Ближние первыми, счёт по типам — по всем. Только загруженные места."""
        return _out(rpc("entities", {"center": center, "radius": radius, "from": start, "to": end, "type": type,
                                     "limit": limit, "dimension": dimension}))

    @mcp.tool()
    def command(commands: list[str], pos: list[int] | None = None, dimension: str | None = None) -> str:
        """Команды от имени ведущего (права 4) по порядку, с выводом каждой: ok, result, output, errors. Источник —
        точка появления мира или pos [x, y, z]. Тяжёлое (большой /fill) — через build."""
        return _out(rpc("command", {"commands": commands, "pos": pos, "dimension": dimension}))

    @mcp.tool()
    def function(lines: list[str], args: dict | None = None, pos: list[int] | None = None,
                 dimension: str | None = None) -> str:
        """Функция из строк .mcfunction без файла и без /reload; строки на $ — макросы $(имя) из args.
        Ошибка разбора называет строку."""
        return _out(rpc("function", {"lines": lines, "args": args, "pos": pos, "dimension": dimension}))

    @mcp.tool()
    def say(text: str | None = None, component: dict | None = None, to: list[str] | None = None,
            style: str = "chat") -> str:
        """Слова игрокам: style chat (с именем ведущего), title, subtitle или actionbar; to — кому (по умолчанию
        всем); component — текст JSON как у /tellraw вместо text."""
        return _out(rpc("say", {"text": text, "component": component, "to": to, "style": style}))

    @mcp.tool()
    def map(center: list[int] | None = None, size: int = 128, start: list[int] | None = None,
            end: list[int] | None = None, below: int | None = None, marks: list[dict] | None = None,
            dimension: str | None = None, wait: int = 30):
        """Карта сверху, как ванильная карта: center [x, z] и size (до 1024) или углы start/end [x, z]. Север вверху.
        below — смотреть под потолок с этой высоты (пещеры, Незер, этажи). marks — свои метки [{label, x, z}].
        Игроки — цветными квадратами (легенда). Серая шахматка — чанк не загружен (area_prepare)."""
        return _out(rpc("map", {"center": center, "size": size if center else None, "from": start, "to": end,
                                "below": below, "marks": marks, "dimension": dimension, "wait": wait}))

    @mcp.tool()
    def look(start: list[int], end: list[int], direction: str, dimension: str | None = None, wait: int = 30):
        """Вид на рамку start..end [x, y, z]: direction north/south/east/west (фасад, разрез), down (план), up.
        Ближе — ярче; голубой — насквозь пусто. Грань до 256×256, глубина до 256."""
        return _out(rpc("look", {"from": start, "to": end, "look": direction, "dimension": dimension, "wait": wait}))

    @mcp.tool()
    def blocks(start: list[int], end: list[int], properties: bool = True, dimension: str | None = None) -> str:
        """Блоки рамки текстом (до 64 по оси, до 32768): палитра символ → блок, слои снизу вверх, строки с севера на
        юг, символы с запада на восток; '.' воздух, '?' не загружено. Тот же вид принимает build (op layers)."""
        return _out(rpc("blocks", {"from": start, "to": end, "properties": properties, "dimension": dimension}))

    @mcp.tool()
    def build(ops: list[dict], dimension: str | None = None, wait: int = 30) -> str:
        """Постройка частями между тиками (сервер не встаёт), с откатом. ops по порядку:
        {"op": "fill", "from": [x,y,z], "to": [x,y,z], "block": "stone", "mode": "replace|keep|hollow|outline"},
        {"op": "set", "pos": [x,y,z], "block": "oak_stairs[facing=east]"},
        {"op": "layers", "origin": [x,y,z], "palette": {"#": "stone"}, "layers": [["#.#", ...], ...]}
        (символ не из палитры — не трогать). Ждёт загрузки чанков сам. Ответ — номер работы для undo."""
        return _out(rpc("build", {"ops": ops, "dimension": dimension, "wait": wait}))

    @mcp.tool()
    def undo(job: int, wait: int = 30) -> str:
        """Откатить постройку (или откат) с номером job: вернуть блоки и содержимое блок-сущностей."""
        return _out(rpc("undo", {"job": job, "wait": wait}))

    @mcp.tool()
    def jobs() -> str:
        """Работы ведущего: идущие и последние кончившиеся."""
        return _out(rpc("jobs"))

    @mcp.tool()
    def job(id: int, wait: int = 0):
        """Работа по номеру; wait — ждать её конца до стольких секунд. Снимок отдаёт картинку один раз."""
        return _out(rpc("job", {"id": id, "wait": wait}))

    @mcp.tool()
    def cancel(id: int) -> str:
        """Остановить работу (сделанное остаётся, откат — undo)."""
        return _out(rpc("cancel", {"id": id}))

    @mcp.tool()
    def area_prepare(start: list[int], end: list[int], ttl_seconds: int = 300, wait: int = 0,
                     dimension: str | None = None) -> str:
        """Загрузить чанки рамки start..end [x, z] без тика (в фоне) и держать ttl_seconds: чтобы увидеть картой
        или прочитать то, где никого нет. wait — ждать готовности."""
        return _out(rpc("area.prepare", {"from": start, "to": end, "ttl_seconds": ttl_seconds, "wait": wait,
                                         "dimension": dimension}))

    @mcp.tool()
    def area_release(id: int) -> str:
        """Отпустить подготовленный район."""
        return _out(rpc("area.release", {"id": id}))

    @mcp.tool()
    def areas() -> str:
        """Чанки, которые держит ведущий: районы и постройки."""
        return _out(rpc("areas"))

    @mcp.tool()
    def events(after: int = 0, wait: int = 0, limit: int = 200) -> str:
        """События после номера after: чат, входы и выходы, смерти с причиной, достижения, смена измерения, команды
        игроков, паузы сервера (lag), конец работ (job). wait — ждать новых до 25 с. boot меняется при перезапуске."""
        return _out(rpc("events", {"after": after, "wait": wait, "limit": limit}))

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
    parser = argparse.ArgumentParser(description="Мод ведущего: MCP-сервер, вызов метода, лента событий")
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
