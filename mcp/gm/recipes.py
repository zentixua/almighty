#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "httpx>=0.27",
# ]
# ///
"""GM recipes: proven ways to do a task, as .md files in the server's recipe directory.

  uv run --script mcp/gm/recipes.py list <directory>                 - recipes (including invalid ones with errors)
  uv run --script mcp/gm/recipes.py run <directory> <recipe> ['{...}'] - run; response is JSON {ok, steps, check}

The same bridge as mcp/almighty.py: ALMIGHTY_URL and ALMIGHTY_TOKEN_FILE (use the test server's address and token).

A recipe records something that already worked in the world so it can work again without improvisation:

    ---
    name: clear_weather
    description: Clear weather (one line: when to use it)
    kind: instant
    params: {"seconds": {"type": "int", "min": 60, "max": 7200, "default": 1200}}
    ---
    Explanation: what it does, pitfalls, how to tell it worked.

    ```steps
    [{"method": "command", "params": {"commands": ["weather clear ${seconds}"]}}]
    ```

    ```check
    return !server.overworld().isRaining()
    ```

name matches the file name; kind: instant takes seconds, task takes longer (the GM runs it in the background),
show leaves a rule running in the game. Header lines are "key: value", with a JSON or string value. Steps call
the bridge (ALLOWED methods, excluding speech to players); ${name} is a parameter: a whole string becomes its
typed value, inside a string it becomes text. Before each call, each step passes through the crash guard
(crash_guard.check_call, like a subagent call: recipes do not write to players); a recipe does not perform
anything the hook would deny or refer to the owner for approval. check is Groovy that reports success after
the steps (true or {ok: true, ...}); task and show require it: "done" is determined by the world.

Long Groovy (a script, an autopilot rule) goes in a named block, referenced in steps by the whole string "${code:name}":

    ```groovy autopilot
    def ship = gm.ship("${ship}")
    ```

    ```steps
    [{"method": "rule.add", "params": {"event": "ServerTickEvent.Post", "name": "ap_${ship}",
      "script": "${code:autopilot}"}}]
    ```

In Groovy (blocks and check), ${name} for a declared parameter is substituted as text; other ${...} expressions
are Groovy strings and remain unchanged.
"""
import argparse
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import crash_guard  # noqa: E402

ALLOWED = {"command", "function", "script", "rule.add", "rule.remove", "build", "undo", "bot.spawn", "bot.remove",
           "bot.act", "area.prepare", "area.release", "status", "player", "entities", "map", "look", "blocks", "see",
           "jobs", "job", "rules", "bots", "bot", "events", "ships"}
# Groovy code values in steps: parameters work as in a groovy block.
GROOVY_KEYS = ("code", "script")
KINDS = ("instant", "task", "show")
TYPES = {
    "int": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v),
    "bool": lambda v: isinstance(v, bool),
    "name": lambda v: isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", v) is not None,
    "block": lambda v: isinstance(v, str) and re.fullmatch(r"[a-z0-9_.:/-]+(\[[a-z0-9_=,]*\])?", v) is not None,
    # No quote, $ or backslash: text can appear inside a Groovy string, where ${...} is code.
    "text": lambda v: isinstance(v, str) and len(v) <= 200 and not set(v) & set("\n\"$\\"),
}
PARAM = re.compile(r"\$\{(\w+)\}")
CODE = re.compile(r"\$\{code:(\w+)\}")


class RecipeError(Exception):
    pass


class Recipe:
    def __init__(self, path, meta, body, steps, check, code=None):
        self.path, self.meta, self.body, self.steps, self.check = path, meta, body, steps, check
        self.code = code or {}
        self.name = meta.get("name", "")
        self.kind = meta.get("kind", "")
        self.description = meta.get("description", "")
        self.params = meta.get("params") or {}

    def summary(self):
        params = ", ".join(f"{k}: {p['type'] if isinstance(p, dict) else p}" for k, p in self.params.items())
        return f"{self.name} [{self.kind}] ({params}) — {self.description}"


def _value(text):
    text = text.strip()
    if text[:1] in "[{\"-0123456789" or text in ("true", "false", "null"):
        try:
            return json.loads(text)
        except ValueError:
            pass
    return text


def parse(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.S)
    if not m:
        raise RecipeError(f"{os.path.basename(path)}: no header --- ... ---")
    meta = {}
    for line in m.group(1).splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition(":")
        if not sep:
            raise RecipeError(f"{os.path.basename(path)}: header line without ':': {line!r}")
        meta[key.strip()] = _value(value)
    body = m.group(2)
    steps_m = re.search(r"```steps\n(.*?)\n```", body, re.S)
    check_m = re.search(r"```check\n(.*?)\n```", body, re.S)
    try:
        steps = json.loads(steps_m.group(1)) if steps_m else None
    except ValueError as e:
        raise RecipeError(f"{os.path.basename(path)}: steps are not JSON ({e})") from e
    code = {}
    for name, text in re.findall(r"```groovy[ \t]+(\w+)[ \t]*\n(.*?)\n```", body, re.S):
        if name in code:
            raise RecipeError(f"{os.path.basename(path)}: two groovy blocks named {name}")
        code[name] = text
    return Recipe(path, meta, body, steps, check_m.group(1) if check_m else None, code)


