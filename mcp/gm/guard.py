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

Хук: JSON на stdin (tool_name, tool_input), отказ — JSON hookSpecificOutput с permissionDecision deny и причиной,
которую видит модель. Ошибка самой охраны — тоже отказ.
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

SCRIPT_NEVER = [
    (r"\bTicketType\b|\baddRegionTicket\b|\baddTicket\b|\bforceChunk\b|\bsetChunkForced\b",
     "скрипт не ставит тикеты чанков: далёкое место — area prepare"),
    (r"\bgetChunk\s*\(|\bgetChunkAt\s*\(",
     "getChunk грузит чанк синхронно (сервер встаёт): getChunkNow, а далёкое место — area prepare"),
    (r"\bnew\s+Thread\b|\bThread\s*\.\s*start|\bExecutors\b|\bnew\s+Timer\b|\bCompletableFuture\b|\.execute\s*\(",
     "потоки и задачи на потом игре не отдавать: реакция на события — rule"),
    (r"EVENT_BUS|\.addListener\s*\(|\.register\s*\(",
     "слушатели шины из скрипта — нельзя: реакция на события — rule"),
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
    for seg in _segments(c):
        if SPEECH.match(seg):
            return SPEECH_REASON
        for p in ctx.g["never"]:
            if re.search(p, seg, re.I):
                return f"«{seg[:80]}» — ведущему нельзя никогда (запрет сервера)"
        for p in ctx.g["owner_only"]:
            if re.search(p, seg, re.I) and not ctx.owner():
                owners = ", ".join(ctx.cfg["gm"]["owners"]) or "не задан"
                return f"«{seg[:80]}» — только по слову владельца ({owners}); запрос сейчас от {ctx.requester or 'никого'}"
        reason = check_strike(seg, ctx)
        if reason:
            return reason
    return check_plot_numbers(re.findall(r"-?\d+(?:\.\d+)?", c), ctx)


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

def _commands(code):
    """Строки скрипта, похожие на команды."""
    for m in STRING.finditer(code):
        text = next(g for g in m.groups() if g is not None)
        if re.match(r"^/?[a-z_][a-z0-9_:.-]*(\s|$)", text.strip(), re.I):
            yield text


def check_script(code, ctx):
    for pattern, reason in SCRIPT_NEVER + [(p, "запрет сервера в скриптах") for p in ctx.g["script_never"]]:
        if re.search(pattern, code):
            return reason
    for text in _commands(code):
        reason = check_command(text, ctx)
        if reason:
            return f"в строке скрипта «{text.strip()[:60]}»: {reason}"
    return None


def has_strike(code, ctx):
    """Есть ли в скрипте удар (правила guard.strike)."""
    return any(re.search(rule["match"], seg, re.I) for text in _commands(code)
               for seg in _segments(text.strip().lstrip("/").strip()) for rule in ctx.g["strike"])


def remember_rule(store, method, params, ctx):
    """Правило с ударом идёт в игре само, зоны проверены только при его постановке: его скрипт — в память, gmd
    сверяет его с зонами снова, когда они меняются. То же имя без удара или снятое правило — память стирается."""
    name = params.get("name")
    if not name or method not in ("rule.add", "rule.remove"):
        return
    code = params.get("script") if method == "rule.add" else None
    if code and has_strike(code, ctx):
        store.put(RULE_KEY + name, {"script": code, "role": ctx.role, "requester": ctx.requester,
                                    "task": int(os.environ.get("GM_TASK") or 0) or None})
    else:
        store.drop(RULE_KEY + name)


def checker(store):
    """check_call для рецептов на живом сервере: правило с ударом запоминается, как в хуке."""
    def check(method, params, ctx):
        reason = check_call(method, params, ctx)
        if not reason:
            remember_rule(store, method, params, ctx)
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
    if method == "command":
        for c in params.get("commands") or []:
            reason = check_command(c, ctx)
            if reason:
                return reason
    elif method == "function":
        for line in params.get("lines") or []:
            line = line.strip()
            if line.startswith("#"):
                continue
            reason = check_command(line.lstrip("$"), ctx)
            if reason:
                return reason
    elif method in ("script", "rule.add"):
        code = params.get("code") if method == "script" else params.get("script")
        if code:
            reason = check_script(code, ctx)
            if reason or method == "script":
                return reason
            if not params.get("name") and has_strike(code, ctx):
                return ("правило с ударом — только с именем (name): по нему охрана снимет его, если удар заденет "
                        "зону, поставленную позже")
    elif method in ("build", "area.prepare", "bot.spawn"):
        return check_plot_numbers(_numbers(params), ctx)
    elif method == "bot.act":
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
            return "function", {"lines": inp["lines"]}
        return "command", {"commands": inp.get("commands") or []}
    if name == "script":
        return "script", {"code": inp.get("code", "")}
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
            remember_rule(store, *tool_call(tool, inp), ctx)
    except Exception as e:  # охрана не знает, что это, — значит нельзя
        reason = f"охрана не смогла проверить ({type(e).__name__}: {e}) — действие не выполнено"
    if reason:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                                 "permissionDecisionReason": reason}}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
