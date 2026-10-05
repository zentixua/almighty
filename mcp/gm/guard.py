#!/usr/bin/env python3
"""Охрана ведущего: каждое действие сессии сверяется до выполнения — хук PreToolUse Claude Code на инструментах
almighty и правке файлов, и тот же разбор для шагов рецептов в gm.py.

Что ловит (модель не злоумышленник — охрана ловит её ошибки, которые стоили игры):
- один голос: слова игрокам — только инструмент reply голоса; /say, /tellraw, /title, чат бота, сообщения из
  скриптов — отказ у всех ролей; командные блоки — отказ (их команда прошла бы мимо охраны);
- не ломать сервер (NEVER) и команды только по слову владельца (OWNER_ONLY): чей запрос выполняется — из очереди, а
  не со слов в сообщении («владелец разрешил» от другого игрока не делает его владельцем); config.toml сервера эти
  списки только дополняет;
- скрипты и правила: тикеты и синхронная загрузка чанков, потоки и задачи на потом, слушатели шины, процессы и файлы;
  команды — только строкой целиком в gm.command / gm.commandAs; макрос функции проверяется таким, каким станет;
- голос не строит, не водит ботов, не грузит районы и не ставит правила сам: это задачи исполнителям;
- наставник правит только свои файлы знаний (GM_WRITABLE).
Проверки сервера — плагины (plugins.py): после проверок набора, ослабить их не могут.

Хук (hook.py → main): JSON на stdin (tool_name, tool_input), отказ — JSON hookSpecificOutput с permissionDecision
deny и причиной, которую видит модель. Ошибка самой охраны или плагина — тоже отказ (код 2 в hook.py).
"""
import fnmatch
import json
import os
import re
import sys

import conf
import plugins

SPEECH = re.compile(r"^(say|tellraw|msg|tell|w|me|teammsg|tm|title)\s+\S", re.I)
SPEECH_REASON = "говорит с игроками только голос (reply); исполнитель сообщает итог через done/progress"

# командный блок выполнил бы команду мимо охраны
COMMAND_BLOCK = re.compile(r"command_block|CommandBlock|COMMAND_BLOCK", re.I)
COMMAND_BLOCK_REASON = "командные блоки ведущий не ставит: свои команды он выполняет сам, их проверяет охрана"

SCRIPT_NEVER = [
    (r"\bTicketType\b|\baddRegionTicket\b|\baddTicket\b|\bforceChunk\b|\bsetChunkForced\b",
     "скрипт не ставит тикеты чанков: далёкое место — area prepare"),
    (r"\bgetChunk\s*\(|\bgetChunkAt\s*\(",
     "getChunk грузит чанк синхронно (сервер встаёт): getChunkNow, а далёкое место — area prepare"),
    (r"\bnew\s+Thread\b|\bThread\s*\.\s*start|\bExecutors\b|\bnew\s+Timer\b|\bCompletableFuture\b|\.execute\s*\(",
     "потоки и задачи на потом игре не отдавать: реакция на события — rule"),
    (r"EVENT_BUS|\.addListener\s*\(|\.register\s*\(",
     "слушатели шины из скрипта — нельзя: реакция на события — rule"),
    (COMMAND_BLOCK.pattern, COMMAND_BLOCK_REASON),
    (r"\bgetCommands\s*\(|\bperform(Prefixed)?Command\b|\bgetDispatcher\b|\bCommandSourceStack\b",
     "команды из скрипта — только gm.command('…') или gm.commandAs(сущность, '…'): мимо них охрана их не видит"),
    (r"\bRuntime\b|\bProcessBuilder\b|\bSystem\s*\.\s*exit\b|\bhalt\s*\(",
     "процессы и выход JVM — нельзя"),
    (r"\bnew\s+File\b|\bFiles\s*\.|\bPaths?\s*\.\s*(get|of)\b|\bFileWriter\b|\bFileOutputStream\b",
     "файлы сервера скрипт не трогает"),
    (r"sendSystemMessage|broadcastSystemMessage|displayClientMessage|broadcastChatMessage|ClientboundSystemChat"
     r"|ClientboundSetTitle|ClientboundSetSubtitle|ClientboundSetActionBar", SPEECH_REASON),
]