def validate(recipe):
    """Recipe errors as a list (empty means valid)."""
    errors = []
    stem = os.path.splitext(os.path.basename(recipe.path))[0]
    if recipe.name != stem or not re.fullmatch(r"[a-z0-9_]+", recipe.name or "-"):
        errors.append(f"name {recipe.name!r}: a-z, 0-9 and _, the same as the file name ({stem})")
    if not recipe.description:
        errors.append("no description")
    if recipe.kind not in KINDS:
        errors.append(f"kind is one of {', '.join(KINDS)}")
    if not isinstance(recipe.params, dict):
        errors.append("params must be a JSON object")
    else:
        for k, p in recipe.params.items():
            t = p.get("type") if isinstance(p, dict) else p
            if t not in TYPES:
                errors.append(f"parameter {k}: type {t!r} is not one of {', '.join(TYPES)}")
    if not isinstance(recipe.steps, list) or not recipe.steps:
        errors.append("no steps (```steps with a JSON list)")
    else:
        for i, s in enumerate(recipe.steps, 1):
            if not isinstance(s, dict) or not isinstance(s.get("params", {}), dict):
                errors.append(f"step {i}: expected {{\"method\", \"params\"}}")
            elif s.get("method") not in ALLOWED:
                errors.append(f"step {i}: method {s.get('method')!r} is not allowed (allowed: {', '.join(sorted(ALLOWED))})")
    steps_text = json.dumps(recipe.steps, ensure_ascii=False)
    for k in sorted(set(PARAM.findall(steps_text)) - set(recipe.params if isinstance(recipe.params, dict) else {})):
        errors.append(f"${{{k}}} is not declared in params")
    refs = set(CODE.findall(json.dumps(recipe.steps, ensure_ascii=False)))
    for k in sorted(refs - set(recipe.code)):
        errors.append(f"${{code:{k}}}: no ```groovy {k} block")
    for k in sorted(set(recipe.code) - refs):
        errors.append(f"groovy block {k} is not used by any step")
    if _code_inside(recipe.steps):
        errors.append("${code:...} only as a whole string (the value of script or code)")
    if recipe.kind in ("task", "show") and not recipe.check:
        errors.append("task and show need a check in the world (```check)")
    return errors


def load_all(directory):
    """Directory recipes by name; invalid entries go in errors."""
    found, errors = {}, {}
    for f in sorted(os.listdir(directory)):
        if not f.endswith(".md"):
            continue
        try:
            r = parse(os.path.join(directory, f))
            problems = validate(r)
        except RecipeError as e:
            errors[f[:-3]] = [str(e)]
            continue
        if problems:
            errors[f[:-3]] = problems
        else:
            found[r.name] = r
    return found, errors


def bind(recipe, given):
    """Parameter values: supplied data plus defaults, with type and bound checks."""
    given = dict(given or {})
    values = {}
    for k, p in recipe.params.items():
        spec = p if isinstance(p, dict) else {"type": p}
        if k in given:
            v = given.pop(k)
        elif "default" in spec:
            v = spec["default"]
        else:
            raise RecipeError(f"{recipe.name}: missing parameter {k}")
        if spec["type"] == "number" and isinstance(v, int) and not isinstance(v, bool):
            v = float(v)
        if not TYPES[spec["type"]](v):
            raise RecipeError(f"{recipe.name}: {k}={v!r} is not {spec['type']}")
        if "min" in spec and v < spec["min"] or "max" in spec and v > spec["max"]:
            raise RecipeError(f"{recipe.name}: {k}={v} is outside [{spec.get('min')}, {spec.get('max')}]")
        values[k] = v
    if given:
        raise RecipeError(f"{recipe.name}: unknown parameters {', '.join(given)}")
    return values


