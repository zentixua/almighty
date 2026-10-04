"""Рецепты ведущего — проверенные способы сделать дело: файл .md в skill/recipes (набора или сервера).

Рецепт — то, что уже получилось в мире, записанное так, чтобы получилось снова без импровизации:

    ---
    name: clear_weather
    description: Ясная погода (одна строка: когда брать)
    kind: instant
    params: {"seconds": {"type": "int", "min": 60, "max": 7200, "default": 1200}}
    resources: ["weather"]
    ---
    Пояснение: что делает, подводные камни, как понять, что вышло.

    ```steps
    [{"method": "command", "params": {"commands": ["weather clear ${seconds}"]}}]
    ```

    ```check
    return !server.overworld().isRaining()
    ```

name — как имя файла; kind: instant — делает голос сразу, task — исполнитель, show — долгое, правилом в игре;
resources — что занимает (две задачи с общим ресурсом не идут одновременно; ${имя} — текстом: "ship:${ship}").
Шапка — строки «ключ: значение», значение — JSON или строка. Шаги — вызовы моста (методы ALLOWED, слов игрокам
среди них нет), ${имя} — параметр:
строка целиком — значение своего типа, внутри строки — текстом. Каждый шаг перед вызовом проходит охрану
(guard.check_call). check — Groovy, который после шагов говорит, вышло ли (true или {ok: true, …}); у task и show
он обязателен: «готово» — по миру.

Длинный Groovy (скрипт, правило автопилота) — своим блоком с именем, а в шагах — строкой "${code:имя}" целиком:

    ```groovy autopilot
    def ship = gm.ship("${ship}")
    ```

    ```steps
    [{"method": "rule.add", "params": {"event": "ServerTickEvent.Post", "name": "ap_${ship}",
      "script": "${code:autopilot}"}}]
    ```

В Groovy (блоки и check) ${имя} объявленного параметра подставляется текстом, прочие ${…} — строки Groovy, их не
трогаем.
"""
import json
import math
import os
import re

ALLOWED = {"command", "function", "script", "rule.add", "rule.remove", "build", "undo", "bot.spawn", "bot.remove",
           "bot.act", "area.prepare", "area.release", "status", "player", "entities", "map", "look", "blocks", "see",
           "jobs", "job", "rules", "bots", "bot", "events", "ships"}
# значения шагов с кодом Groovy: параметры в них — как в блоке groovy
GROOVY_KEYS = ("code", "script")
KINDS = ("instant", "task", "show")
TYPES = {
    "int": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v),
    "bool": lambda v: isinstance(v, bool),
    "name": lambda v: isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9_]{1,16}", v) is not None,
    "block": lambda v: isinstance(v, str) and re.fullmatch(r"[a-z0-9_.:/-]+(\[[a-z0-9_=,]*\])?", v) is not None,
    # без кавычки, $ и \: текст встаёт и в строку Groovy, где ${…} — код
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
        self.resources = meta.get("resources") or []

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
        raise RecipeError(f"{os.path.basename(path)}: нет шапки --- … ---")
    meta = {}
    for line in m.group(1).splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition(":")
        if not sep:
            raise RecipeError(f"{os.path.basename(path)}: строка шапки без «:» — {line!r}")
        meta[key.strip()] = _value(value)
    body = m.group(2)
    steps_m = re.search(r"```steps\n(.*?)\n```", body, re.S)
    check_m = re.search(r"```check\n(.*?)\n```", body, re.S)
    try:
        steps = json.loads(steps_m.group(1)) if steps_m else None
    except ValueError as e:
        raise RecipeError(f"{os.path.basename(path)}: шаги — не JSON ({e})") from e
    code = {}
    for name, text in re.findall(r"```groovy[ \t]+(\w+)[ \t]*\n(.*?)\n```", body, re.S):
        if name in code:
            raise RecipeError(f"{os.path.basename(path)}: два блока groovy {name}")
        code[name] = text
    return Recipe(path, meta, body, steps, check_m.group(1) if check_m else None, code)


def validate(recipe):
    """Ошибки рецепта списком (пусто — годен)."""
    errors = []
    stem = os.path.splitext(os.path.basename(recipe.path))[0]
    if recipe.name != stem or not re.fullmatch(r"[a-z0-9_]+", recipe.name or "-"):
        errors.append(f"name {recipe.name!r} — латиница, цифры, _ и как имя файла ({stem})")
    if not recipe.description:
        errors.append("нет description")
    if recipe.kind not in KINDS:
        errors.append(f"kind — {', '.join(KINDS)}")
    if not isinstance(recipe.params, dict):
        errors.append("params — объект JSON")
    else:
        for k, p in recipe.params.items():
            t = p.get("type") if isinstance(p, dict) else p
            if t not in TYPES:
                errors.append(f"параметр {k}: тип {t!r} — один из {', '.join(TYPES)}")
    if not isinstance(recipe.resources, list) or not all(isinstance(x, str) and x.strip() for x in recipe.resources):
        errors.append("resources — список непустых строк")
    elif any(CODE.search(x) for x in recipe.resources):
        errors.append("${code:…} в resources нельзя: ресурс — имя места или корабля")
    if not isinstance(recipe.steps, list) or not recipe.steps:
        errors.append("нет шагов (```steps со списком JSON)")
    else:
        for i, s in enumerate(recipe.steps, 1):
            if not isinstance(s, dict) or not isinstance(s.get("params", {}), dict):
                errors.append(f"шаг {i}: {{\"method\", \"params\"}}")
            elif s.get("method") not in ALLOWED:
                errors.append(f"шаг {i}: метод {s.get('method')!r} нельзя (можно: {', '.join(sorted(ALLOWED))})")
    steps_text = json.dumps([recipe.steps, recipe.resources], ensure_ascii=False)
    for k in sorted(set(PARAM.findall(steps_text)) - set(recipe.params if isinstance(recipe.params, dict) else {})):
        errors.append(f"${{{k}}} не объявлен в params")
    refs = set(CODE.findall(json.dumps(recipe.steps, ensure_ascii=False)))
    for k in sorted(refs - set(recipe.code)):
        errors.append(f"${{code:{k}}}: нет блока ```groovy {k}")
    for k in sorted(set(recipe.code) - refs):
        errors.append(f"блок groovy {k} не взят ни одним шагом")
    if _code_inside(recipe.steps):
        errors.append("${code:…} — только целой строкой (значение script или code)")
    if recipe.kind in ("task", "show") and not recipe.check:
        errors.append("у task и show нужна проверка в мире (```check)")
    return errors


