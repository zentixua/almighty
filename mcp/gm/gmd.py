#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# ///
"""Диспетчер ведущего — служба без ИИ: слушает игру, будит голос, ведёт очередь исполнителей, учит наставника.

  uv run mcp/gm/gmd.py --config ~/airstrike-server/gm/config.toml

- Лента моста: личное /gm и сообщения в чате, где ведущего назвали (gm.names), — голосу. Кто только что говорил с
  ведущим, тому имя повторять не нужно 90 с. Сообщения одного игрока подряд — одним ходом; разные игроки — по
  очереди (охрана знает, чей запрос выполняется).
- Голос — один живой процесс claude -p (stream-json): ход — сообщение игрока с тем, что голосу нужно знать сразу
  (его задачи, недавний чат). Перезапуск в паузе — после voice_turns ходов и после правки навыка.
- Задачи — исполнители: свежий claude -p на задачу, не больше limits.workers сразу, ресурсы не пересекаются, срок,
  отмена. Итог задачи (с доказательством из мира) — голосу, он скажет игроку.
- Наставник — после нового опыта (не чаще teacher_gap_minutes): правит знания сервера в отдельной копии (git
  worktree), правка проходит ворота (gate.py) и только тогда входит в навык.
Всё — в журнал gm.db: время ответа, цена, отказы охраны, итоги — сырьё наставника и цифры для людей.
"""
import argparse
import collections
import functools
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bridge as bridgemod  # noqa: E402
import conf  # noqa: E402
import guard  # noqa: E402
import roles  # noqa: E402
import zones as zonesmod  # noqa: E402
import store as storemod  # noqa: E402

CONVERSATION_SECONDS = 90
FALLBACK = {"ru": "Ведущий сейчас не смог ответить — повтори, пожалуйста, через минуту.",
            "uk": "Ведучий зараз не зміг відповісти — повтори, будь ласка, за хвилину.",
            "en": "The game master couldn't answer just now — please try again in a minute."}
# что будит наставника сразу; прочие события (сообщения, ответы) — когда их накопилось TEACH_AFTER
SIGNALS = ("lesson", "task_failed", "no_reply", "guard_deny", "tool_error", "recipe_error", "voice_timeout",
           "voice_crash", "slow_reply", "rule_removed")
TEACH_AFTER = 8
RULES_RECHECK = 60  # правила с ударом сверяются с зонами при каждой их правке и не реже раза в минуту
STALE_SECONDS = 20  # голос не поднялся — сообщения старше этого получают запасную фразу и не копятся
EXPERIENCE_ROWS = 300  # записей журнала на один урок (столько отдаёт experience)


def log(text):
    print(time.strftime("%H:%M:%S ") + text, flush=True)


def addressed(text, names):
    low = text.lower()
    return any(re.search(r"(?<![\w])" + re.escape(n.lower()) + r"(?![\w])", low) for n in names)


class Item:
    """Что ждёт голоса: сообщение игрока, итог задачи для него или весть службы (note)."""

    def __init__(self, player, text, channel=None, task=None, pos=None, direct=False, note=False):
        self.player, self.text, self.channel, self.task, self.pos = player, text, channel, task, pos
        self.note = note
        self.direct = direct  # личное /gm или ведущего назвали; без ответа — ошибка голоса
        self.time = time.time()


def compose(cfg, player, items, tasks, chat):
    """Текст хода голоса: сообщения игрока подряд, итоги его задач, его открытые задачи, недавний чат."""
    lang = conf.language(cfg, player)
    lines = []
    for it in items:
        stamp = time.strftime("%H:%M", time.localtime(it.time))
        if it.note:
            lines.append(f"[{stamp} · служба ведущего] {it.text}")
        elif it.task is None:
            where = "лично /gm" if it.channel == "gm" else "в общий чат"
            lines.append(f"[{stamp} · {where}] {player}: {it.text}")
        else:
            lines.append(f"[{stamp} · итог задачи #{it.task}] {it.text}")
    head = (f"Игрок {player}" + (" (владелец)" if conf.is_owner(cfg, player) else "")
            + f", отвечать на его языке (обычно {lang}).")
    pos = next((it.pos for it in reversed(items) if it.pos), None)
    if pos:
        head += f" Где он: {pos}."
    out = [head, *lines]
    open_tasks = [t for t in tasks if t["status"] in storemod.OPEN]
    if open_tasks:
        out.append("Его открытые задачи: " + "; ".join(
            f"#{t['id']} «{t['text'][:80]}» — {t['status']}" + (f" ({t['progress']})" if t["progress"] else "")
            for t in open_tasks))
    others = [c for c in chat if c["player"] != player][-6:]
    if others:
        out.append("Недавно в чате: " + " | ".join(f"{c['player']}: {c['text'][:120]}" for c in others))
    return "\n".join(out)


