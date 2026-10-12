#!/usr/bin/env python3
"""Хук PreToolUse ведущего: только то, что заведомо роняет сервер, дела владельца и один голос в чате.

Хук Claude Code (https://code.claude.com/docs/en/hooks): JSON вызова на stdin, решение — JSON hookSpecificOutput
с permissionDecision: deny — нельзя никогда (причину видит модель), ask — только с подтверждения владельца (запрос
разрешения приходит ему в приложение и в игру через канал almighty-chat). Остальное ведущему можно.

Что запрещено — то, что роняет или замораживает сервер:
- stop, reload, save-off, save-all flush, forceload add; /tick rate выше TICK_RATE_MAX;
- скрипты и правила: тикет чанка с замыканием, синхронная загрузка чанка, потоки и задачи на потом,
  слушатели шины, сон на потоке сервера (Thread.sleep замораживает игру), выход JVM и процессы;
- постройка, загрузка района и телепорт на участки Sable (x или z от 20 000 000): постройка там роняет сервер.

Один голос: игрокам пишет только главная сессия. Вызов из субагента хук узнаёт по agent_id во входе (доки hooks,
Common input fields) и не пускает его слова в чат: say, команды чата, реплики ботов.
"""
import json
import re
import sys

NEVER = [
    (r"^(stop|reload|save-off)\b|^save-all\s+flush\b", "stops or breaks the server"),
    (r"^forceload\s+add\b", "forceload add loads chunks synchronously and for good: for a distant place use area prepare"),
]
OWNER = r"^(op|deop|whitelist|ban|ban-ip|pardon|pardon-ip|kick)\b"
TICK_RATE = re.compile(r"^tick\s+rate\s+(\d+(?:\.\d+)?)")
TICK_RATE_MAX = 100

SCRIPT_NEVER = [
    (r"\bTicketType\b|\baddRegionTicket\b|\baddTicket\b|\bforceChunk\b|\bsetChunkForced\b",
     "chunk tickets from a script crash the server: for a distant place use area prepare"),
    (r"\bgetChunk\s*\(|\bgetChunkAt\s*\(",
     "getChunk loads a chunk synchronously (the server freezes): use getChunkNow, and area prepare for a distant place"),
    (r"\bnew\s+Thread\b|\bThread\s*\.\s*start|\bExecutors\b|\bnew\s+Timer\b|\bCompletableFuture\b"
     r"|\.(execute|submit|schedule)\s*[({]",
     "do not hand threads, processes or deferred tasks to the game: react to events with rule"),
    (r"EVENT_BUS|\.addListener\s*\(|\.register\s*\(", "no event bus listeners from a script: react to events with rule"),
    (r"\bThread\s*\.\s*sleep\b|(?<![\w.])sleep\s*\(|\.wait\s*\(\s*\)",
     "sleeping on the server thread freezes the game: wait between calls, and use every in a rule"),
    (r"\bSystem\s*\.\s*exit\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*(exec|exit|halt)\b|\bProcessBuilder\b",
     "no JVM exit or processes from the game: run host commands with Bash"),
]
COMMAND_LITERAL = re.compile(r"""\bgm\s*\.\s*command(?:As)?\s*\((?:[^'"()]*,\s*)?(['"])((?:\\.|(?!\1).)*)\1""")

CHAT = re.compile(r"^(say|tellraw|tell|msg|w|me|teammsg|tm|title)\b")
ONE_VOICE = "only the main game master session talks to players: return the result to it, and it will tell them"

PLOT = 20_000_000
FAR_COMMAND = re.compile(r"^(tp|teleport|spreadplayers|fill|clone|setblock|place|forceload)\b")


def segments(cmd):
    """execute … run <команда>: каждая часть цепочки — своя команда."""
    c = cmd.strip().lstrip("/").strip()
    return [c] + [s.strip().lstrip("/") for s in re.split(r"\brun\s+", c)[1:]]


def numbers(value):
    if isinstance(value, bool):
        return []
    if isinstance(value, (int, float)):
        return [value]
    if isinstance(value, str):
        return [float(n) for n in re.findall(r"(?<![\w.~^])-?\d+(?:\.\d+)?", value)]
    if isinstance(value, dict):
        return [n for v in value.values() for n in numbers(v)]
    if isinstance(value, list):
        return [n for v in value for n in numbers(v)]
    return []


def on_plot(value):
    return any(abs(n) >= PLOT for n in numbers(value))