def load_all(dirs):
    """Рецепты по имени из каталогов по порядку (поздний перекрывает ранний); негодные — в errors."""
    found, errors = {}, {}
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".md"):
                continue
            path = os.path.join(d, f)
            try:
                r = parse(path)
                problems = validate(r)
            except RecipeError as e:
                errors[f[:-3]] = [str(e)]
                continue
            if problems:
                errors[f[:-3]] = problems
            else:
                found[r.name] = r
                errors.pop(r.name, None)
    return found, errors


def bind(recipe, given):
    """Значения параметров: данные + по умолчанию, с проверкой типа и границ."""
    given = dict(given or {})
    values = {}
    for k, p in recipe.params.items():
        spec = p if isinstance(p, dict) else {"type": p}
        if k in given:
            v = given.pop(k)
        elif "default" in spec:
            v = spec["default"]
        else:
            raise RecipeError(f"{recipe.name}: нет параметра {k}")
        if spec["type"] == "number" and isinstance(v, int) and not isinstance(v, bool):
            v = float(v)
        if not TYPES[spec["type"]](v):
            raise RecipeError(f"{recipe.name}: {k}={v!r} — не {spec['type']}")
        if "min" in spec and v < spec["min"] or "max" in spec and v > spec["max"]:
            raise RecipeError(f"{recipe.name}: {k}={v} вне [{spec.get('min')}, {spec.get('max')}]")
        values[k] = v
    if given:
        raise RecipeError(f"{recipe.name}: лишние параметры {', '.join(given)}")
    return values


def _text(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return repr(round(v, 6)).removesuffix(".0") if v == int(v) else repr(round(v, 6))
    return str(v)


def resources(recipe, values):
    """Ресурсы задачи по рецепту: ${имя} — текстом ("ship:${ship}" → "ship:Grand")."""
    return [PARAM.sub(lambda m: _text(values[m.group(1)]), r) for r in recipe.resources]


def substitute(obj, values, code=None):
    """Параметры в шагах; "${code:имя}" — текст блока groovy с подставленными параметрами."""
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
    """Groovy рецепта: ${имя} объявленного параметра — текстом, прочие ${…} — строки Groovy, как есть."""
    def put(m):
        if m.group(1) not in values:
            return m.group(0)
        v = values[m.group(1)]
        # из строки Groovy в одинарных кавычках текст не выйдет: кавычки в нём — не в код
        if isinstance(v, str) and "'" in v:
            raise RecipeError(f"{m.group(1)}: текст с ' не подставляется в Groovy")
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
    """Текст ошибки, если ответ моста говорит о неудаче (команда не прошла, скрипт упал), иначе None."""
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


def run(recipe, given, bridge, ctx, check_call):
    """Выполнить рецепт: охрана и вызов каждого шага, потом проверка. Ответ — {ok, steps, check}."""
    values = bind(recipe, given)
    steps = substitute(recipe.steps, values, recipe.code)
    done = []
    for i, step in enumerate(steps, 1):
        reason = check_call(step["method"], step.get("params") or {}, ctx)
        if reason:
            raise RecipeError(f"{recipe.name}, шаг {i}: охрана — {reason}")
        result = bridge.call(step["method"], step.get("params") or {})
        failed = _failed(result)
        done.append({"method": step["method"], "result": _short(result)})
        if failed:
            return {"ok": False, "steps": done, "error": f"шаг {i}: {failed}"}
    if recipe.check:
        code = groovy(recipe.check, values)
        reason = check_call("script", {"code": code}, ctx)
        if reason:
            raise RecipeError(f"{recipe.name}, проверка: охрана — {reason}")
        check = bridge.call("script", {"code": code, "args": values, "timeout_ms": 5000})
        ok = check.get("ok") and (check.get("value") is True
                                  or isinstance(check.get("value"), dict) and check["value"].get("ok") is True)
        return {"ok": bool(ok), "steps": done, "check": _short(check)}
    return {"ok": True, "steps": done}


def _short(result, limit=1500):
    text = json.dumps(result, ensure_ascii=False)
    return result if len(text) <= limit else text[:limit] + "…"
