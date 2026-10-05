"""Сессии ведущего: из чего собирается запуск claude для каждой роли.

Роль — свой системный промпт (вместо промпта Claude Code: ведущий — не программист в репозитории), свои инструменты
и права, свой мост. Сессии чистые: без памяти Claude Code, без CLAUDE.md, без чужих навыков и настроек; всё, что
ведущий знает, — навык (skill/ набора и сервера) в промпте и в файлах, которые он читает сам.

  voice    — голос: единственный, кто говорит с игроками; отвечает, смотрит, делает мгновенное, ставит задачи
  worker   — исполнитель задачи: свежая сессия на задачу, мост игры, без слов игрокам
  teacher  — наставник: правит знания сервера по опыту, пробует рецепты на тестовом сервере (не в игре)
  reviewer — независимая проверка правки наставника
  eval     — голос без инструментов на проверочном наборе (то же, что voice в промпте)
"""
import hashlib
import json
import os
import shlex
import sys

import recipes as recipesmod

ROLES = {
    "voice": {"tools": "Read,Glob,Grep", "mode": "dontAsk", "bridge": "bridge",
              "deny": ["mcp__almighty__say", "mcp__almighty__build", "mcp__almighty__bot", "mcp__almighty__area",
                       "mcp__almighty__rule"]},
    "worker": {"tools": "Read,Glob,Grep,WebSearch,WebFetch", "mode": "dontAsk", "bridge": "bridge",
               "deny": ["mcp__almighty__say"]},
    "teacher": {"tools": "Read,Glob,Grep,Edit,Write,WebSearch,WebFetch", "mode": "acceptEdits",
                "bridge": "test_bridge", "deny": ["mcp__almighty__say"]},
    "reviewer": {"tools": "Read,Glob,Grep", "mode": "dontAsk", "bridge": None, "deny": []},
    "eval": {"tools": "Read,Glob,Grep", "mode": "dontAsk", "bridge": None, "deny": []},
}


def _read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        return ""


def _body(text):
    """Текст без шапки --- … --- (у SKILL.md она для Claude Code, в промпте не нужна)."""
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end >= 0:
            return text[end + 5:].strip()
    return text


def system_prompt(cfg, role, skill_dir):
    """Промпт роли: роль, ядро навыка, правила, выученное сервером, факты сервера. skill_dir — навык сервера."""
    kit_skill = os.path.join(cfg["paths"]["kit"], "skill")
    role_file = "voice" if role == "eval" else role
    parts = [_read(os.path.join(kit_skill, "roles", role_file + ".md"))]
    if role != "reviewer":
        parts.append(_body(_read(os.path.join(kit_skill, "SKILL.md"))))
    parts.append(_read(os.path.join(kit_skill, "RULES.md")))
    local_rules = _read(os.path.join(skill_dir, "rules.local.md"))
    if local_rules:
        parts.append("# Правила этого сервера\n\n" + local_rules)
    learned = _read(os.path.join(skill_dir, "CORE.md"))
    if learned and role != "reviewer":
        parts.append("# Выучено на этом сервере\n\n" + learned)
    if role != "reviewer":  # рецепты — в промпте: голос не ищет их по каталогам, а сразу знает, что есть
        found, _ = recipesmod.load_all([os.path.join(kit_skill, "recipes"), os.path.join(skill_dir, "recipes")])
        if found:
            parts.append("# Рецепты (recipe_run; instant — делай сам, остальные — task с recipe и params)\n\n"
                         + "\n".join(f"- {r.summary()}" for r in sorted(found.values(), key=lambda r: r.name)))
    g = cfg["gm"]
    langs = ", ".join(f"{p} — {lang}" for p, lang in sorted(g["languages"].items()))
    parts.append(
        "# Этот сервер\n\n"
        f"- Владельцы (их слово — закон для owner-only дел): {', '.join(g['owners']) or 'не заданы'}.\n"
        f"- Язык ответа по умолчанию: {g['default_language']}"
        + (f"; игроки на своём языке: {langs}.\n" if langs else ".\n") +
        f"- Навык ведущего (читать по делу): {kit_skill} — levers.md (рычаги Almighty), recipes/ (рецепты).\n"
        f"- Знания этого сервера: {skill_dir} — knowledge/ (места, игроки, корабли, что как делается), recipes/.\n")
    return "\n\n".join(p for p in parts if p)