# голос отвечает и делает мгновенное; постройки, боты, районы и правила — задачи исполнителям
VOICE_TASK_ONLY = {"build", "undo", "bot.spawn", "bot.remove", "bot.act", "area.prepare", "area.release",
                   "rule.add", "rule.remove"}

# не ломать сервер: никогда, у всех ролей; config.toml (guard.never) только добавляет
NEVER = [r"^(stop|reload|save-off|save-all\s+flush)\b", r"^forceload\s+add\b"]
# только по слову владельца (тот, чей запрос сейчас выполняется, — из gm.owners); config.toml только добавляет
OWNER_ONLY = [r"^(op|deop|whitelist|ban|ban-ip|pardon|pardon-ip|kick)\b"]

# наставник правит только эти файлы своего каталога
WRITABLE = ["skill/CORE.md", "skill/rules.local.md", "skill/knowledge/*.md", "skill/recipes/*.md",
            "evals/learned.jsonl"]

STRING = re.compile(r'"""(.*?)"""|\'\'\'(.*?)\'\'\'|"((?:[^"\\\n]|\\.)*)"|\'((?:[^\'\\\n]|\\.)*)\'', re.S)


class Ctx:
    """Кто действует: роль, чей запрос (игрок), настройки."""

    def __init__(self, cfg, role, requester=None):
        self.cfg, self.role, self.requester = cfg, role, requester
        self.g = cfg["guard"]

    def owner(self):
        return self.requester is not None and conf.is_owner(self.cfg, self.requester)


# ---------------------------------------------------------------- команды

def check_command(cmd, ctx):
    """Причина отказа или None; cmd — команда без или с «/». Сначала набор по всем частям, потом плагины."""
    c = cmd.strip().lstrip("/").strip()
    if not c:
        return None
    if COMMAND_BLOCK.search(c):
        return COMMAND_BLOCK_REASON
    parts = segments(c)
    for seg in parts:
        if SPEECH.match(seg):
            return SPEECH_REASON
        for p in NEVER + ctx.g["never"]:
            if re.search(p, seg, re.I):
                return f"«{seg[:80]}» — ведущему нельзя никогда (запрет сервера)"
        for p in OWNER_ONLY + ctx.g["owner_only"]:
            if re.search(p, seg, re.I) and not ctx.owner():
                owners = ", ".join(ctx.cfg["gm"]["owners"]) or "не задан"
                return (f"«{seg[:80]}» — только по слову владельца ({owners}); "
                        f"запрос сейчас от {ctx.requester or 'никого'}")
    for seg in parts:
        reason = plugins.check_command(seg, ctx)
        if reason:
            return reason
    return None


def segments(c):
    """execute … run <команда>: каждая часть цепочки — своя команда (первая — вся строка)."""
    return [c] + [s.strip().lstrip("/") for s in re.split(r"\brun\s+", c)[1:]]


# ---------------------------------------------------------------- скрипты

# команды из скрипта — только gm.command('…') и gm.commandAs(сущность, '…') строкой-литералом: команду, собранную
# в скрипте (переменная, сложение строк, подстановка ${…}, args), охрана не видит
CALL = re.compile(r"(\b\w+\s*\.\s*)?\b(command|commandAs)\b(\s*\()?")
COMMAND_CALL_REASON = ("команды из скрипта — только gm.command('…') или gm.commandAs(сущность, '…') строкой целиком: "
                       "собранную в скрипте команду охрана не видит (для игрока — commandAs и @s)")


