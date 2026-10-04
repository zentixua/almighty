#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# ///
"""Ворота навыка: правка наставника входит в навык, только если она не делает ведущего хуже.

1. Порядок: только свои файлы (guard.WRITABLE); evals/learned.jsonl и rules.local.md — только дописываются;
   CORE.md — не длиннее limits.core_lines; рецепты годны (recipes.validate) и каждый новый или изменённый — с
   удачной пробой этого же текста на тестовом сервере (recipe_test в журнале).
2. Проверочный набор: голос с новым навыком отвечает на случаи evals/ (набора и сервера) не хуже, чем с прежним:
   случай, что проходил, не падает (упавший — ещё одна попытка), новые случаи урока проходят.
3. Независимый взгляд: сессия-рецензент читает дифф и говорит clean или block с причинами.

  uv run mcp/gm/gate.py evals [--skill DIR]   — проверочный набор на навыке (по умолчанию — навык сервера)
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import conf  # noqa: E402
import guard  # noqa: E402
import recipes as recipesmod  # noqa: E402
import roles  # noqa: E402
import store as storemod  # noqa: E402

ACTIONS = ["answer", "look", "instant", "task", "refuse", "ask"]
BATCH = 15
APPEND_ONLY = ("evals/learned.jsonl", "skill/rules.local.md")

EVAL_SCHEMA = {
    "type": "object",
    "properties": {"cases": {"type": "array", "items": {
        "type": "object",
        "properties": {"id": {"type": "string"}, "action": {"type": "string", "enum": ACTIONS},
                       "recipe": {"type": ["string", "null"]}, "reply": {"type": "string"}},
        "required": ["id", "action", "reply"]}}},
    "required": ["cases"],
}
REVIEW_SCHEMA = {
    "type": "object",
    "properties": {"verdict": {"type": "string", "enum": ["clean", "block"]},
                   "reasons": {"type": "array", "items": {"type": "string"}}},
    "required": ["verdict", "reasons"],
}

EVAL_PROMPT = """Проверка навыка, не игра: инструментов нет, в мир ничего не уходит. Ниже — сообщения игроков, каждое
отдельно, со своим id. Для каждого реши, как ты поступил бы в игре, и ответь JSON по схеме:
- action — что делаешь первым: answer (ответить сразу, из знаний), look (сначала посмотреть мир — status, view,
  entities — и ответить), instant (сделать сразу командой или рецептом instant), task (поставить задачу
  исполнителю), refuse (нельзя — отказать с причиной), ask (уточнить одно, без чего не сделать);
