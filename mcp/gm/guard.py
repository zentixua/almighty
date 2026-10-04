#!/usr/bin/env python3
"""Охрана ведущего: каждое действие сессии сверяется до выполнения — хук PreToolUse Claude Code на инструментах
almighty и правке файлов, и тот же разбор для шагов рецептов в gm.py.

Что ловит (модель не злоумышленник — охрана ловит её ошибки, которые на 04.10 стоили игры):
- один голос: слова игрокам — только инструмент reply голоса; /say, /tellraw, /title, чат бота, сообщения из
  скриптов — отказ у всех ролей;
- запреты сервера (never) и команды только по слову владельца (owner_only): чей запрос выполняется — из очереди, а не
  со слов в сообщении («Артём разрешил» от другого игрока не делает его владельцем);
- удары (strike из config.toml): цель только числами, путь и разброс сверяются с охраняемыми зонами; правило с
  ударом — только с именем: gmd сверяет его скрипт с зонами снова и снимает, если новая зона на пути;
- скрипты и правила: тикеты и синхронная загрузка чанков, потоки и задачи на потом, слушатели шины, процессы и файлы;
- координаты участков кораблей Sable (x ≥ plot_x) — ни постройки, ни загрузки, ни команды;
- голос не строит и не водит ботов сам: это задачи исполнителям;
- наставник правит только свои файлы знаний (GM_WRITABLE).

Хук (hook.py → main): JSON на stdin (tool_name, tool_input), отказ — JSON hookSpecificOutput с permissionDecision
deny и причиной, которую видит модель. Ошибка самой охраны — тоже отказ (код 2 в hook.py).
"""
import fnmatch
import json
import os
import re
import sys

import conf
import zones as zonesmod

NUM = re.compile(r"^-?\d+(\.\d+)?$")
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

# наставник правит только эти файлы своего каталога
WRITABLE = ["skill/CORE.md", "skill/rules.local.md", "skill/knowledge/*.md", "skill/recipes/*.md",
            "evals/learned.jsonl"]

# скрипт правила с ударом (имя → скрипт, роль, чей запрос) — в gm.db: gmd сверяет его с зонами снова
RULE_KEY = "strike_rule:"

STRING = re.compile(r'"""(.*?)"""|\'\'\'(.*?)\'\'\'|"((?:[^"\\\n]|\\.)*)"|\'((?:[^\'\\\n]|\\.)*)\'', re.S)


class Ctx:
    """Кто действует: роль, чей запрос (игрок), настройки; зоны читаются при первом ударе."""

    def __init__(self, cfg, role, requester=None):
        self.cfg, self.role, self.requester = cfg, role, requester
        self.g = cfg["guard"]

    def owner(self):
        return self.requester is not None and conf.is_owner(self.cfg, self.requester)


# ---------------------------------------------------------------- команды

def check_command(cmd, ctx):
    """Причина отказа или None; cmd — команда без или с «/»."""
    c = cmd.strip().lstrip("/").strip()
    if not c:
        return None
    if COMMAND_BLOCK.search(c):
        return COMMAND_BLOCK_REASON
    for seg in _segments(c):
        if SPEECH.match(seg):
            return SPEECH_REASON
        for p in ctx.g["never"]:
            if re.search(p, seg, re.I):
                return f"«{seg[:80]}» — ведущему нельзя никогда (запрет сервера)"
        for p in ctx.g["owner_only"]:
            if re.search(p, seg, re.I) and not ctx.owner():
                owners = ", ".join(ctx.cfg["gm"]["owners"]) or "не задан"
                return (f"«{seg[:80]}» — только по слову владельца ({owners}); "
                        f"запрос сейчас от {ctx.requester or 'никого'}")
        reason = check_strike(seg, ctx)
        if reason:
            return reason
    if re.match(r"(scoreboard|xp|experience|time|gamerule|attribute|bossbar)\b", c, re.I):  # числа — не места
        return None
    return check_plot_numbers(re.findall(r"-?\d+(?:\.\d+)?", re.sub(r"\[I;[^\]]*\]", "", c)), ctx)


def _segments(c):
    """execute … run <команда>: каждая часть цепочки — своя команда."""
    return [c] + [s.strip().lstrip("/") for s in re.split(r"\brun\s+", c)[1:]]


def check_strike(seg, ctx):
    for rule in ctx.g["strike"]:
        if not re.search(rule["match"], seg, re.I):
            continue
        if rule.get("deny"):
            return rule["deny"]
        if rule.get("owner") and not ctx.owner():
            return f"«{seg[:80]}» — только по слову владельца; запрос сейчас от {ctx.requester or 'никого'}"
        at = re.search(rule.get("target", r"\bat\s+(\S+)\s+(\S+)\s+(\S+)"), seg, re.I)
        if not at:
            return ("удар — только по месту числами (at x y z): цель-сущность, me и look ведущему нельзя — "
                    "так не проверить зоны и нельзя навести на игрока")
        x, _, z = at.group(1), at.group(2), at.group(3)
        frm = re.search(rule.get("from", r"\bfrom\s+(\S+)\s+(\S+)"), seg, re.I)
        via_m = re.search(r"\bvia\s+(.*?)(?=\s+(?:from|at)\b|$)", seg, re.I)
        via_tokens = via_m.group(1).split() if via_m else []
        numbers = [x, z] + (list(frm.groups()) if frm else []) + via_tokens
        if not all(NUM.match(n) for n in numbers) or len(via_tokens) % 2:
            return "координаты удара — только числами (без ~, ^ и подстановок): иначе зоны не проверить"
        spread = 0
        if rule.get("spread"):
            s = re.search(rule["spread"], seg, re.I)
            spread = int(s.group(1)) if s else 0
        via = [(float(via_tokens[i]), float(via_tokens[i + 1])) for i in range(0, len(via_tokens), 2)]
        found = zonesmod.conflicts(zonesmod.load(ctx.cfg["paths"]["zones"]), (float(x), float(z)),
                                   (float(frm.group(1)), float(frm.group(2))) if frm else None, via, spread,
                                   rule.get("margin", ctx.g["margin"]))
        if found:
            return "НЕЛЬЗЯ — охраняемые зоны: " + "; ".join(found)
        return None
    return None