def _text(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return repr(round(v, 6)).removesuffix(".0") if v == int(v) else repr(round(v, 6))
    return str(v)


def substitute(obj, values, code=None):
    """Parameters in steps; "${code:name}" is a groovy block's text with substituted parameters."""
    if isinstance(obj, str):
        ref = CODE.fullmatch(obj)
        if ref and code is not None:
            return groovy(code[ref.group(1)], values)
        whole = PARAM.fullmatch(obj)
        if whole:
            return values[whole.group(1)]
        return PARAM.sub(lambda m: _text(values[m.group(1)]), obj)
    if isinstance(obj, list):
        return [substitute(v, values, code) for v in obj]
    if isinstance(obj, dict):
        return {k: groovy(v, values) if k in GROOVY_KEYS and isinstance(v, str) and not CODE.fullmatch(v)
                else substitute(v, values, code) for k, v in obj.items()}
    return obj


def groovy(text, values):
    """Recipe Groovy: substitute declared ${name} parameters as text; leave other ${...} Groovy strings unchanged."""
    def put(m):
        if m.group(1) not in values:
            return m.group(0)
        v = values[m.group(1)]
        # Text cannot escape a single-quoted Groovy string: its quotes stay data, not code.
        if isinstance(v, str) and "'" in v:
            raise RecipeError(f"{m.group(1)}: text with ' cannot be put into Groovy")
        return _text(v)
    return PARAM.sub(put, text)


def _code_inside(obj):
    if isinstance(obj, str):
        return CODE.search(obj) is not None and CODE.fullmatch(obj) is None
    if isinstance(obj, list):
        return any(_code_inside(v) for v in obj)
    if isinstance(obj, dict):
        return any(_code_inside(v) for v in obj.values())
    return False


def _failed(result):
    """Error text if the bridge response reports failure (command failed, script error), otherwise None."""
    if isinstance(result, dict):
        if result.get("ok") is False:
            return result.get("error") or result.get("errors") or json.dumps(result, ensure_ascii=False)[:300]
        for k in ("results", "commands"):
            if isinstance(result.get(k), list):
                return _failed(result[k])
    if isinstance(result, list):
        for r in result:
            if isinstance(r, dict) and r.get("ok") is False:
                return f"{r.get('command', '')}: {r.get('errors') or r.get('output') or r.get('error')}"
    return None


def guard(method, params):
    """Crash guard rejection reason or None: recipes act as subagents (no player speech) and never await approval."""
    found = crash_guard.check_call(method, params, subagent=True)
    return found[1] if found else None


def run(recipe, given, call):
    """Run a recipe: guard and bridge call(method, params) for each step, then check. Response: {ok, steps, check}."""
    values = bind(recipe, given)
    steps = substitute(recipe.steps, values, recipe.code)
    done = []
    for i, step in enumerate(steps, 1):
        reason = guard(step["method"], step.get("params") or {})
        if reason:
            raise RecipeError(f"{recipe.name}, step {i}: refused by the hook: {reason}")
        result = call(step["method"], step.get("params") or {})
        failed = _failed(result)
        done.append({"method": step["method"], "result": _short(result)})
        if failed:
            return {"ok": False, "steps": done, "error": f"step {i}: {failed}"}
    if recipe.check:
        code = groovy(recipe.check, values)
        reason = guard("script", {"code": code})
        if reason:
            raise RecipeError(f"{recipe.name}, check: refused by the hook: {reason}")
        check = call("script", {"code": code, "args": values, "timeout_ms": 5000})
        ok = check.get("ok") and (check.get("value") is True
                                  or isinstance(check.get("value"), dict) and check["value"].get("ok") is True)
        return {"ok": bool(ok), "steps": done, "check": _short(check)}
    return {"ok": True, "steps": done}


def _short(result, limit=1500):
    text = json.dumps(result, ensure_ascii=False)
    return result if len(text) <= limit else text[:limit] + "…"


def main():
    p = argparse.ArgumentParser(description="Game master recipes: list and run")
    sub = p.add_subparsers(dest="cmd", required=True)
    ls = sub.add_parser("list", help="recipes in the directory (invalid ones with their errors)")
    ls.add_argument("dir")
    rn = sub.add_parser("run", help="run a recipe")
    rn.add_argument("dir")
    rn.add_argument("name")
    rn.add_argument("params", nargs="?", default="{}", help="parameters as JSON")
    a = p.parse_args()
    found, errors = load_all(a.dir)
    if a.cmd == "list":
        for r in found.values():
            print(r.summary())
        for name, problems in errors.items():
            print(f"INVALID {name}: {'; '.join(problems)}")
        return 0
    if a.name not in found:
        print(f"no recipe {a.name}" + (f": {'; '.join(errors[a.name])}" if a.name in errors else ""), file=sys.stderr)
        return 1
    # The bridge is the mcp/almighty.py adapter call, using its ALMIGHTY_URL and ALMIGHTY_TOKEN_FILE.
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    import almighty
    try:
        result = run(found[a.name], json.loads(a.params), almighty.rpc)
    except (RecipeError, almighty.GmError, ValueError) as e:
        result = {"ok": False, "error": str(e)}
    print(json.dumps(result, ensure_ascii=False, indent=1))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