def settings(cfg, role):
    """Права и охрана сессии (--settings): MCP almighty и gm — без вопросов, запрещённое — отказ, хук охраны."""
    r = ROLES[role]
    allow = ["mcp__almighty__*", "mcp__gm__*"] if r["bridge"] else []
    deny = list(r["deny"])
    for section in ("bridge", "test_bridge"):  # токены мостов сессия не читает
        if cfg[section].get("token_file"):
            deny.append("Read(/" + cfg[section]["token_file"] + ")")
    if role == "teacher":  # набор (базовый навык, охрана, проверки) наставник не правит; Edit покрывает и Write
        deny.append(f"Edit(/{cfg['paths']['kit']}/**)")
    if "WebSearch" in r["tools"]:
        allow += ["WebSearch", "WebFetch"]
    guard = f"{shlex.quote(sys.executable)} {shlex.quote(os.path.join(cfg['paths']['kit'], 'hook.py'))}"
    return {
        "permissions": {"allow": allow, "deny": deny, "blockReadsOutsideWorkingDirectories": True},
        "hooks": {"PreToolUse": [{"matcher": "mcp__almighty__.*|Edit|Write|MultiEdit|NotebookEdit",
                                  "hooks": [{"type": "command", "command": guard, "timeout": 30}]}]},
    }


def mcp_config(cfg, role, env):
    r = ROLES[role]
    if not r["bridge"]:
        return {"mcpServers": {}}
    uv = cfg["gm"]["uv"]
    servers = {"gm": {"command": uv, "args": ["run", "--quiet", "--script", os.path.join(cfg["paths"]["kit"], "gm.py"),
                                             "mcp"], "env": {k: v for k, v in env.items() if k.startswith("GM_")}}}
    b = cfg[r["bridge"]]
    if b.get("url") and b.get("token_file"):  # без тестового сервера наставник мира не видит вовсе
        servers["almighty"] = {"command": uv, "args": ["run", "--quiet", "--script", cfg["paths"]["adapter"], "mcp"],
                               "env": {"ALMIGHTY_URL": b["url"], "ALMIGHTY_TOKEN_FILE": b["token_file"]}}
    return {"mcpServers": servers}


def env(cfg, role, skill_dir, task=None, writable=None):
    e = dict(os.environ)
    e.update({"GM_CONFIG": cfg["paths"]["config"], "GM_ROLE": role, "GM_SKILL": skill_dir,
              # чистая сессия: без памяти и CLAUDE.md Claude Code
              "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1", "CLAUDE_CODE_DISABLE_CLAUDE_MDS": "1"})
    for k in ("GM_TASK", "GM_WRITABLE"):
        e.pop(k, None)
    if task is not None:
        e["GM_TASK"] = str(task)
    if writable:
        e["GM_WRITABLE"] = writable
    return e


def prepare(cfg, role, run_dir, skill_dir, task=None, writable=None, add_dirs=()):
    """Каталог запуска (рабочий каталог сессии) с настройками и MCP; ответ — (argv без вывода, env)."""
    os.makedirs(run_dir, exist_ok=True)
    e = env(cfg, role, skill_dir, task, writable)
    with open(os.path.join(run_dir, "settings.json"), "w", encoding="utf-8") as f:
        json.dump(settings(cfg, role), f, ensure_ascii=False, indent=1)
    with open(os.path.join(run_dir, "mcp.json"), "w", encoding="utf-8") as f:
        json.dump(mcp_config(cfg, role, e), f, ensure_ascii=False, indent=1)
    m = cfg["models"]
    model_key = "voice" if role == "eval" else role
    argv = [cfg["gm"]["claude"], "-p", "--model", m[model_key]]
    if m.get(model_key + "_effort") and "haiku" not in m[model_key]:  # у Haiku уровня усилий нет
        argv += ["--effort", m[model_key + "_effort"]]
    argv += ["--system-prompt", system_prompt(cfg, role, skill_dir),
             "--settings", os.path.join(run_dir, "settings.json"),
             "--mcp-config", os.path.join(run_dir, "mcp.json"), "--strict-mcp-config",
             "--setting-sources", "project", "--permission-mode", ROLES[role]["mode"],
             "--tools", ROLES[role]["tools"], "--disable-slash-commands", "--no-session-persistence"]
    if m.get("fallback") and m["fallback"] != m[model_key]:
        argv += ["--fallback-model", m["fallback"]]
    dirs = [os.path.join(cfg["paths"]["kit"], "skill"), skill_dir, *add_dirs]
    for d in dict.fromkeys(dirs):
        argv += ["--add-dir", d]
    return argv, e


def skill_hash(cfg, skill_dir):
    """Отпечаток навыка (набор и сервер): сменился — голос перезапускается в паузе, итоги проверок — новые."""
    h = hashlib.sha256()
    for root in (os.path.join(cfg["paths"]["kit"], "skill"), skill_dir):
        for dirpath, dirnames, files in os.walk(root):
            dirnames.sort()
            for f in sorted(files):
                p = os.path.join(dirpath, f)
                h.update(os.path.relpath(p, root).encode())
                with open(p, "rb") as fh:
                    h.update(fh.read())
    return h.hexdigest()[:16]