def _call_args(code, i):
    """Аргументы вызова, у которого «(» в code[i]: тексты верхнего уровня; None — скобка не закрыта."""
    depth, j, start, out = 0, i, i + 1, []
    while j < len(code):
        m = STRING.match(code, j)
        if m:
            j = m.end()
            continue
        ch = code[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                out.append(code[start:j].strip())
                return [a for a in out if a]
        elif ch == "," and depth == 1:
            out.append(code[start:j].strip())
            start = j + 1
        j += 1
    return None


def _literals(arg):
    """Тексты аргумента-литерала: строка или список строк; None — не литерал."""
    m = STRING.fullmatch(arg)
    if m:
        text = next(g for g in m.groups() if g is not None)
        double = arg.startswith('"')
        return None if double and "$" in text else [text]  # "…${x}…" в Groovy — подстановка
    if arg.startswith("[") and arg.endswith("]"):
        items = _call_args("(" + arg[1:-1] + ")", 0)
        if items is None:
            return None
        out = []
        for item in items:
            texts = _literals(item)
            if texts is None:
                return None
            out += texts
        return out
    return None


def _blank(code):
    """Тот же код, строки — пробелами (места те же): слова внутри строк — не вызовы."""
    return STRING.sub(lambda m: m.group(0)[0] + " " * (len(m.group(0)) - 2) + m.group(0)[-1], code)


def script_commands(code):
    """(команды скрипта, причина отказа): команды — только литералы в gm.command/gm.commandAs."""
    commands = []
    for m in CALL.finditer(_blank(code)):
        gm_call = (m.group(1) or "").replace(" ", "") == "gm."
        if not gm_call and not m.group(3):  # event.command и т.п. — чтение, не вызов
            continue
        if not gm_call or not m.group(3):
            return [], COMMAND_CALL_REASON
        args = _call_args(code, m.end() - 1)
        if not args or (m.group(2) == "commandAs" and len(args) < 2):
            return [], COMMAND_CALL_REASON
        for arg in args[1:] if m.group(2) == "commandAs" else args:
            texts = _literals(arg)
            if texts is None:
                return [], COMMAND_CALL_REASON
            commands += texts
    return commands, None


def check_script(code, ctx):
    if not isinstance(code, str):
        return "скрипт — строка"
    for pattern, reason in SCRIPT_NEVER + [(p, "запрет сервера в скриптах") for p in ctx.g["script_never"]]:
        if re.search(pattern, code):
            return reason
    bare = _blank(code)
    if re.search(r"(?<![\w.])gm\b(?!\s*\.\s*\w)|\bgm\s*\.\s*[&\"']", bare):  # gm отдан дальше или вызов по имени
        return COMMAND_CALL_REASON
    commands, reason = script_commands(code)
    if reason:
        return reason
    for text in commands:
        reason = check_command(text, ctx)
        if reason:
            return f"в команде скрипта «{text.strip()[:60]}»: {reason}"
    return None


def note_call(store, method, params, ctx):
    """Вызов на живом мосту разрешён — сразу после проверки, до выполнения (хук сессии и шаги рецептов; у наставника
    мост тестовый — нет): бот и правило с именем от исполнителя — в журнал задачи (оборванную задачу gmd убирает за
    собой); потом after_call плагинов сервера."""
    params = params or {}
    task = int(os.environ.get("GM_TASK") or 0) or None
    name = params.get("name")
    if task and ctx.role == "worker" and method in ("rule.add", "bot.spawn") and isinstance(name, str) and name:
        store.log(ctx.role, "made", ctx.requester, task, what="rule" if method == "rule.add" else "bot", name=name)
    plugins.after_call(store, method, params, ctx)


def checker(store):
    """check_call для рецептов на живом сервере: то же, что хук после разрешённого вызова (note_call)."""
    def check(method, params, ctx):
        reason = check_call(method, params, ctx)
        if not reason:
            note_call(store, method, params, ctx)
        return reason
    return check


# ---------------------------------------------------------------- вызовы моста

def check_call(method, params, ctx):
    """Причина отказа вызова метода моста или None: сначала проверки набора, потом плагины сервера."""
    params = params or {}
    return _check_call(method, params, ctx) or plugins.check_call(method, params, ctx)


def _check_call(method, params, ctx):
    if method == "say":
        return SPEECH_REASON
    if ctx.role == "voice" and method in VOICE_TASK_ONLY:
        return f"{method} — не мгновенное дело: голос ставит задачу (task), её делает исполнитель"
    if method in ("command", "function"):
        key = "commands" if method == "command" else "lines"
        items = params.get(key)
        items = [items] if isinstance(items, str) else [] if items is None else items
        if not isinstance(items, list) or not all(isinstance(c, str) for c in items):
            return f"{key} — список строк: иначе охрана их не проверит"
        args = params.get("args") or {}
        if not isinstance(args, dict):
            return "args — объект {имя: значение}"
        for line in items:
            line = line.strip()
            if method == "function":
                if not line or line.startswith("#"):
                    continue
                if line.startswith("$"):  # макрос: проверяется команда, какой она станет
                    missing = [n for n in re.findall(r"\$\(([\w.]+)\)", line) if n not in args]
                    if missing:
                        return f"макрос $({missing[0]}) без значения в args"
                    line = re.sub(r"\$\(([\w.]+)\)", lambda m: _macro_value(args[m.group(1)]), line[1:])
            reason = check_command(line, ctx)
            if reason:
                return reason
        return None
    if method in ("script", "rule.add"):
        code = params.get("code") if method == "script" else params.get("script")
        return None if code is None else check_script(code, ctx)
    if method == "build" and COMMAND_BLOCK.search(json.dumps(params)):
        return COMMAND_BLOCK_REASON
    if method == "bot.act":
        if not isinstance(params.get("actions") or [], list):
            return "actions — список шагов"
        for step in params.get("actions") or []:
            chat = step.get("chat") if isinstance(step, dict) else None
            if chat is not None:
                if not str(chat).startswith("/"):
                    return "бот не говорит в чат: " + SPEECH_REASON
                reason = check_command(str(chat), ctx)
                if reason:
                    return reason
    return None


def _macro_value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    return v if isinstance(v, str) else json.dumps(v)


def numbers(value):
    """Все числа в параметрах вызова (без bool) — для проверок плагинов по координатам."""
    if isinstance(value, bool):
        return []
    if isinstance(value, (int, float)):
        return [value]
    if isinstance(value, dict):
        return [n for v in value.values() for n in numbers(v)]
    if isinstance(value, list):
        return [n for v in value for n in numbers(v)]
    return []


def _given(inp, keys, rename=None):
    """Параметры инструмента, которые адаптер передаёт мосту (None он отбрасывает), с именами моста."""
    rename = rename or {}
    return {rename.get(k, k): inp[k] for k in keys if inp.get(k) is not None}


SPAN = {"start": "from", "end": "to"}


def tool_call(tool, inp):
    """Инструмент MCP almighty и его параметры → (метод моста, параметры) — то, что шлёт мосту адаптер
    (mcp/almighty.py, тест AdapterMirrorTest), без его значений по умолчанию: охрана и плагины видят тот же вызов."""
    name = tool.removeprefix("mcp__almighty__")
    if name == "status":
        return ("player", {"name": inp["player"]}) if inp.get("player") is not None else ("status", {})
    if name == "entities":
        return "entities", _given(inp, ("center", "radius", "start", "end", "type", "limit", "dimension"), SPAN)
    if name == "command":
        if inp.get("lines") is not None:
            return "function", _given(inp, ("lines", "args", "pos", "dimension"))
        return "command", _given(inp, ("commands", "args", "pos", "dimension"))
    if name == "say":
        return "say", _given(inp, ("text", "component", "to", "style"))
    if name == "script":
        return "script", _given(inp, ("code", "args", "timeout_ms"))
    if name == "rule":
        action = inp.get("action", "list")
        if action == "add":
            return "rule.add", _given(inp, ("event", "script", "name", "every", "limit", "priority", "canceled",
                                            "budget_ms", "persist"))
        if action == "remove":
            return "rule.remove", _given(inp, ("id", "name"))
        if action == "types":
            return "event.types", _given(inp, ("query",))
        return "rules", {}
    if name == "events":
        return "events", _given(inp, ("after", "wait", "limit"))
    if name == "view":
        kind = inp.get("kind")
        if kind == "eye":
            who = inp.get("who")
            ident = {} if who is None else {"uuid": who} if len(who) == 36 and who.count("-") == 4 else {"name": who}
            return "see", {**ident, **_given(inp, ("eye", "yaw", "pitch", "dimension", "image", "width", "height", "fov",
                                                   "distance", "radius", "wait"), {"eye": "at"})}
        if kind == "map":
            keys = ("center", "size", "start", "end", "below", "marks", "dimension", "wait")
            return "map", _given(inp, keys if inp.get("center") is not None else keys[:1] + keys[2:], SPAN)
        if kind == "look":
            return "look", _given(inp, ("start", "end", "direction", "dimension", "wait"), {**SPAN, "direction": "look"})
        if kind == "blocks":
            return "blocks", _given(inp, ("start", "end", "properties", "dimension"), SPAN)
        return "view", inp
    if name == "build":
        if inp.get("undo") is not None:
            return "undo", {"job": inp["undo"], **_given(inp, ("wait",))}
        return "build", _given(inp, ("ops", "dimension", "wait"))
    if name == "area":
        action = inp.get("action", "list")
        if action == "prepare":
            return "area.prepare", _given(inp, ("start", "end", "ttl_seconds", "wait", "dimension"), SPAN)
        if action == "release":
            return "area.release", _given(inp, ("id",))
        return "areas", {}
    if name == "job":
        if inp.get("id") is None:
            return "jobs", {}
        return ("cancel", {"id": inp["id"]}) if inp.get("cancel") else ("job", _given(inp, ("id", "wait")))
    if name == "bot":
        action = inp.get("action", "list")
        keys = {"spawn": ("name", "pos", "dimension", "yaw", "pitch", "gamemode", "skin", "marker", "auto_respawn"),
                "remove": ("name",), "state": ("name", "after"), "act": ("name", "actions", "replace", "wait")}
        method = {"spawn": "bot.spawn", "remove": "bot.remove", "state": "bot", "act": "bot.act"}.get(action)
        return (method, _given(inp, keys[action])) if method else ("bots", {})
    if name == "notes":
        return "notes", {}
    if name == "call":
        return inp.get("method", ""), {k: v for k, v in (inp.get("params") or {}).items() if v is not None}
    return name, inp


def check_edit(path, ctx):
    root = os.environ.get("GM_WRITABLE")
    if ctx.role != "teacher" or not root:
        return "файлы правит только наставник, и только свои знания"
    rel = os.path.relpath(os.path.realpath(path), os.path.realpath(root))
    if rel.startswith("..") or not any(fnmatch.fnmatchcase(rel, g) for g in WRITABLE):
        return f"{rel}: наставник правит только {', '.join(WRITABLE)}"
    return None


def check_tool(tool, inp, ctx):
    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        return check_edit(inp.get("file_path") or inp.get("notebook_path") or "", ctx)
    if tool.startswith("mcp__almighty__"):
        method, params = tool_call(tool, inp)
        return check_call(method, params, ctx)
    return None


def requester(cfg, role, store):
    """Чей запрос сейчас выполняется: у исполнителя — игрок задачи, у голоса — игрок этого хода."""
    if role == "worker" and os.environ.get("GM_TASK"):
        task = store.task(int(os.environ["GM_TASK"]))
        return task["player"] if task else None
    if role == "voice":
        return store.get("voice.requester")
    return None


def main():
    role = os.environ.get("GM_ROLE", "")
    try:
        event = json.load(sys.stdin)
        cfg = conf.load()
        plugins.load(cfg)  # плагин сервера не загрузился — отказ во всём, а не охрана без его проверок
        import store as storemod
        store = storemod.Store(cfg["paths"]["db"])
        ctx = Ctx(cfg, role, requester(cfg, role, store))
        tool, inp = event.get("tool_name", ""), event.get("tool_input") or {}
        reason = check_tool(tool, inp, ctx)
        if reason:
            store.log(role, "guard_deny", ctx.requester, int(os.environ.get("GM_TASK") or 0) or None,
                      tool=tool, input=json.dumps(inp, ensure_ascii=False)[:2000], reason=reason)
        elif role != "teacher" and tool.startswith("mcp__almighty__"):  # у наставника мост — тестовый
            note_call(store, *tool_call(tool, inp), ctx)
    except Exception as e:  # охрана не знает, что это, — значит нельзя
        reason = f"охрана не смогла проверить ({type(e).__name__}: {e}) — действие не выполнено"
    if reason:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                                 "permissionDecisionReason": reason}}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