def check_plot_numbers(numbers, ctx):
    for n in numbers:
        try:
            if abs(float(n)) >= ctx.g["plot_x"]:
                return (f"координата {n} — участки кораблей Sable (x ≥ {ctx.g['plot_x']}): там не строить, не грузить "
                        "и не телепортировать; корабль — по его месту в мире")
        except (TypeError, ValueError):
            pass
    return None


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


def has_strike(code, ctx):
    """Есть ли в скрипте удар (правила guard.strike)."""
    return any(re.search(rule["match"], seg, re.I) for text in script_commands(code)[0]
               for seg in _segments(text.strip().lstrip("/").strip()) for rule in ctx.g["strike"])


def note_call(store, method, params, ctx):
    """После разрешённого вызова на живом сервере.
    - Правило с ударом идёт в игре само, зоны проверены только при его постановке: его скрипт — в память, gmd
      сверяет его с зонами снова, когда они меняются. То же имя без удара или снятое правило — память стирается.
    - Бот и правило с именем от исполнителя — в журнал задачи: оборванную задачу gmd убирает за собой."""
    task = int(os.environ.get("GM_TASK") or 0) or None
    name = params.get("name")
    if not isinstance(name, str) or not name:
        return
    if method in ("rule.add", "rule.remove"):
        code = params.get("script") if method == "rule.add" else None
        if isinstance(code, str) and has_strike(code, ctx):
            store.put(RULE_KEY + name, {"script": code, "role": ctx.role, "requester": ctx.requester, "task": task})
        else:
            store.drop(RULE_KEY + name)
    if task and ctx.role == "worker" and method in ("rule.add", "bot.spawn"):
        store.log(ctx.role, "made", ctx.requester, task, what="rule" if method == "rule.add" else "bot", name=name)


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
    """Причина отказа вызова метода моста или None."""
    params = params or {}
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
        return check_plot_numbers(_numbers(params.get("pos")), ctx)
    if method in ("script", "rule.add"):
        code = params.get("code") if method == "script" else params.get("script")
        if code is None:
            return None
        reason = check_script(code, ctx)
        if reason or method == "script":
            return reason
        if not params.get("name") and has_strike(code, ctx):
            return ("правило с ударом — только с именем (name): по нему охрана снимет его, если удар заденет "
                    "зону, поставленную позже")
        return None
    if method == "build" and COMMAND_BLOCK.search(json.dumps(params)):
        return COMMAND_BLOCK_REASON
    if method in ("build", "area.prepare", "bot.spawn"):
        return check_plot_numbers(_numbers(params), ctx)
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
        return check_plot_numbers(_numbers(params), ctx)
    return None


def _macro_value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    return v if isinstance(v, str) else json.dumps(v)


def _numbers(value):
    if isinstance(value, bool):
        return []
    if isinstance(value, (int, float)):
        return [value]
    if isinstance(value, dict):
        return [n for v in value.values() for n in _numbers(v)]
    if isinstance(value, list):
        return [n for v in value for n in _numbers(v)]
    return []


def tool_call(tool, inp):
    """Инструмент MCP almighty и его параметры → (метод моста, параметры) для проверки."""
    name = tool.removeprefix("mcp__almighty__")
    if name == "command":
        if inp.get("lines") is not None:
            return "function", {k: inp.get(k) for k in ("lines", "args", "pos")}
        return "command", {k: inp.get(k) for k in ("commands", "args", "pos")}
    if name == "script":
        return "script", {k: inp.get(k) for k in ("code", "args")}
    if name == "rule":
        action = inp.get("action", "list")
        return {"add": "rule.add", "remove": "rule.remove"}.get(action, "rules"), inp
    if name == "build":
        return ("undo", inp) if inp.get("undo") is not None else ("build", {"ops": inp.get("ops")})
    if name == "area":
        action = inp.get("action", "list")
        return {"prepare": "area.prepare", "release": "area.release"}.get(action, "areas"), {
            "from": inp.get("start"), "to": inp.get("end")}
    if name == "bot":
        action = inp.get("action", "list")
        return {"spawn": "bot.spawn", "remove": "bot.remove", "act": "bot.act", "state": "bot"}.get(action, "bots"), inp
    if name == "call":
        return inp.get("method", ""), inp.get("params") or {}
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