def check_command(cmd):
    """(решение, причина) или None."""
    for seg in segments(cmd):
        for pattern, why in NEVER:
            if re.search(pattern, seg, re.I):
                return "deny", f'"{seg[:80]}": {why}'
        m = TICK_RATE.match(seg)
        if m and float(m.group(1)) > TICK_RATE_MAX:
            return "deny", f"/tick rate above {TICK_RATE_MAX} crashes the server"
        if FAR_COMMAND.match(seg) and on_plot(seg):
            return "deny", "do not load Sable ship plots (x or z from 20,000,000): reach a ship at its position in the world"
        if re.search(OWNER, seg, re.I):
            return "ask", f'"{seg[:80]}": needs the owner\'s approval'
    return None


def check_script(code):
    if not isinstance(code, str):
        return None
    for pattern, why in SCRIPT_NEVER:
        if re.search(pattern, code):
            return "deny", why
    for m in COMMAND_LITERAL.finditer(code):
        found = check_command(m.group(2))
        if found:
            return found
    return None


def expand(lines, args):
    """Строки функции такими, какими станут: $(имя) — значение из args."""
    args = args or {}
    return [re.sub(r"\$\((\w+)\)", lambda m: str(args.get(m.group(1), m.group(0))), l).lstrip("$") for l in lines]


def first(*found):
    """Самое строгое решение: deny важнее ask."""
    found = [f for f in found if f]
    return next((f for f in found if f[0] == "deny"), found[0] if found else None)


def check_voice(method, p):
    """Слова игрокам из субагента: say, команда чата (и в цепочке execute … run) или реплика бота."""
    if method == "say":
        return "deny", ONE_VOICE
    if method in ("command", "function"):
        lines = p.get("commands") if method == "command" else expand(p.get("lines") or [], p.get("args"))
        if any(CHAT.match(seg) for c in lines or [] for seg in segments(c)):
            return "deny", ONE_VOICE
    if method == "bot.act":
        said = [a["chat"] for a in p.get("actions") or [] if isinstance(a, dict) and isinstance(a.get("chat"), str)]
        # «/команда» в чате бота — команда, а не слова; кроме команд чата
        if any(not c.startswith("/") or any(CHAT.match(seg) for seg in segments(c)) for c in said):
            return "deny", ONE_VOICE
    return None


def check_call(method, p, subagent=False):
    """Метод моста и его параметры — как их шлёт адаптер almighty.py; subagent — вызов из субагента."""
    p = p or {}
    if subagent:
        found = check_voice(method, p)
        if found:
            return found
    if method == "command":
        return first(*(check_command(c) for c in p.get("commands") or []))
    if method == "function":
        return first(*(check_command(c) for c in expand(p.get("lines") or [], p.get("args"))))
    if method == "script":
        return check_script(p.get("code"))
    if method == "rule.add":
        return check_script(p.get("script"))
    if method in ("build", "area.prepare") and on_plot([p.get("ops"), p.get("from"), p.get("to")]):
        return "deny", "building and loading areas on Sable plots (x or z from 20,000,000) crashes the server"
    if method == "bot.spawn" and on_plot(p.get("pos")):
        return "deny", "a bot on a Sable plot loads chunks there: reach a ship at its position in the world"
    if method == "bot.act":
        return first(*(check_command(a["chat"]) for a in p.get("actions") or []
                       if isinstance(a, dict) and isinstance(a.get("chat"), str) and a["chat"].startswith("/")))
    return None


TOOLS = {  # инструмент MCP almighty → (метод моста, параметры)
    "command": lambda i: ("function", i) if i.get("lines") is not None else ("command", i),
    "script": lambda i: ("script", i),
    "rule": lambda i: ("rule.add", i) if i.get("action") == "add" else (None, i),
    "build": lambda i: ("build", i),
    "area": lambda i: ("area.prepare", {"from": i.get("start"), "to": i.get("end")})
    if i.get("action") == "prepare" else (None, i),
    "bot": lambda i: ({"spawn": "bot.spawn", "act": "bot.act"}.get(i.get("action")), i),
    "say": lambda i: ("say", i),
    "call": lambda i: (i.get("method"), i.get("params")),
}


def decide(tool, tool_input, subagent=False):
    name = tool.removeprefix("mcp__almighty__")
    if name == tool or name not in TOOLS:
        return None
    method, params = TOOLS[name](tool_input or {})
    return check_call(method, params, subagent) if method else None


def main():
    event = json.load(sys.stdin)
    found = decide(event.get("tool_name", ""), event.get("tool_input"), "agent_id" in event)
    if found:
        decision, reason = found
        json.dump({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": decision,
                                          "permissionDecisionReason": reason}}, sys.stdout, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001 — сломалась сама охрана: отказ (код 2 блокирует вызов), а не пропуск
        print(f"game master guard failed: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(2)