def notice(task):
    """Итог задачи словами для голоса."""
    if task["status"] == "done":
        ev = json.dumps((task["evidence"] or {}).get("result"), ensure_ascii=False)[:700]
        return (f"Задача «{task['text'][:120]}» готова: {task['summary']}\nПроверка в мире: {ev}\n"
                "Скажи игроку коротко, что готово и где/как пользоваться.")
    return (f"Задача «{task['text'][:120]}» не вышла: {task['summary']}\n"
            "Скажи игроку честно, одной-двумя фразами, и что можно сделать иначе.")


def pick(pending):
    """Чей ход: игрок самого раннего ожидания; его элементы — одним ходом. Ответ — (игрок, элементы, остаток)."""
    if not pending:
        return None, [], pending
    player = pending[0].player
    mine = [it for it in pending if it.player == player]
    return player, mine, [it for it in pending if it.player != player]


def runnable(queued, running, slots):
    """Какие задачи из очереди можно начать: по порядку, пока есть места и ресурсы не заняты."""
    busy = {r for t in running for r in t["resources"]}
    start = []
    for t in queued:
        if len(running) + len(start) >= slots:
            break
        if busy & set(t["resources"]):
            continue
        start.append(t)
        busy |= set(t["resources"])
    return start


def spawn(argv, run_dir, cwd, env, prompt):
    """Разовая сессия: задание — на stdin (prompt.txt), ответ — out.json, ошибки — stderr.log в каталоге запуска."""
    with open(os.path.join(run_dir, "prompt.txt"), "w", encoding="utf-8") as f:
        f.write(prompt)
    path = functools.partial(os.path.join, run_dir)
    with open(path("prompt.txt"), "rb") as stdin, open(path("out.json"), "wb") as out, \
            open(path("stderr.log"), "wb") as err:
        return subprocess.Popen(argv, cwd=cwd, env=env, stdin=stdin, stdout=out, stderr=err)


class Voice:
    """Голос: живой процесс claude -p со stream-json; ход — сообщение пользователя, конец хода — result."""

    def __init__(self, cfg, skill_dir):
        self.cfg, self.skill_dir = cfg, skill_dir
        self.proc = None
        self.results = queue.Queue()
        self.turns = 0
        self.skill = None

    def start(self):
        run_dir = os.path.join(self.cfg["paths"]["runs"], "voice")
        argv, env = roles.prepare(self.cfg, "voice", run_dir, self.skill_dir)
        argv += ["--input-format", "stream-json", "--output-format", "stream-json", "--verbose"]
        with open(os.path.join(run_dir, "stderr.log"), "a", encoding="utf-8") as err:
            self.proc = subprocess.Popen(argv, cwd=run_dir, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                         stderr=err, text=True, bufsize=1, encoding="utf-8")
        self.results = queue.Queue()
        threading.Thread(target=self._read, args=(self.proc, self.results), daemon=True).start()
        self.turns = 0
        self.skill = roles.skill_hash(self.cfg, self.skill_dir)
        log(f"голос запущен (навык {self.skill})")

    @staticmethod
    def _read(proc, results):
        for line in proc.stdout:
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if msg.get("type") == "result":
                results.put(msg)
        proc.stdout.close()
        results.put(None)  # процесс кончился

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def send(self, text):
        self.proc.stdin.write(json.dumps({"type": "user", "message": {"role": "user", "content": text}},
                                         ensure_ascii=False) + "\n")
        self.proc.stdin.flush()

    def stop(self):
        if self.proc is None:
            return
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
        self.proc = None


