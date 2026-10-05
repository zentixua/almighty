"""Плагины сервера: свои проверки, инструменты и работа диспетчера из каталога сервера — поверх набора.

В config.toml сервера: [gm] plugins = ["plugins/my_checks.py", …] — пути от каталога сервера. Плагин — обычный
модуль Python (грузится стандартно: importlib.util.spec_from_file_location, один раз на процесс, имя модуля —
gm_plugin_<имя файла>) с любыми из функций:

  check_command(seg, ctx)            → причина отказа или None: каждая часть команды (execute … run …), откуда бы
                                       она ни пришла — command, строки функции и макросы, команды-литералы скриптов
                                       и правил, чат бота, шаги рецептов
  check_call(method, params, ctx)    → причина отказа или None: вызов моста целиком (метод и параметры моста, как
                                       их шлёт адаптер, без его значений по умолчанию: guard.tool_call), после проверок набора
  after_call(store, method, params, ctx) — вызов на живом мосту разрешён: сразу после проверки, до выполнения (хук
                                       PreToolUse, шаг рецепта); мост ещё может его отклонить. У наставника мост
                                       тестовый — нет
  tools(gm, safe)                    — свои инструменты MCP gm: @safe def имя(...) → mcp__gm__имя; хук их не видит:
                                       инструмент, который сам зовёт мост, проверяет вызов guard.checker(gm.store)
                                       с gm.ctx()
  tick(host)                         — каждый круг диспетчера (5 раз в секунду): host.cfg, host.store, host.bridge,
                                       host.notify(player, text, task=None) — весть голосу; упал — следующий раз
                                       через TICK_RETRY секунд

Сначала проверки набора, потом плагины по порядку списка; решает первый отказ — ослабить проверку набора плагин не
может. Плагин не загрузился (нет файла, ошибка в коде) или его проверка упала — PluginError: охрана отказывает во
всём, диспетчер не стартует. Модули набора (guard и др.) плагин импортирует внутри своих функций.
"""
import importlib.util
import os
import re
import sys
import time

TICK_RETRY = 60  # tick плагина упал (мост лёг, своя ошибка) — снова через минуту, а не каждый круг
_loaded = {}  # путь файла → модуль
_tick_after = {}  # имя модуля → когда снова звать его tick


class PluginError(Exception):
    """Плагин не загрузился, его проверка упала или его инструмент отказал (safe пишет это как tool_error)."""


def paths(cfg):
    """Файлы плагинов сервера по порядку списка: пути от каталога сервера."""
    return [os.path.normpath(os.path.join(cfg["paths"]["overlay"], os.path.expanduser(p)))
            for p in cfg["gm"]["plugins"]]


def load(cfg):
    """Модули плагинов сервера по порядку; каждый файл грузится один раз на процесс."""
    mods, names = [], {}
    for path in paths(cfg):
        name = "gm_plugin_" + re.sub(r"\W", "_", os.path.splitext(os.path.basename(path))[0])
        if names.setdefault(name, path) != path:
            raise PluginError(f"плагины {names[name]} и {path}: одно имя файла — переименовать один")
        if path not in _loaded:
            _loaded[path] = _import(name, path)
        mods.append(_loaded[path])
    return mods


def _import(name, path):
    try:
        spec = importlib.util.spec_from_file_location(name, path)
        if spec is None or spec.loader is None:
            raise ImportError("не модуль Python (.py)")
        mod = importlib.util.module_from_spec(spec)
        sys.modules[name] = mod
        spec.loader.exec_module(mod)
        return mod
    except Exception as e:  # noqa: BLE001 — любая ошибка плагина — отказ, а не пропуск
        sys.modules.pop(name, None)
        raise PluginError(f"плагин {path} не загрузился: {type(e).__name__}: {e}") from e


def names(cfg):
    """Имена файлов плагинов — для журнала диспетчера."""
    return [os.path.basename(p) for p in paths(cfg)]


def _hooks(cfg, hook):
    return [(mod, getattr(mod, hook)) for mod in load(cfg) if callable(getattr(mod, hook, None))]


def _call(mod, fn, *args):
    try:
        return fn(*args)
    except PluginError:
        raise
    except Exception as e:  # noqa: BLE001
        raise PluginError(f"плагин {mod.__name__}.{fn.__name__}: {type(e).__name__}: {e}") from e


def check_command(seg, ctx):
    for mod, fn in _hooks(ctx.cfg, "check_command"):
        reason = _call(mod, fn, seg, ctx)
        if reason:
            return str(reason)  # причина отказа уходит в JSON хука строкой
    return None


def check_call(method, params, ctx):
    for mod, fn in _hooks(ctx.cfg, "check_call"):
        reason = _call(mod, fn, method, params, ctx)
        if reason:
            return str(reason)
    return None


def after_call(store, method, params, ctx):
    for mod, fn in _hooks(ctx.cfg, "after_call"):
        _call(mod, fn, store, method, params, ctx)


def register_tools(gm, safe):
    """Инструменты плагинов — после инструментов набора: имя набора плагин не перекроет."""
    for mod, fn in _hooks(gm.cfg, "tools"):
        _call(mod, fn, gm, safe)


def tick(host, now=None):
    """Круг диспетчера: tick каждого плагина; ошибка одного не мешает другим и приходит потом одной PluginError,
    упавший плагин ждёт TICK_RETRY секунд."""
    now = time.time() if now is None else now
    errors = []
    for mod, fn in _hooks(host.cfg, "tick"):
        if now < _tick_after.get(mod.__name__, 0):
            continue
        try:
            _call(mod, fn, host)
        except PluginError as e:
            _tick_after[mod.__name__] = now + TICK_RETRY
            errors.append(str(e))
    if errors:
        raise PluginError("; ".join(errors))
