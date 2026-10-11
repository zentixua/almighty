"""Рецепты: python3 -m unittest discover -s mcp/gm/tests"""
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import recipes  # noqa: E402

WEATHER = ('---\nname: clear_weather\ndescription: Ясная погода\nkind: instant\n'
           'params: {"seconds": {"type": "int", "min": 60, "max": 7200, "default": 1200}}\n---\n'
           '```steps\n[{"method": "command", "params": {"commands": ["weather clear ${seconds}s"]}}]\n```\n'
           '```check\nreturn !server.overworld().isRaining()\n```\n')


class FakeBridge:
    def __init__(self, results=None):
        self.calls, self.results = [], results or {}

    def __call__(self, method, params=None):
        self.calls.append((method, params))
        r = self.results.get(method)
        return r(params) if callable(r) else r if r is not None else [{"ok": True}]


class RecipesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)

    def write(self, name, text):
        path = os.path.join(self.dir, name + ".md")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def test_load_bind_and_substitute(self):
        self.write("clear_weather", WEATHER)
        self.write("broken", "без шапки")
        found, errors = recipes.load_all(self.dir)
        self.assertEqual(list(found), ["clear_weather"])
        self.assertIn("шапки", errors["broken"][0])
        r = found["clear_weather"]
        self.assertEqual(r.kind, "instant")
        v = recipes.bind(r, {})
        self.assertEqual(v, {"seconds": 1200})
        self.assertEqual(recipes.substitute(r.steps, v)[0]["params"]["commands"], ["weather clear 1200s"])
        self.assertEqual(recipes.substitute({"n": "${seconds}"}, v), {"n": 1200})
        for bad in ({"seconds": 10}, {"seconds": "1200"}, {"seconds": 600, "extra": 1}):
            with self.assertRaises(recipes.RecipeError):
                recipes.bind(r, bad)

    def test_run_guards_steps_and_checks(self):
        r = recipes.parse(self.write("clear_weather", WEATHER))
        b = FakeBridge({"script": {"ok": True, "value": True}})
        self.assertTrue(recipes.run(r, {}, b)["ok"])
        self.assertEqual(b.calls[0], ("command", {"commands": ["weather clear 1200s"]}))
        b = FakeBridge({"command": [{"command": "x", "ok": False, "errors": ["Unknown"]}]})
        self.assertFalse(recipes.run(r, {}, b)["ok"])
        # хук от падений: слова игрокам, падения сервера и дела владельца рецепт не делает
        for command in ("say привет", "stop", "op Steve"):
            path = self.write("bad", '---\nname: bad\ndescription: x\nkind: instant\nparams: {}\n---\n```steps\n'
                                     f'[{{"method": "command", "params": {{"commands": ["{command}"]}}}}]\n```\n')
            bad = recipes.parse(path)
            self.assertEqual(recipes.validate(bad), [])
            b = FakeBridge()
            with self.assertRaises(recipes.RecipeError, msg=command):
                recipes.run(bad, {}, b)
            self.assertEqual(b.calls, [])

    def test_groovy_blocks(self):
        path = self.write("ap", '---\nname: ap\ndescription: x\nkind: show\nparams: {"ship": "name", "alt": "int"}\n'
                                '---\n```groovy pilot\ndef s = gm.ship("${ship}")\n'
                                'if (s.pos().y < ${alt}) gm.emit("low ${s.name()}")\n```\n'
                                '```steps\n[{"method": "rule.add", "params": {"event": "ServerTickEvent.Post",'
                                ' "name": "ap_${ship}", "script": "${code:pilot}"}}]\n```\n'
                                '```check\nreturn "${ship}" == "Grand"\n```\n')
        r = recipes.parse(path)
        self.assertEqual(recipes.validate(r), [])
        b = FakeBridge({"rule.add": {"rule": 1}, "script": {"ok": True, "value": True}})
        self.assertTrue(recipes.run(r, {"ship": "Grand", "alt": 120}, b)["ok"])
        params = b.calls[0][1]
        self.assertEqual(params["name"], "ap_Grand")
        self.assertEqual(params["script"], 'def s = gm.ship("Grand")\nif (s.pos().y < 120) gm.emit("low ${s.name()}")')
        self.assertEqual(b.calls[1][1]["code"], 'return "Grand" == "Grand"')
        self.assertFalse(recipes.TYPES["number"](float("nan")) or recipes.TYPES["number"](float("inf")))
        self.assertFalse(recipes.TYPES["text"]('${"x".execute()}'))
        with self.assertRaises(recipes.RecipeError):
            recipes.groovy("gm.emit('${t}')", {"t": "п'ять"})
        with self.assertRaises(recipes.RecipeError):  # и код прямо в шаге
            recipes.substitute([{"method": "script", "params": {"code": "gm.emit('${t}')"}}], {"t": "п'ять"})
        self.assertEqual(recipes.substitute({"commands": ["say ${t}"]}, {"t": "п'ять"}), {"commands": ["say п'ять"]})
        path = self.write("ap", '---\nname: ap\ndescription: x\nkind: instant\nparams: {}\n---\n```groovy a\n1\n```\n'
                                '```groovy b\n2\n```\n```steps\n[{"method": "script", "params": {"code": "x ${code:a}"}},'
                                ' {"method": "script", "params": {"code": "${code:c}"}}]\n```\n')
        problems = " | ".join(recipes.validate(recipes.parse(path)))
        for part in ("${code:c}", "блок groovy b", "целой строкой"):
            self.assertIn(part, problems)

    def test_validate(self):
        path = self.write("x", '---\nname: y\nkind: task\nparams: {"a": "float"}\n---\n```groovy z\n1\n```\n'
                               '```steps\n[{"method": "say", "params": {"text": "${b}"}}]\n```\n')
        problems = " | ".join(recipes.validate(recipes.parse(path)))
        for part in ("name", "description", "float", "say", "${b}", "блок groovy z", "check"):
            self.assertIn(part, problems)


if __name__ == "__main__":
    unittest.main()
