"""Проверки набора ведущего без сети и моделей: python3 -m unittest discover -s mcp/gm/tests"""
import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest

KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, KIT)

import conf  # noqa: E402
import gate  # noqa: E402
import gmd  # noqa: E402
import guard  # noqa: E402
import recipes  # noqa: E402
import roles  # noqa: E402
import store as storemod  # noqa: E402
import zones  # noqa: E402


def make_overlay(tmp, extra=""):
    """Каталог сервера с config.toml по образцу набора; токены — пустые файлы во временном каталоге."""
    token = os.path.join(tmp, "token")
    with open(token, "w") as f:
        f.write("t")
    with open(os.path.join(KIT, "config.example.toml"), encoding="utf-8") as f:
        text = f.read()
    text = text.replace("~/airstrike-server/gm-token", token).replace("~/airstrike-work/gm-test/token", token)
    with open(os.path.join(tmp, "config.toml"), "w", encoding="utf-8") as f:
        f.write(text + extra)
    os.makedirs(os.path.join(tmp, "skill", "recipes"), exist_ok=True)
    return conf.load(os.path.join(tmp, "config.toml"))


class Tmp(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.cfg = make_overlay(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def ctx(self, role="worker", requester="Steve"):
        return guard.Ctx(self.cfg, role, requester)

    def zone(self, name, x, z, r):
        zs = zones.load(self.cfg["paths"]["zones"])
        zs[name] = {"x": x, "z": z, "r": r}
        zones.save(zs, self.cfg["paths"]["zones"])


class ZonesTest(unittest.TestCase):
    def test_target_and_path(self):
        zs = {"порт": {"x": 0, "z": 0, "r": 100}}
        self.assertTrue(zones.conflicts(zs, (50, 0)))
        self.assertTrue(zones.conflicts(zs, (150, 0)))  # запас 64
        self.assertFalse(zones.conflicts(zs, (300, 0)))
        found = zones.conflicts(zs, (500, 0), frm=(-500, 0))
        self.assertIn("обход", found[0])
        self.assertFalse(zones.conflicts(zs, (500, 0), frm=(-500, 0), via=[(0, 300)]))


class GuardTest(Tmp):
    def test_speech_denied_everywhere(self):
        for role in ("voice", "worker", "teacher"):
            self.assertIn("голос", guard.check_command("say привет", self.ctx(role)))
            self.assertIn("голос", guard.check_command("/tellraw @a {\"text\":\"x\"}", self.ctx(role)))
            title = "execute as @a run title @s title {\"text\":\"x\"}"
            self.assertIn("голос", guard.check_command(title, self.ctx(role)))
            self.assertIn("голос", guard.check_call("say", {"text": "x"}, self.ctx(role)))
        self.assertIsNone(guard.check_command("w", self.ctx()))  # не команда речи без текста

    def test_never_and_owner(self):
        self.assertIn("никогда", guard.check_command("reload", self.ctx()))
        self.assertIn("никогда", guard.check_command("forceload add 0 0 100 100", self.ctx()))
        self.assertIn("никогда", guard.check_command("summon fireball 0 100 0", self.ctx()))
        self.assertIn("владельца", guard.check_command("op Steve", self.ctx(requester="Steve")))
        self.assertIsNone(guard.check_command("op Steve", self.ctx(requester="ZentixUA")))
        self.assertIn("владельца", guard.check_command("op Steve", self.ctx(role="teacher", requester=None)))

    def test_strikes(self):
        c = self.ctx(requester="ZentixUA")
        self.assertIn("числами", guard.check_command("airstrike drone @p", c))
        self.assertIn("числами", guard.check_command("airstrike salvo grad 5 20 look", c))
        self.assertIn("числами", guard.check_command("airstrike salvo grad 5 20 at ~ ~ ~10", c))
        self.assertIsNone(guard.check_command("airstrike salvo grad 5 20 at 300 64 0 from 600 0", c))
        self.zone("порт", 0, 0, 100)
        self.assertIn("зоны", guard.check_command("airstrike salvo grad 5 20 at 100 64 0", c))
        self.assertIn("зоны", guard.check_command("airstrike salvo missile 1 0 at 500 64 0 from -500 0", c))
        self.assertIsNone(guard.check_command("airstrike salvo missile 1 0 at 500 64 0 from -500 0 via 0 400", c))
        self.assertIn("стационарной", guard.check_command("airstrike launcher 1 64 1 fire", c))
        self.assertIn("зоны", guard.check_command("airstrike launcher 900 64 0 mission grad 4 10 50 64 0", c))
        self.assertIn("владельца", guard.check_command("airstrike nuke at 5000 64 5000", self.ctx(requester="Steve")))
        self.assertIsNone(guard.check_command("airstrike nuke at 5000 64 5000 20 air", c))
        self.assertIn("зоны", guard.check_command("airstrike nuke at 600 64 0", c))  # запас ядерки 600
        self.assertIsNone(guard.check_command("airstrike clear", c))

    def test_plot_coordinates(self):
        self.assertIn("Sable", guard.check_command("tp Steve 20480100 100 0", self.ctx()))
        self.assertIn("Sable", guard.check_call("build", {"ops": [{"op": "set", "pos": [20480000, 64, 0],
                                                                    "block": "stone"}]}, self.ctx()))
        self.assertIn("Sable", guard.check_call("area.prepare", {"from": [20480000, 0], "to": [20480100, 0]},
                                                self.ctx()))

    def test_scripts(self):
        c = self.ctx()
        for code in ("level.getChunk(0, 0)", "new Thread({ -> }).start()", "server.execute({ -> })",
                     "def t = TicketType.create('x', null)", "p.sendSystemMessage(Component.literal('hi'))",
                     "gm.command('say hi')", "'rm -rf /'.execute()", "new File('/etc/passwd').text"):
            self.assertIsNotNone(guard.check_script(code, c), code)
        self.assertIsNone(guard.check_script("def p = gm.player('Steve'); return p.blockPosition().toString()", c))
        self.assertIsNone(guard.check_script("lvl.getChunkSource().getChunkNow(0, 0)", c))
        self.zone("порт", 0, 0, 100)
        self.assertIn("зоны", guard.check_script("gm.command('airstrike salvo grad 4 10 at 20 64 20')",
                                                 self.ctx(requester="ZentixUA")))
        self.assertIn("числами", guard.check_script('gm.command("airstrike salvo grad 4 10 at ${x} 64 ${z}")', c))
        self.assertIn("числами", guard.check_call("rule.add", {"event": "tick", "script":
                                                  "gm.command('airstrike drone @p')"}, c))

    def test_roles_and_tools(self):
        self.assertIn("задач", guard.check_call("build", {"ops": []}, self.ctx("voice")))
        self.assertIn("задач", guard.check_tool("mcp__almighty__call", {"method": "bot.spawn",
                                                                         "params": {"name": "b"}}, self.ctx("voice")))
        self.assertIn("голос", guard.check_tool("mcp__almighty__call", {"method": "say", "params": {}}, self.ctx()))
        self.assertIn("бот", guard.check_tool("mcp__almighty__bot", {"action": "act", "name": "b",
                                                                      "actions": [{"chat": "привет"}]}, self.ctx()))
        self.assertIsNone(guard.check_tool("mcp__almighty__bot", {"action": "act", "name": "b",
                                                                   "actions": [{"chat": "/spawn"}]}, self.ctx()))
        self.assertIsNone(guard.check_tool("mcp__almighty__view", {"kind": "map", "center": [0, 0]}, self.ctx()))
        self.assertIn("голос", guard.check_tool("mcp__almighty__command", {"lines": ["# x", "$say $(t)"]}, self.ctx()))

    def test_teacher_edits(self):
        wt = os.path.join(self.tmp, "wt")
        os.makedirs(os.path.join(wt, "skill", "knowledge"))
        os.environ["GM_WRITABLE"] = wt
        try:
            t = self.ctx("teacher", None)
            self.assertIsNone(guard.check_edit(os.path.join(wt, "skill", "knowledge", "places.md"), t))
            self.assertIsNone(guard.check_edit(os.path.join(wt, "skill", "CORE.md"), t))
            self.assertIsNotNone(guard.check_edit(os.path.join(wt, "config.toml"), t))
            self.assertIsNotNone(guard.check_edit(os.path.join(KIT, "guard.py"), t))
            self.assertIsNotNone(guard.check_edit(os.path.join(wt, "skill", "CORE.md"), self.ctx("worker")))
        finally:
            del os.environ["GM_WRITABLE"]

    def test_hook_protocol(self):
        env = dict(os.environ, GM_CONFIG=self.cfg["paths"]["config"], GM_ROLE="worker")
        event = {"tool_name": "mcp__almighty__command", "tool_input": {"commands": ["say hi"]}}
        out = subprocess.run([sys.executable, os.path.join(KIT, "guard.py")], input=json.dumps(event), env=env,
                             capture_output=True, text=True)
        decision = json.loads(out.stdout)["hookSpecificOutput"]
        self.assertEqual(decision["permissionDecision"], "deny")
        ok = {"tool_name": "mcp__almighty__status", "tool_input": {}}
        out = subprocess.run([sys.executable, os.path.join(KIT, "guard.py")], input=json.dumps(ok), env=env,
                             capture_output=True, text=True)
        self.assertEqual(out.stdout.strip(), "")
        denials = storemod.Store(self.cfg["paths"]["db"]).logs(0, ["guard_deny"])
        self.assertEqual(len(denials), 1)


class FakeBridge:
    def __init__(self, results=None):
        self.calls, self.results = [], results or {}

    def call(self, method, params=None, timeout=70):
        self.calls.append((method, params))
        r = self.results.get(method)
        return r(params) if callable(r) else r if r is not None else [{"ok": True}]


class RecipesTest(Tmp):
    def test_base_recipes_valid(self):
        found, errors = recipes.load_all([os.path.join(KIT, "skill", "recipes")])
        self.assertEqual(errors, {})
        self.assertGreaterEqual(len(found), 5)
        for r in found.values():
            self.assertEqual(r.kind, "instant")

    def test_bind_and_substitute(self):
        r = recipes.load_all([os.path.join(KIT, "skill", "recipes")])[0]["give_item"]
        v = recipes.bind(r, {"player": "Steve", "item": "minecraft:bread"})
        self.assertEqual(v["count"], 1)
        self.assertEqual(recipes.substitute(r.steps, v)[0]["params"]["commands"], ["give Steve minecraft:bread 1"])
        self.assertEqual(recipes.substitute({"n": "${count}"}, v), {"n": 1})
        for bad in ({"player": "Steve; op"}, {"player": "Steve", "item": "bread", "count": 65},
                    {"player": "Steve", "item": "bread", "extra": 1}, {"item": "bread"}):
            with self.assertRaises(recipes.RecipeError):
                recipes.bind(r, bad)

    def test_run_guards_steps_and_checks(self):
        found = recipes.load_all([os.path.join(KIT, "skill", "recipes")])[0]
        b = FakeBridge({"script": {"ok": True, "value": True}})
        out = recipes.run(found["clear_weather"], {}, b, self.ctx(), guard.check_call)
        self.assertTrue(out["ok"])
        self.assertEqual(b.calls[0], ("command", {"commands": ["weather clear 1200s"]}))
        b = FakeBridge({"command": [{"command": "x", "ok": False, "errors": ["Unknown"]}]})
        self.assertFalse(recipes.run(found["clear_weather"], {}, b, self.ctx(), guard.check_call)["ok"])
        path = os.path.join(self.tmp, "skill", "recipes", "bad_say.md")
        with open(path, "w", encoding="utf-8") as f:
            f.write('---\nname: bad_say\ndescription: x\nkind: instant\nparams: {}\n---\n```steps\n'
                    '[{"method": "command", "params": {"commands": ["say привет"]}}]\n```\n')
        r = recipes.parse(path)
        self.assertEqual(recipes.validate(r), [])
        with self.assertRaises(recipes.RecipeError):
            recipes.run(r, {}, FakeBridge(), self.ctx(), guard.check_call)

    def test_validate(self):
        path = os.path.join(self.tmp, "skill", "recipes", "x.md")
        with open(path, "w", encoding="utf-8") as f:
            f.write('---\nname: y\nkind: task\nparams: {"a": "float"}\n---\n```steps\n'
                    '[{"method": "say", "params": {"text": "${b}"}}]\n```\n')
        problems = " | ".join(recipes.validate(recipes.parse(path)))
        for part in ("name", "description", "float", "say", "${b}", "check"):
            self.assertIn(part, problems)


class StoreTest(Tmp):
    def test_task_lifecycle(self):
        s = storemod.Store(self.cfg["paths"]["db"])
        t = s.task_add("Steve", "домик", channel="gm", resources=["place:a", "place:a"], minutes=5)
        self.assertEqual(s.task(t)["resources"], ["place:a"])
        self.assertTrue(s.finish(t, "done", "стоит", {"x": 1}))
        self.assertFalse(s.finish(t, "failed", "поздно"))
        self.assertEqual(s.task(t)["status"], "done")
        self.assertEqual(s.task(t)["evidence"], {"x": 1})


class DispatcherPartsTest(Tmp):
    def test_addressed(self):
        names = self.cfg["gm"]["names"]
        self.assertTrue(gmd.addressed("Ведущий, дай хлеба", names))
        self.assertTrue(gmd.addressed("эй ведучий", names))
        self.assertFalse(gmd.addressed("ведущийся спор", names))
        self.assertFalse(gmd.addressed("всем привет", names))

    def test_pick_and_runnable(self):
        items = [gmd.Item("A", "1"), gmd.Item("B", "2"), gmd.Item("A", "3")]
        player, mine, rest = gmd.pick(items)
        self.assertEqual((player, [i.text for i in mine], [i.text for i in rest]), ("A", ["1", "3"], ["2"]))
        q = [{"id": 1, "resources": ["ship"]}, {"id": 2, "resources": ["ship"]}, {"id": 3, "resources": []}]
        self.assertEqual([t["id"] for t in gmd.runnable(q, [], 2)], [1, 3])
        self.assertEqual([t["id"] for t in gmd.runnable(q, [{"resources": ["ship"]}], 2)], [3])

    def test_compose(self):
        item = gmd.Item("WallyFillmark", "привіт", "gm", pos="overworld 1 2 3")
        text = gmd.compose(self.cfg, "WallyFillmark", [item],
                           [{"id": 4, "text": "літак", "status": "running", "progress": "збираю"}],
                           [{"player": "ENOTzRPG", "text": "го в шахту"}])
        self.assertIn("uk", text)
        self.assertIn("#4", text)
        self.assertIn("ENOTzRPG", text)
        self.assertIn("overworld 1 2 3", text)


class RolesTest(Tmp):
    def test_prepare(self):
        argv, env = roles.prepare(self.cfg, "voice", os.path.join(self.tmp, "runs", "v"),
                                  os.path.join(self.tmp, "skill"))
        prompt = argv[argv.index("--system-prompt") + 1]
        self.assertIn("Правила ведущего", prompt)
        self.assertIn("Роль: голос", prompt)
        self.assertIn("ZentixUA", prompt)
        self.assertIn("--strict-mcp-config", argv)
        self.assertEqual(env["GM_ROLE"], "voice")
        self.assertEqual(env["CLAUDE_CODE_DISABLE_AUTO_MEMORY"], "1")
        with open(os.path.join(self.tmp, "runs", "v", "settings.json"), encoding="utf-8") as f:
            s = json.load(f)
        self.assertIn("mcp__almighty__build", s["permissions"]["deny"])
        self.assertTrue(any(d.startswith("Read(//") for d in s["permissions"]["deny"]))
        self.assertIn("guard.py", s["hooks"]["PreToolUse"][0]["hooks"][0]["command"])
        argv, _ = roles.prepare(self.cfg, "teacher", os.path.join(self.tmp, "runs", "t"),
                                os.path.join(self.tmp, "skill"), writable=self.tmp)
        with open(os.path.join(self.tmp, "runs", "t", "mcp.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f)["mcpServers"]["almighty"]["env"]["ALMIGHTY_URL"], "http://127.0.0.1:25651")


class GateTest(Tmp):
    def test_grade(self):
        case = {"expect": {"action": ["refuse"], "lang": "uk", "must_not": ["готово"], "max_chars": 50}}
        self.assertEqual(gate.grade(case, {"action": "refuse", "reply": "Ні, так не можна."}), [])
        long = "Готово, всё сделано, ещё и это, и то, и вон то ещё сверху"
        self.assertEqual(len(gate.grade(case, {"action": "task", "reply": long})), 4)

    def test_lint(self):
        def git(*a):
            subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *a], cwd=self.tmp, check=True,
                           capture_output=True)
        os.makedirs(os.path.join(self.tmp, "evals"))
        with open(os.path.join(self.tmp, ".gitignore"), "w") as f:
            f.write("gm.db*\nruns/\nzones.json*\n")
        with open(os.path.join(self.tmp, "evals", "learned.jsonl"), "w") as f:
            f.write('{"id": "a", "text": "x", "expect": {}}\n')
        git("init", "-q")
        git("add", "-A")
        git("commit", "-qm", "base")
        base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.tmp, capture_output=True, text=True).stdout.strip()
        store = storemod.Store(self.cfg["paths"]["db"])
        with open(os.path.join(self.tmp, "skill", "CORE.md"), "w") as f:
            f.write("- правило\n")
        with open(os.path.join(self.tmp, "evals", "learned.jsonl"), "a") as f:
            f.write('{"id": "b", "text": "y", "expect": {"action": ["answer"]}}\n')
        git("add", "skill", "evals")
        self.assertEqual(gate.lint(self.cfg, store, self.tmp, base), [])
        with open(os.path.join(self.tmp, "evals", "learned.jsonl"), "w") as f:
            f.write('{"id": "b", "text": "y", "expect": {}}\n')
        with open(self.cfg["paths"]["config"], "a") as f:
            f.write("# правка\n")
        recipe = os.path.join(self.tmp, "skill", "recipes", "night.md")
        with open(recipe, "w", encoding="utf-8") as f:
            f.write('---\nname: night\ndescription: ночь\nkind: instant\nparams: {}\n---\n```steps\n'
                    '[{"method": "command", "params": {"commands": ["time set 13000"]}}]\n```\n')
        git("add", "-A")
        errors = " | ".join(gate.lint(self.cfg, store, self.tmp, base))
        self.assertIn("дописывать", errors)
        self.assertIn("пробы", errors)
        self.assertIn("config.toml", errors)
        import hashlib
        with open(recipe, "rb") as f:
            store.log("teacher", "recipe_test", recipe="night", sha256=hashlib.sha256(f.read()).hexdigest(), ok=True)
        self.assertNotIn("пробы", " | ".join(gate.lint(self.cfg, store, self.tmp, base)))


class Bridge(http.server.BaseHTTPRequestHandler):
    """Мост-заглушка: лента из очереди теста, say — в список."""
    events, said, polls = [], [], [0]

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        method = body["method"]
        if method == "events":
            Bridge.polls[0] += 1
            batch = [] if Bridge.polls[0] == 1 else Bridge.events[:]
            del Bridge.events[:len(batch)]
            if not batch:
                time.sleep(0.05)
            result = {"boot": "b", "next": body["params"].get("after", 0) + len(batch), "events": batch}
        else:
            Bridge.said.append(body["params"])
            result = {}
        data = json.dumps({"ok": True, "result": result}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


class DispatcherTest(Tmp):
    """Диспетчер целиком с заглушками моста и claude: сообщение → ход голоса → задача → исполнитель → итог голосу."""

    def test_flow(self):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Bridge)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        fake = os.path.join(KIT, "tests", "fake_claude.py")
        os.chmod(fake, 0o755)
        cfg = make_overlay(self.tmp, "")
        cfg["bridge"]["url"] = f"http://127.0.0.1:{server.server_port}"
        cfg["gm"]["claude"] = fake
        os.environ["GM_CONFIG"] = cfg["paths"]["config"]
        d = gmd.Dispatcher(cfg)
        t = threading.Thread(target=d.run, daemon=True)
        t.start()
        store = storemod.Store(cfg["paths"]["db"])

        def wait(cond, what):
            for _ in range(150):
                if cond():
                    return
                time.sleep(0.1)
            self.fail(what + ": " + json.dumps(store.logs(0), ensure_ascii=False)[:3000])

        try:
            wait(lambda: Bridge.polls[0] >= 1, "лента не опрошена")
            Bridge.events += [{"type": "chat", "player": "Steve", "text": "всем привет"},
                              {"type": "gm", "player": "Steve", "text": "привет", "dimension": "minecraft:overworld",
                               "x": 1, "y": 64, "z": 2}]
            wait(lambda: store.logs(0, ["turn"]), "нет хода голоса")
            self.assertEqual(len(store.logs(0, ["message"])), 1)  # «всем привет» — не ведущему
            Bridge.events.append({"type": "chat", "player": "Steve", "text": "а ещё поставь задачу"})  # без имени
            wait(lambda: store.tasks(open_only=False), "задача не поставлена")
            wait(lambda: store.logs(0, ["task_end"]), "исполнитель не кончил")
            wait(lambda: len(store.logs(0, ["turn"])) >= 3, "итог задачи не дошёл до голоса")
            self.assertEqual(store.tasks()[0]["status"], "done")
            Bridge.events.append({"type": "gm", "player": "Alex", "text": "молчи"})
            wait(lambda: store.logs(0, ["no_reply"]), "ход без ответа не записан")
        finally:
            d.stopping = True
            t.join(10)
            server.shutdown()
            server.server_close()
            os.environ.pop("GM_CONFIG", None)


class TeacherFlowTest(Tmp):
    """Урок целиком с заглушкой claude: опыт → наставник правит копию → ворота (порядок, набор, рецензент) → навык."""

    def test_lesson_reaches_skill(self):
        import gm
        gm.init(self.tmp)
        cfg = conf.load(os.path.join(self.tmp, "config.toml"))
        cfg["gm"]["claude"] = os.path.join(KIT, "tests", "fake_claude.py")
        cfg["limits"]["teacher_gap_minutes"] = 0
        os.environ["GM_CONFIG"] = cfg["paths"]["config"]
        try:
            d = gmd.Dispatcher(cfg)
            d.store.log("voice", "lesson", "Steve", text="игроки спрашивают, где спавн")
            for _ in range(200):
                d.teacher_tick()
                if d.store.logs(0, ["teach_merged", "teach_rejected", "teach_conflict"]):
                    break
                time.sleep(0.1)
            if d.gate:
                d.gate.join(30)
            merged = d.store.logs(0, ["teach_merged"])
            self.assertTrue(merged, json.dumps(d.store.logs(0), ensure_ascii=False)[:2000])
            self.assertIn("Добавил место спавна", merged[0]["data"]["summary"])
            self.assertTrue(os.path.exists(os.path.join(self.tmp, "skill", "knowledge", "places.md")))
            log = subprocess.run(["git", "log", "--oneline"], cwd=self.tmp, capture_output=True, text=True).stdout
            self.assertIn("Урок", log)
            self.assertGreater(d.store.get("teacher.seen", 0), 0)
        finally:
            os.environ.pop("GM_CONFIG", None)


if __name__ == "__main__":
    unittest.main()
