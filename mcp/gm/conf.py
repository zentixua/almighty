"""Настройки ведущего: config.toml в каталоге сервера (оверлей) поверх значений по умолчанию.

Оверлей — каталог с config.toml, своим git (знания сервера, которые копит наставник, плагины сервера) и тем, что в
git не идёт: gm.db (очередь, опыт), runs/ (файлы запусков сессий). Набор (этот каталог) — общий для всех серверов.
"""
import copy
import os
import tomllib

KIT = os.path.dirname(os.path.abspath(__file__))

DEFAULTS = {
    "bridge": {"url": "http://127.0.0.1:25650", "token_file": ""},
    # тестовый сервер: на нём наставник пробует рецепты; без него рецепты не учатся
    "test_bridge": {"url": "", "token_file": ""},
    "gm": {
        # слова в общем чате, на которые ведущий отвечает (личное /gm — всегда)
        "names": ["ведущий", "ведущая", "ведущему", "ведущего"],  # «гм» — междометие, «gm» — «good morning»
        "owners": [],
        "languages": {},
        "default_language": "ru",
        # боты (bot.spawn), которых ведущий слушает как игроков: репетиция ботами; прочих ботов он не слышит
        "bot_players": [],
        "claude": "claude",
        "uv": "uv",
        # плагины сервера (plugins.py): файлы Python, пути от каталога сервера
        "plugins": [],
    },
    "models": {
        "voice": "haiku", "voice_effort": "low",
        "worker": "sonnet", "worker_effort": "medium",
        "teacher": "sonnet", "teacher_effort": "high",
        "reviewer": "sonnet", "reviewer_effort": "medium",
        "fallback": "sonnet",
    },
    "limits": {
        "workers": 2,
        "voice_turns": 40,          # перезапуск голоса в паузе: контекст не растёт без конца
        "voice_turn_seconds": 120,  # ход голоса дольше — голос перезапускается, игроку — что случилось
        "task_minutes": 20,
        "task_usd": 3.0,
        "teacher_gap_minutes": 10,  # не чаще; учится, только когда есть новый опыт
        "teacher_minutes": 30,
        "teacher_usd": 5.0,
        "core_lines": 150,          # CORE.md наставника — в каждом ходе голоса, поэтому коротко
    },
    "guard": {
        # добавки сервера к запретам набора (guard.NEVER, guard.OWNER_ONLY, guard.SCRIPT_NEVER): убрать их нельзя
        "never": [],         # команды, которых ведущий не даёт никогда
        "owner_only": [],    # команды только по слову владельца (тот, чей запрос сейчас выполняется, — из owners)
        "script_never": [],  # в коде скриптов и правил
    },
}


# ключи, которые ушли из набора в плагины сервера: старый config.toml не должен думать, что они ещё проверяются
MOVED = ("strike", "margin", "plot_x")


class ConfigError(Exception):
    pass


def _merge(base, over):
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _merge(base[k], v)
        else:
            base[k] = v
    return base


def load(path=None):
    """Настройки из config.toml (GM_CONFIG); пути оверлея — в ключе paths."""
    path = os.path.abspath(os.path.expanduser(path or os.environ.get("GM_CONFIG", "config.toml")))
    with open(path, "rb") as f:
        raw = tomllib.load(f)
    for key in MOVED:
        if key in (raw.get("guard") or {}):
            raise ConfigError(f"{path}: guard.{key} перенесено в плагин сервера (README, «Плагины сервера»)")
    cfg = _merge(copy.deepcopy(DEFAULTS), raw)
    for section, key in (("gm", "plugins"), ("guard", "never"), ("guard", "owner_only"), ("guard", "script_never")):
        if not isinstance(cfg[section][key], list) or not all(isinstance(x, str) for x in cfg[section][key]):
            raise ConfigError(f"{path}: {section}.{key} — список строк")
    overlay = os.path.dirname(path)
    cfg["paths"] = {
        "config": path,
        "overlay": overlay,
        "kit": KIT,
        "adapter": os.path.join(os.path.dirname(KIT), "almighty.py"),
        "db": os.path.join(overlay, "gm.db"),
        "runs": os.path.join(overlay, "runs"),
    }
    for section in ("bridge", "test_bridge"):
        if cfg[section]["token_file"]:
            cfg[section]["token_file"] = os.path.expanduser(cfg[section]["token_file"])
    return cfg


def language(cfg, player):
    return cfg["gm"]["languages"].get(player, cfg["gm"]["default_language"])


def is_owner(cfg, player):
    return player in cfg["gm"]["owners"]
