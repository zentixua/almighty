"""Память служб ведущего — SQLite (gm.db в оверлее): очередь задач, журнал опыта, общее состояние, итоги проверок.

Пишут несколько процессов сразу (диспетчер, инструменты ведущего каждой сессии, охрана): WAL и ожидание блокировки.
Журнал — сырьё наставника: всё, что случилось (сообщения, ответы с временем, задачи, отказы охраны, ошибки,
уроки), по строке на событие.
"""
import json
import sqlite3
import time

SCHEMA = """
create table if not exists tasks(
  id integer primary key, created real, player text, text text, channel text, recipe text, params text,
  resources text, minutes real, status text, started real, finished real, pid integer, progress text,
  summary text, evidence text, cancel integer default 0);
create table if not exists log(
  id integer primary key, ts real, role text, kind text, player text, task integer, data text);
create index if not exists log_kind on log(kind, id);
create table if not exists state(key text primary key, value text);
create table if not exists evals(skill text, case_id text, ok integer, detail text, primary key(skill, case_id));
"""

OPEN = ("queued", "running")


class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, timeout=10, isolation_level=None, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("pragma journal_mode=wal")
        self.db.execute("pragma busy_timeout=10000")
        self.db.executescript(SCHEMA)

    # ---------------------------------------------------------------- журнал

    def log(self, role, kind, player=None, task=None, **data):
        cur = self.db.execute("insert into log(ts, role, kind, player, task, data) values (?, ?, ?, ?, ?, ?)",
                              (time.time(), role, kind, player, task, json.dumps(data, ensure_ascii=False)))
        return cur.lastrowid

    def logs(self, after=0, kinds=None, limit=500):
        q, args = "select * from log where id > ?", [after]
        if kinds:
            q += f" and kind in ({','.join('?' * len(kinds))})"
            args += list(kinds)
        rows = self.db.execute(q + " order by id limit ?", args + [limit]).fetchall()
        return [dict(r, data=json.loads(r["data"] or "{}")) for r in rows]

    def last_log_id(self):
        return self.db.execute("select coalesce(max(id), 0) from log").fetchone()[0]

    # ---------------------------------------------------------------- состояние

    def get(self, key, default=None):
        row = self.db.execute("select value from state where key = ?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def put(self, key, value):
        self.db.execute("insert into state(key, value) values (?, ?) "
                        "on conflict(key) do update set value = excluded.value", (key, json.dumps(value, ensure_ascii=False)))

    def drop(self, key):
        self.db.execute("delete from state where key = ?", (key,))

    def items(self, prefix):
        """Состояние с ключами на prefix: {ключ без prefix: значение}."""
        rows = self.db.execute("select key, value from state where substr(key, 1, ?) = ?", (len(prefix), prefix))
        return {k[len(prefix):]: json.loads(v) for k, v in rows.fetchall()}

    # ---------------------------------------------------------------- задачи

    def task_add(self, player, text, channel=None, recipe=None, params=None, resources=None, minutes=None):
        cur = self.db.execute(
            "insert into tasks(created, player, text, channel, recipe, params, resources, minutes, status) "
            "values (?, ?, ?, ?, ?, ?, ?, ?, 'queued')",
            (time.time(), player, text, channel, recipe, json.dumps(params or {}, ensure_ascii=False),
             json.dumps(sorted(set(resources or []))), minutes))
        return cur.lastrowid

    def task(self, task_id):
        row = self.db.execute("select * from tasks where id = ?", (task_id,)).fetchone()
        return _task(row) if row else None

    def tasks(self, player=None, open_only=False, limit=20):
        q, args = "select * from tasks where 1", []
        if player:
            q += " and player = ?"
            args.append(player)
        if open_only:
            q += " and status in ('queued', 'running')"
        return [_task(r) for r in self.db.execute(q + " order by id desc limit ?", args + [limit]).fetchall()]

    def task_update(self, task_id, **fields):
        for k in ("params", "evidence"):
            if k in fields and not isinstance(fields[k], str) and fields[k] is not None:
                fields[k] = json.dumps(fields[k], ensure_ascii=False)
        sets = ", ".join(f"{k} = ?" for k in fields)
        self.db.execute(f"update tasks set {sets} where id = ?", (*fields.values(), task_id))

    def finish(self, task_id, status, summary, evidence=None):
        """Задача кончилась — только из открытого состояния: второй итог (done после отмены) не пишется поверх."""
        cur = self.db.execute(
            "update tasks set status = ?, summary = ?, evidence = ?, finished = ? "
            "where id = ? and status in ('queued', 'running')",
            (status, summary, json.dumps(evidence, ensure_ascii=False) if evidence is not None else None,
             time.time(), task_id))
        return cur.rowcount == 1

    # ---------------------------------------------------------------- итоги проверочного набора

    def eval_results(self, skill):
        return {r["case_id"]: (bool(r["ok"]), r["detail"])
                for r in self.db.execute("select * from evals where skill = ?", (skill,)).fetchall()}

    def eval_save(self, skill, results):
        self.db.executemany("insert or replace into evals(skill, case_id, ok, detail) values (?, ?, ?, ?)",
                            [(skill, cid, int(ok), detail) for cid, (ok, detail) in results.items()])


def _task(row):
    t = dict(row)
    t["params"] = json.loads(t["params"] or "{}")
    t["resources"] = json.loads(t["resources"] or "[]")
    t["evidence"] = json.loads(t["evidence"]) if t["evidence"] else None
    return t
