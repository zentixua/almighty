"""Настройки ведущего: config.toml в каталоге сервера (оверлей) поверх значений по умолчанию.

Оверлей — каталог с config.toml, своим git (знания сервера, которые копит наставник) и тем, что в git не идёт:
gm.db (очередь, опыт), zones.json, runs/ (файлы запусков сессий). Набор (этот каталог) — общий для всех серверов.
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
        "claude": "claude",
        "uv": "uv",
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
        # команды только по слову владельца (тот, чей запрос сейчас выполняется, — из owners)
        "owner_only": [r"^(op|deop|whitelist|ban|ban-ip|pardon|pardon-ip|kick)\b"],
        # команды, которых ведущий не даёт никогда
        "never": [r"^(stop|reload|save-off|save-all\s+flush)\b", r"^forceload\s+add\b",
                  r"^summon\s+(minecraft:)?(fireball|small_fireball|dragon_fireball|wither_skull)\b"],
        # в коде скриптов и правил
        "script_never": [],
        # удары: команда, которая что-то роняет или взрывает, — цель только числами, путь и цель сверяются с зонами
        "strike": [],
        "margin": 64,
        # участки кораблей Sable — от x 20 480 000; постройки и загрузка там запрещены
        "plot_x": 20_000_000,
    },
}


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
        cfg = _merge(copy.deepcopy(DEFAULTS), tomllib.load(f))
    overlay = os.path.dirname(path)
    cfg["paths"] = {
        "config": path,
        "overlay": overlay,
        "kit": KIT,
        "adapter": os.path.join(os.path.dirname(KIT), "almighty.py"),
        "db": os.path.join(overlay, "gm.db"),
        "zones": os.environ.get("GM_ZONES") or os.path.join(overlay, "zones.json"),  # у наставника — копия
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