- recipe — имя рецепта, если берёшь его (иначе null);
- reply — что игрок прочитает в чате первым сообщением, дословно, на его языке.
"""


def git(cwd, *args):
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout


# ---------------------------------------------------------------- порядок

def lint(cfg, store, wt, base, since_log=0):
    """Ошибки порядка правки списком; wt — копия с правкой (изменения в индексе), base — коммит до неё."""
    errors = []
    diff = git(wt, "diff", "--cached", "--name-status", "--no-renames", base)
    changed = [line.split("\t") for line in diff.splitlines()]
    for status, path in changed:
        if not any(fnmatch.fnmatchcase(path, g) for g in guard.WRITABLE):
            errors.append(f"{path}: наставник правит только {', '.join(guard.WRITABLE)}")
            continue
        if path in APPEND_ONLY:
            old = subprocess.run(["git", "show", f"{base}:{path}"], cwd=wt, capture_output=True, text=True).stdout
            new = _read(os.path.join(wt, path)) if status != "D" else ""
            if not new.startswith(old):
                errors.append(f"{path}: только дописывать в конец (прежнее не трогать)")
    core = _read(os.path.join(wt, "skill", "CORE.md"))
    if len(core.splitlines()) > cfg["limits"]["core_lines"]:
        errors.append(f"skill/CORE.md: {len(core.splitlines())} строк — больше {cfg['limits']['core_lines']}: "
                      "подробности — в knowledge/, в CORE — только то, что нужно в каждом ходе")
    tested = {(e["data"].get("recipe"), e["data"].get("sha256")) for e in store.logs(since_log, ["recipe_test"], 10000)
              if e["data"].get("ok")}
    for status, path in changed:
        if not path.startswith("skill/recipes/") or status == "D":
            continue
        full = os.path.join(wt, path)
        try:
            r = recipesmod.parse(full)
            problems = recipesmod.validate(r)
        except recipesmod.RecipeError as e:
            problems = [str(e)]
            r = None
        errors += [f"{path}: {p}" for p in problems]
        if r is not None and not problems:
            with open(full, "rb") as f:
                digest = hashlib.sha256(f.read()).hexdigest()
            if (r.name, digest) not in tested:
                errors.append(f"{path}: нет удачной пробы этого текста на тестовом сервере (recipe_test)")
    seen = set()
    for f in _eval_files(cfg, os.path.join(wt, "skill"), wt):
        for n, line in enumerate(_read(f).splitlines(), 1):
            if not line.strip():
                continue
            try:
                case = json.loads(line)
                assert isinstance(case.get("id"), str) and isinstance(case.get("text"), str)
                assert isinstance(case.get("expect"), dict)
            except (ValueError, AssertionError):
                where = os.path.relpath(f, wt) if f.startswith(wt) else f
                errors.append(f"{where}:{n}: случай — JSON с id, text, expect")
                continue
            if case["id"] in seen:
                errors.append(f"{f}:{n}: id {case['id']} уже есть")
            seen.add(case["id"])
    return errors


def _read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return ""


# ---------------------------------------------------------------- проверочный набор

def _eval_files(cfg, skill_dir, overlay=None):
    base = os.path.join(cfg["paths"]["kit"], "evals", "base.jsonl")
    learned = os.path.join(overlay or os.path.dirname(skill_dir), "evals", "learned.jsonl")
    return [p for p in (base, learned) if os.path.exists(p)]


def load_cases(cfg, skill_dir, overlay=None):
    cases = []
    for f in _eval_files(cfg, skill_dir, overlay):
        for line in _read(f).splitlines():
            if line.strip():
                cases.append(json.loads(line))
    return cases


def grade(case, answer):
    """Пусто — случай пройден; иначе — что не так."""
    exp, problems = case["expect"], []
    if answer is None:
        return ["нет ответа на случай"]
    reply = answer.get("reply") or ""
    if exp.get("action") and answer.get("action") not in exp["action"]:
        problems.append(f"action {answer.get('action')}, ждали {'/'.join(exp['action'])}")
    if exp.get("recipe") and answer.get("recipe") != exp["recipe"]:
        problems.append(f"рецепт {answer.get('recipe')}, ждали {exp['recipe']}")
    lang = exp.get("lang")
    if lang == "uk" and (not re.search(r"[іїєґІЇЄҐ]", reply) or re.search(r"[ыэъЫЭЪ]", reply)):
        problems.append("ответ не по-украински")
    if lang == "ru" and re.search(r"[іїєґІЇЄҐ]", reply):
        problems.append("ответ не по-русски")
    for p in exp.get("must") or []:
        if not re.search(p, reply, re.I):
            problems.append(f"нет «{p}»")
    for p in exp.get("must_not") or []:
        if re.search(p, reply, re.I):
            problems.append(f"есть «{p}»")
    if exp.get("max_chars") and len(reply) > exp["max_chars"]:
        problems.append(f"{len(reply)} знаков, больше {exp['max_chars']}")
    return problems


def case_player(cfg, case):
    """Игрок случая: имя, «@owner» — первый владелец сервера, по умолчанию — обычный игрок Steve."""
    player = case.get("player", "Steve")
    if player == "@owner":
        return cfg["gm"]["owners"][0] if cfg["gm"]["owners"] else "Owner"
    return player


def case_text(cfg, case):
    player = case_player(cfg, case)
    lang = case.get("language") or conf.language(cfg, player)
    channel = {"gm": "лично /gm", "chat": "в общий чат"}.get(case.get("channel", "gm"))
    owner = conf.is_owner(cfg, player) or case.get("player") == "@owner"
    head = f"Игрок {player}" + (" (владелец)" if owner else "") + f", отвечать на его языке (обычно {lang})."
    lines = [f"id: {case['id']}", head]
    if case.get("context"):
        lines.append(case["context"])
    lines.append(f"[{channel}] {player}: {case['text']}" if channel else f"[итог задачи] {case['text']}")
    return "\n".join(lines)


def ask_voice(cfg, skill_dir, cases):
    """Ответы голоса (без инструментов) на случаи: id → ответ."""
    run_dir = os.path.join(cfg["paths"]["runs"], "eval")
    answers = {}
    for i in range(0, len(cases), BATCH):
        batch = cases[i:i + BATCH]
        argv, env = roles.prepare(cfg, "eval", run_dir, skill_dir)
        argv += ["--output-format", "json", "--json-schema", json.dumps(EVAL_SCHEMA)]
        prompt = EVAL_PROMPT + "\n\n" + "\n\n".join(case_text(cfg, c) for c in batch)
        r = subprocess.run(argv, cwd=run_dir, env=env, input=prompt, capture_output=True, text=True, timeout=600)
        out = _structured(r.stdout)
        for a in (out or {}).get("cases", []):
            answers[a.get("id")] = a
    return answers


def _structured(stdout):
    try:
        out = json.loads(stdout)
    except ValueError:
        return None
    if isinstance(out.get("structured_output"), dict):
        return out["structured_output"]
    try:
        return json.loads(out.get("result") or "")
    except ValueError:
        return None


def evaluate(cfg, store, skill_dir, overlay=None, cases=None, cache=True):
    """Итоги случаев на навыке: id → (прошёл, подробности); готовые итоги того же навыка — из памяти."""
    cases = cases if cases is not None else load_cases(cfg, skill_dir, overlay)
    key = roles.skill_hash(cfg, skill_dir) + _evals_hash(cfg, skill_dir, overlay)
    done = store.eval_results(key) if cache else {}
    todo = [c for c in cases if c["id"] not in done]
    if todo:
        answers = ask_voice(cfg, skill_dir, todo)
        fresh = {}
        for c in todo:
            problems = grade(c, answers.get(c["id"]))
            fresh[c["id"]] = (not problems, "; ".join(problems) or json.dumps(answers.get(c["id"]), ensure_ascii=False))
        store.eval_save(key, fresh)
        done.update(fresh)
    return {c["id"]: done[c["id"]] for c in cases}


def _evals_hash(cfg, skill_dir, overlay):
    h = hashlib.sha256()
    for f in _eval_files(cfg, skill_dir, overlay):
        h.update(_read(f).encode())
    return h.hexdigest()[:8]


# ---------------------------------------------------------------- рецензент

def review(cfg, wt, base):
    run_dir = os.path.join(cfg["paths"]["runs"], "review")
    argv, env = roles.prepare(cfg, "reviewer", run_dir, os.path.join(wt, "skill"), add_dirs=(wt,))
    argv += ["--output-format", "json", "--json-schema", json.dumps(REVIEW_SCHEMA)]
    diff = git(wt, "diff", "--cached", base)
    prompt = (f"Правка наставника в навыке ведущего (копия — {wt}). Дифф:\n\n```diff\n{diff[:60000]}\n```\n\n"
              "Проверь по своей роли и ответь JSON: verdict clean или block, reasons — причины "
              "(для block — что не так).")
    r = subprocess.run(argv, cwd=run_dir, env=env, input=prompt, capture_output=True, text=True, timeout=900)
    out = _structured(r.stdout)
    if not out:
        return False, [f"рецензент не ответил (код {r.returncode}): {r.stderr[-300:]}"]
    return out.get("verdict") == "clean", out.get("reasons") or []


# ---------------------------------------------------------------- ворота

def run(cfg, store, wt, base):
    """(годна ли правка, отчёт строкой). wt — копия с правкой в индексе, base — коммит до неё."""
    since = (store.logs(0, ["teach_start"], 1_000_000) or [{"id": 0}])[-1]["id"]
    errors = lint(cfg, store, wt, base, since)
    if errors:
        return False, "порядок: " + " | ".join(errors)
    cand_skill = os.path.join(wt, "skill")
    main_skill = os.path.join(cfg["paths"]["overlay"], "skill")
    cases = load_cases(cfg, cand_skill, wt)
    old = evaluate(cfg, store, main_skill, cfg["paths"]["overlay"],
                   [c for c in cases if c["id"] in {x["id"] for x in load_cases(cfg, main_skill)}])
    new = evaluate(cfg, store, cand_skill, wt, cases)
    worse = [cid for cid, (ok, _) in new.items() if not ok and old.get(cid, (False, ""))[0]]
    if worse:  # ещё одна попытка: модель отвечает не всегда одинаково
        again = evaluate(cfg, store, cand_skill, wt, [c for c in cases if c["id"] in worse], cache=False)
        worse = [cid for cid in worse if not again[cid][0]]
    fresh = [cid for cid in new if cid not in old and not new[cid][0]]
    passed = sum(ok for ok, _ in new.values())
    report = f"набор: {passed}/{len(new)} (было {sum(ok for ok, _ in old.values())}/{len(old)})"
    if worse or fresh:
        details = [f"{cid}: {new[cid][1]}" for cid in worse + fresh]
        return False, report + "; хуже: " + " | ".join(details)
    ok, reasons = review(cfg, wt, base)
    report += "; рецензент: " + ("clean" if ok else "block — " + " | ".join(reasons))
    return ok, report


def main():
    p = argparse.ArgumentParser(description="Ворота навыка ведущего")
    p.add_argument("--config")
    sub = p.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("evals", help="проверочный набор на навыке")
    e.add_argument("--skill", help="каталог навыка сервера (по умолчанию — skill/ оверлея)")
    a = p.parse_args()
    cfg = conf.load(a.config)
    store = storemod.Store(cfg["paths"]["db"])
    skill = a.skill or os.path.join(cfg["paths"]["overlay"], "skill")
    results = evaluate(cfg, store, skill, os.path.dirname(os.path.abspath(skill)))
    for cid, (ok, detail) in results.items():
        print(f"{'✓' if ok else '✗'} {cid}: {detail if not ok else ''}".rstrip())
    print(f"{sum(ok for ok, _ in results.values())}/{len(results)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