class Dispatcher:
    def __init__(self, cfg):
        self.cfg = cfg
        os.makedirs(cfg["paths"]["runs"], exist_ok=True)
        self.store = storemod.Store(cfg["paths"]["db"])
        self.bridge = bridgemod.of(cfg, "bridge")
        self.skill_dir = os.path.join(cfg["paths"]["overlay"], "skill")
        self.events = queue.Queue()
        self.pending = []
        self.chat = collections.deque(maxlen=20)
        self.talked = {}  # игрок → когда голос ему ответил (разговор без имени ведущего)
        self.voice = Voice(cfg, self.skill_dir)
        self.turn = None
        self.skill_checked = 0.0
        self.voice_retry = 0.0
        self.workers = {}  # id задачи → процесс
        self.teacher = None
        self.gate = None
        self.last_teach = 0.0
        self.zones_seen = None
        self.rules_checked = 0.0
        self.stopping = False

    # ---------------------------------------------------------------- лента

    def follow(self):
        after, boot, down, pause = 0, None, False, 2
        first = True
        while not self.stopping:
            try:
                page = self.bridge.call("events", {"after": after, "wait": 0 if first else 25,
                                                   "limit": 1000 if first else 500}, timeout=40)
            except Exception as e:  # noqa: BLE001 — нить ленты не умирает: мост лёг, токен пропал — ждём
                if not down:
                    self.events.put({"type": "bridge_down", "error": f"{type(e).__name__}: {e}"})
                    down = True
                time.sleep(pause)
                pause = min(pause * 2, 30)
                continue
            if down:
                self.events.put({"type": "bridge_up"})
                down, pause = False, 2
            if boot is not None and page["boot"] != boot:
                self.events.put({"type": "server_restarted"})
            boot, after = page["boot"], page["next"]
            if first:  # старое — не ново: начинаем с этой минуты
                if len(page["events"]) < 1000:
                    first = False
                continue
            for e in page["events"]:
                self.events.put(e)

    def on_event(self, e):
        t = e.get("type")
        if t in ("chat", "gm") and (not e.get("bot") or e.get("player") in self.cfg["gm"]["bot_players"]):
            player, text = e.get("player"), (e.get("text") or "").strip()
            if not player or not text:
                return
            if t == "chat":
                self.chat.append({"player": player, "text": text})
            direct = t == "gm" or addressed(text, self.cfg["gm"]["names"])
            if direct or time.time() - self.talked.get(player, 0) < CONVERSATION_SECONDS:
                pos = f"{e['dimension']} {e['x']} {e['y']} {e['z']}" if "x" in e else None
                self.pending.append(Item(player, text, t, pos=pos, direct=direct))
                self.store.log("voice", "message", player, channel=t, text=text)
        elif t in ("bridge_down", "bridge_up", "server_restarted"):
            log(f"мост: {t} {e.get('error', '')}")
            self.store.log("gmd", t, error=e.get("error"))

    # ---------------------------------------------------------------- голос

    def voice_tick(self):
        limits = self.cfg["limits"]
        if self.turn is not None:
            try:
                result = self.voice.results.get_nowait()
            except queue.Empty:
                if time.time() - self.turn["started"] > limits["voice_turn_seconds"]:
                    self.store.log("voice", "voice_timeout", self.turn["player"], seconds=limits["voice_turn_seconds"])
                    if not self.store.logs(self.turn["log_id"], ["reply"], 1):
                        self.fallback(self.turn)
                    self.voice.stop()
                    self.turn = None
                return
            self.end_turn(result)
            return
        if not self.voice.alive():
            if self.voice.proc is not None:
                self.voice.stop()
                self.store.log("voice", "voice_crash", None, when="между ходами")
                self.voice_retry = time.time() + 30
            if time.time() < self.voice_retry:
                self.drop_stale()
                return
            try:
                self.voice.start()
            except OSError as e:  # claude не запускается: не каждый круг, а через 30 с
                self.store.log("voice", "voice_crash", None, when="запуск", error=str(e))
                self.voice_retry = time.time() + 30
                return
        elif self.voice.turns >= limits["voice_turns"] or self.skill_changed():
            log("голос: перезапуск в паузе (ходы или навык)")
            self.voice.stop()
            self.voice.start()
        player, items, self.pending = pick(self.pending)
        if not player:
            return
        channel = next((it.channel for it in reversed(items) if it.channel), None)
        if channel is None:  # одни итоги задач и вести: канал того, кто просил
            task = self.store.task(items[0].task) if items[0].task else None
            channel = task["channel"] if task else "gm"
        text = compose(self.cfg, player, items, self.store.tasks(player, limit=6), list(self.chat))
        self.turn = {"player": player, "channel": channel, "started": time.time(), "log_id": self.store.last_log_id(),
                     "asked": any(it.direct for it in items)}
        self.store.put("voice.requester", player)
        self.store.put("voice.turn", {k: self.turn[k] for k in ("player", "channel", "started", "log_id")})
        try:
            self.voice.send(text)
        except OSError as e:
            self.store.log("voice", "voice_crash", player, error=str(e))
            self.fallback(self.turn)
            self.voice.stop()
            self.turn = None

    def skill_changed(self):
        """Навык сменился (урок наставника, правка человека)? Проверка не чаще раза в 10 с."""
        if time.time() - self.skill_checked < 10:
            return False
        self.skill_checked = time.time()
        return self.voice.skill != roles.skill_hash(self.cfg, self.skill_dir)

    def end_turn(self, result):
        turn, self.turn = self.turn, None
        replies = self.store.logs(turn["log_id"], ["reply"])
        ms = round((time.time() - turn["started"]) * 1000)
        if result is None:  # процесс голоса кончился посреди хода
            self.store.log("voice", "voice_crash", turn["player"], ms=ms)
            if not replies:
                self.fallback(turn)
            self.voice.stop()
            return
        self.voice.turns += 1
        first = replies[0]["data"].get("ms") if replies else None
        self.store.log("voice", "turn", turn["player"], ms=ms, first_reply_ms=first, replies=len(replies),
                       cost=result.get("total_cost_usd"), usage=result.get("usage"),
                       error=result.get("subtype") if result.get("is_error") else None)
        text = result.get("result") or ""
        if replies:
            self.talked[turn["player"]] = time.time()
        elif result.get("is_error"):  # перегрузка, предел подписки: игрок не должен ждать впустую
            self.fallback(turn)
        elif turn["asked"] and "не мне" not in text.lower():
            self.store.log("voice", "no_reply", turn["player"], result=text[:500])
        if first and first > 15000:
            self.store.log("voice", "slow_reply", turn["player"], ms=first)

    def fallback(self, turn):
        """Голос не ответил: короткая честная фраза от имени ведущего, чтобы игрок не ждал впустую."""
        lang = conf.language(self.cfg, turn["player"])
        try:
            self.bridge.call("say", {"text": FALLBACK.get(lang, FALLBACK["ru"]),
                                     "to": [turn["player"]] if turn["channel"] == "gm" else None})
        except bridgemod.BridgeError as e:
            log(f"запасная фраза не ушла: {e}")

    def drop_stale(self):
        """Голос лежит: сообщения игроков старше STALE_SECONDS — запасная фраза (раз на игрока) и из очереди вон;
        итоги задач и вести ждут голоса."""
        now = time.time()
        stale = [it for it in self.pending if it.task is None and not it.note and now - it.time > STALE_SECONDS]
        if not stale:
            return
        told = set()
        for it in stale:
            if it.direct and it.player not in told:
                self.fallback({"player": it.player, "channel": it.channel})
                told.add(it.player)
        self.pending = [it for it in self.pending if it not in stale]
        self.store.log("gmd", "voice_down", None, dropped=len(stale), players=sorted(told))

    # ---------------------------------------------------------------- исполнители

    def workers_tick(self):
        now = time.time()
        for task_id, proc in list(self.workers.items()):
            task = self.store.task(task_id)
            code = proc.poll()
            if code is None:
                if task["cancel"]:
                    self.kill(proc)
                    self.store.finish(task_id, "cancelled", "отменена")
                elif now - task["started"] > task["minutes"] * 60:
                    self.kill(proc)
                    self.store.finish(task_id, "failed", f"срок {round(task['minutes'])} мин вышел, не доделано")
                else:
                    continue
                code = proc.poll()
                self.clean_up(task_id)
            del self.workers[task_id]
            self.worker_ended(task_id, code)
        running = [self.store.task(i) for i in self.workers]
        for task in self.store.tasks(open_only=True, limit=100):
            if task["status"] == "queued" and task["cancel"]:
                self.store.finish(task["id"], "cancelled", "отменена до начала")
        queued = sorted((t for t in self.store.tasks(open_only=True, limit=100) if t["status"] == "queued"),
                        key=lambda t: t["id"])
        for task in runnable(queued, running, self.cfg["limits"]["workers"]):
            self.start_worker(task)

    def start_worker(self, task):
        run_dir = os.path.join(self.cfg["paths"]["runs"], f"task-{task['id']}")
        argv, env = roles.prepare(self.cfg, "worker", run_dir, self.skill_dir, task=task["id"])
        argv += ["--output-format", "json", "--max-budget-usd", str(self.cfg["limits"]["task_usd"])]
        lang = conf.language(self.cfg, task["player"])
        owner = " (владелец)" if conf.is_owner(self.cfg, task["player"]) else ""
        prompt = (f"Задача #{task['id']} от {task['player']}{owner}, язык игрока: {lang}.\n"
                  f"Что просит: «{task['text']}»\n")
        if task["recipe"]:
            prompt += (f"Рецепт: {task['recipe']} {json.dumps(task['params'], ensure_ascii=False)} — сначала он "
                       "(recipe_run); не вышел — разберись по его тексту и доделай.\n")
        prompt += (f"Срок: {round(task['minutes'])} мин. Конец — done с проверкой в мире или fail с причиной. "
                   "Игрокам не пишешь: итог скажет голос.")
        proc = spawn(argv, run_dir, run_dir, env, prompt)
        self.workers[task["id"]] = proc
        self.store.task_update(task["id"], status="running", started=time.time(), pid=proc.pid)
        self.store.log("worker", "task_start", task["player"], task["id"], text=task["text"], recipe=task["recipe"])
        log(f"задача #{task['id']} ({task['player']}): исполнитель {proc.pid}")

    def worker_ended(self, task_id, code):
        run_dir = os.path.join(self.cfg["paths"]["runs"], f"task-{task_id}")
        try:
            with open(os.path.join(run_dir, "out.json"), encoding="utf-8") as f:
                out = json.load(f)
        except (OSError, ValueError):
            out = {}
        task = self.store.task(task_id)
        if task["status"] in storemod.OPEN:
            why = (out.get("result") or f"код {code}")[:300]
            self.store.finish(task_id, "failed", "исполнитель кончил без итога: " + why)
            task = self.store.task(task_id)
        self.store.log("worker", "task_end", task["player"], task_id, status=task["status"], code=code,
                       minutes=round((time.time() - task["started"]) / 60, 1), cost=out.get("total_cost_usd"),
                       usage=out.get("usage"), turns=out.get("num_turns"))
        log(f"задача #{task_id}: {task['status']}")
        if task["status"] in ("done", "failed"):
            self.pending.append(Item(task["player"], notice(task), task=task_id))

    def clean_up(self, task_id):
        """Оборванная задача: её боты и правила (по журналу made) — из мира. Районы держат срок сами."""
        for e in self.store.logs(0, ["made"], 100_000):
            if e["task"] != task_id:
                continue
            what, name = e["data"].get("what"), e["data"].get("name")
            try:
                self.bridge.call("bot.remove" if what == "bot" else "rule.remove", {"name": name})
                self.store.log("gmd", "cleaned", e["player"], task_id, what=what, name=name)
            except bridgemod.BridgeError as err:  # уже ушёл сам — не беда
                self.store.log("gmd", "cleaned", e["player"], task_id, what=what, name=name, error=str(err))

    @staticmethod
    def kill(proc):
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()

    # ---------------------------------------------------------------- наставник

    def git(self, *args, cwd=None):
        return subprocess.run(["git", "-c", "user.name=Ведущий", "-c", "user.email=gm@localhost", *args],
                              cwd=cwd or self.cfg["paths"]["overlay"], capture_output=True, text=True)

    def teacher_tick(self):
        limits = self.cfg["limits"]
        if self.gate is not None:
            if self.gate.is_alive():
                return
            self.gate = None
        if self.teacher is not None:
            proc, wt, base, until, started = self.teacher
            if proc.poll() is None:
                if time.time() - started > limits["teacher_minutes"] * 60:
                    self.kill(proc)
                else:
                    return
            self.teacher = None
            self.gate = threading.Thread(target=self.after_teacher, args=(wt, base, until), daemon=True)
            self.gate.start()
            return
        if time.time() - self.last_teach < limits["teacher_gap_minutes"] * 60:
            return
        seen = self.store.get("teacher.seen", 0)
        # свои ошибки наставника (отказ охраны, сбой инструмента) — не повод звать его снова
        signal = [e for e in self.store.logs(seen, SIGNALS, limit=500) if e["role"] != "teacher"]
        if not signal and len(self.store.logs(seen, ["message"], TEACH_AFTER)) < TEACH_AFTER:
            return
        self.start_teacher(seen)

    def start_teacher(self, seen):
        overlay = self.cfg["paths"]["overlay"]
        if not os.path.isdir(os.path.join(overlay, ".git")):
            return
        wt = os.path.join(self.cfg["paths"]["runs"], "teach")
        self.git("worktree", "remove", "--force", wt)
        self.git("worktree", "prune")
        base = self.git("rev-parse", "HEAD").stdout.strip()
        r = self.git("worktree", "add", "--detach", wt, base)
        if r.returncode:
            log("наставник: worktree не создан: " + r.stderr.strip())
            self.last_teach = time.time()
            return
        rows = self.store.logs(seen, limit=EXPERIENCE_ROWS)  # больше — следующим уроком, не мимо
        until = rows[-1]["id"] if len(rows) == EXPERIENCE_ROWS else self.store.last_log_id()
        run_dir = os.path.join(self.cfg["paths"]["runs"], "teacher")
        os.makedirs(run_dir, exist_ok=True)
        zones = os.path.join(run_dir, "zones.json")  # копия: пробы наставника не меняют охрану игры
        with zonesmod.Locked(self.cfg["paths"]["zones"]):
            if os.path.exists(self.cfg["paths"]["zones"]):
                shutil.copyfile(self.cfg["paths"]["zones"], zones)
            elif os.path.exists(zones):
                os.remove(zones)
        argv, env = roles.prepare(self.cfg, "teacher", run_dir, os.path.join(wt, "skill"), writable=wt, add_dirs=(wt,),
                                  zones=zones)
        argv += ["--output-format", "json", "--max-budget-usd", str(self.cfg["limits"]["teacher_usd"])]
        prompt = (f"Новый опыт — записи журнала после {seen} до {until} (experience). "
                  f"Твоя копия знаний сервера — {wt}: правь там skill/ и evals/learned.jsonl по своей роли; рецепты — с удачной пробой recipe_test. "
                  "В конце — одна строка: что изменил и почему (или «без изменений» и почему).")
        proc = spawn(argv, run_dir, wt, env, prompt)
        self.teacher = (proc, wt, base, until, time.time())
        self.store.log("teacher", "teach_start", seen=seen, until=until)
        log(f"наставник: урок по журналу {seen}..{until}")

    def after_teacher(self, wt, base, until):
        """После урока: правка → ворота → в навык; журнал до until разобран в любом случае. Свой поток — своя
        связь с базой."""
        import gate
        store = storemod.Store(self.cfg["paths"]["db"])
        try:
            with open(os.path.join(self.cfg["paths"]["runs"], "teacher", "out.json"), encoding="utf-8") as f:
                out = json.load(f)
        except (OSError, ValueError):
            out = {}
        summary = (out.get("result") or "").strip().splitlines()[-1:] or ["урок"]
        self.git("add", "-A", cwd=wt)
        changed = self.git("diff", "--cached", "--name-only", base, cwd=wt).stdout.split()
        try:
            if not changed:
                store.log("teacher", "teach_nochange", summary=summary[0], cost=out.get("total_cost_usd"))
                return
            ok, report = gate.run(self.cfg, store, wt, base)
            if not ok:
                store.log("teacher", "teach_rejected", summary=summary[0], files=changed, report=report,
                               cost=out.get("total_cost_usd"))
                log("наставник: правка не прошла ворота")
                return
            self.git("commit", "-q", "-m", "Урок: " + summary[0][:200], cwd=wt)
            sha = self.git("rev-parse", "HEAD", cwd=wt).stdout.strip()
            merged = self.git("merge", "--ff-only", "-q", sha)
            if merged.returncode:
                store.log("teacher", "teach_conflict", summary=summary[0], error=merged.stderr[-500:])
                return
            store.log("teacher", "teach_merged", summary=summary[0], files=changed, commit=sha, report=report,
                           cost=out.get("total_cost_usd"))
            log(f"наставник: в навыке {sha[:8]} — {summary[0][:120]}")
        except Exception as e:  # ворота упали — правка не входит
            store.log("teacher", "teach_rejected", summary=summary[0], files=changed, report=f"ворота: {e}")
        finally:
            store.put("teacher.seen", until)
            self.last_teach = time.time()

    # ---------------------------------------------------------------- цикл

    # ---------------------------------------------------------------- удары в правилах

    def zones_tick(self):
        """Правило с ударом идёт в игре само: зоны сверены при его постановке. Зону поставили позже (голос, человек
        из zones.py) — скрипт правила сверяется снова, задетое правило снимается, игроку — весть через голос."""
        try:
            mtime = os.stat(self.cfg["paths"]["zones"]).st_mtime_ns
        except FileNotFoundError:
            mtime = 0
        if mtime == self.zones_seen and time.time() - self.rules_checked < RULES_RECHECK:
            return
        self.zones_seen, self.rules_checked = mtime, time.time()  # мост лёг — снова через минуту, не каждый круг
        remembered = self.store.items(guard.RULE_KEY)
        if not remembered:
            return
        on = {r.get("name") for r in self.bridge.call("rules") if r.get("state") == "on"}
        for name, rule in remembered.items():
            if name not in on:
                continue
            reason = guard.check_script(rule["script"], guard.Ctx(self.cfg, rule["role"], rule["requester"]))
            if not reason:
                continue
            try:
                self.bridge.call("rule.remove", {"name": name})
            except bridgemod.BridgeError as e:  # память остаётся: снова через минуту
                self.store.log("gmd", "error", where="zones_tick", error=f"rule.remove {name}: {e}")
                continue
            self.store.drop(guard.RULE_KEY + name)
            self.store.log("gmd", "rule_removed", rule["requester"], rule["task"], name=name, reason=reason)
            log(f"правило «{name}» снято: {reason}")
            if rule["requester"]:
                self.pending.append(Item(rule["requester"], f"Правило «{name}» снято охраной: {reason}. Скажи "
                                         "игроку коротко, что удары по нему остановлены и почему.",
                                         task=rule["task"], note=True))

    def recover(self):
        """Задачи, оборванные прошлым запуском диспетчера: честно — не доделаны."""
        for task in self.store.tasks(open_only=True, limit=100):
            if task["status"] == "running":
                self.store.finish(task["id"], "failed", "ведущего перезапустили посреди задачи — не доделана")
                self.pending.append(Item(task["player"], notice(self.store.task(task["id"])), task=task["id"]))

    def run(self):
        self.recover()
        threading.Thread(target=self.follow, daemon=True).start()
        log("ведущий слушает")
        while not self.stopping:
            try:
                while True:
                    self.on_event(self.events.get_nowait())
            except queue.Empty:
                pass
            for step in (self.voice_tick, self.workers_tick, self.teacher_tick, self.zones_tick):
                try:
                    step()
                except Exception as e:  # служба не падает от одной ошибки: в журнал и дальше
                    log(f"{step.__name__}: {type(e).__name__}: {e}")
                    self.store.log("gmd", "error", where=step.__name__, error=f"{type(e).__name__}: {e}")
            time.sleep(0.2)
        self.voice.stop()
        for proc in self.workers.values():
            self.kill(proc)
        if self.teacher:
            self.kill(self.teacher[0])


def main():
    p = argparse.ArgumentParser(description="Диспетчер ведущего")
    p.add_argument("--config", help="config.toml оверлея (иначе GM_CONFIG)")
    a = p.parse_args()
    d = Dispatcher(conf.load(a.config))

    def stop(*_):
        d.stopping = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    d.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
