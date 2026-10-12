"""Хук от падений: python3 -m unittest discover -s mcp/gm/tests"""
import json
import pathlib
import subprocess
import sys
import unittest

HOOK = pathlib.Path(__file__).resolve().parent.parent / "crash_guard.py"
sys.path.insert(0, str(HOOK.parent))

import crash_guard as g  # noqa: E402


def decision(tool, tool_input):
    found = g.decide("mcp__almighty__" + tool, tool_input)
    return found[0] if found else None


class Commands(unittest.TestCase):
    def test_never(self):
        for c in ("stop", "/reload", "save-off", "save-all flush", "forceload add 0 0", "execute as @a run stop",
                  "tick rate 9999"):
            self.assertEqual(decision("command", {"commands": [c]}), "deny", c)

    def test_owner_only_asks(self):
        for c in ("op ClyuDan", "whitelist add Foo", "ban Foo", "kick Foo", "execute run deop ZentixUA"):
            self.assertEqual(decision("command", {"commands": [c]}), "ask", c)

    def test_ordinary_allowed(self):
        for c in ("time set day", "save-all", "tick rate 20", "tick freeze", "incoming salvo missile 1 0 at 1 64 2",
                  "tp ZentixUA 3590 110 310", "gamerule doFireTick true", "spark profiler start --timeout 30"):
            self.assertIsNone(decision("command", {"commands": [c]}), c)

    def test_sable_plot(self):
        self.assertEqual(decision("command", {"commands": ["tp @s 20481033 131 20483080"]}), "deny")
        self.assertEqual(decision("build", {"ops": [{"op": "set", "pos": [20481033, 131, 5], "block": "stone"}]}), "deny")
        self.assertEqual(decision("area", {"action": "prepare", "start": [20480000, 0, 0], "end": [20480100, 0, 100]}),
                         "deny")
        self.assertIsNone(decision("build", {"ops": [{"op": "set", "pos": [100, 64, 5], "block": "stone"}]}))

    def test_function_macro_expanded(self):
        self.assertEqual(decision("command", {"lines": ["$$(c)"], "args": {"c": "stop"}}), "deny")

    def test_bot_chat_command(self):
        self.assertEqual(decision("bot", {"action": "act", "name": "B", "actions": [{"chat": "/op B"}]}), "ask")
        self.assertIsNone(decision("bot", {"action": "act", "name": "B", "actions": [{"chat": "привет"}]}))

    def test_call_passthrough(self):
        self.assertEqual(decision("call", {"method": "command", "params": {"commands": ["stop"]}}), "deny")
        self.assertIsNone(decision("call", {"method": "rule.remove", "params": {"name": "war"}}))


class Scripts(unittest.TestCase):
    def test_crash_classes(self):
        for code in ("TicketType.create('x', c)", "level.getChunk(0, 0)", "new Thread({}).start()",
                     "server.execute { }", "Thread.sleep(8000)", "sleep(500)", "MinecraftForge.EVENT_BUS.register(x)",
                     "System.exit(0)", "'ls'.execute()"):
            self.assertEqual(decision("script", {"code": code}), "deny", code)

    def test_reads_allowed(self):
        for code in ("Runtime.runtime.maxMemory()", "server.getCommands().getDispatcher().getRoot().getChild('sable')",
                     "new File('config/spark').list()", "java.lang.management.ManagementFactory.getMemoryMXBean()",
                     "level.getChunkNow(0, 0)", "[1, 2].join(',')", "gm.command(\"incoming salvo missile 1 0 at ${x} 64 ${z}\")"):
            self.assertIsNone(decision("script", {"code": code}), code)

    def test_command_literal_in_rule(self):
        self.assertEqual(decision("rule", {"action": "add", "event": "tick", "script": "gm.command('stop')"}), "deny")
        self.assertEqual(decision("rule", {"action": "add", "event": "tick", "script": "gm.commandAs(p, 'op x')"}), "ask")


class OneVoice(unittest.TestCase):
    def sub(self, tool, tool_input):
        found = g.decide("mcp__almighty__" + tool, tool_input, subagent=True)
        return found[0] if found else None

    def test_subagent_does_not_talk_to_players(self):
        self.assertEqual(self.sub("say", {"text": "готово"}), "deny")
        self.assertEqual(self.sub("call", {"method": "say", "params": {"text": "x"}}), "deny")
        for c in ("tellraw @a \"x\"", "/say x", "execute as @a run title @s title \"x\"", "msg ZentixUA x"):
            self.assertEqual(self.sub("command", {"commands": [c]}), "deny", c)
        self.assertEqual(self.sub("bot", {"action": "act", "name": "B", "actions": [{"chat": "Огонь!"}]}), "deny")

    def test_subagent_still_works(self):
        self.assertIsNone(self.sub("command", {"commands": ["incoming salvo missile 1 0 at 1 64 2"]}))
        self.assertIsNone(self.sub("bot", {"action": "act", "name": "B", "actions": [{"chat": "/gamemode creative"}]}))

    def test_main_session_talks(self):
        self.assertIsNone(decision("say", {"text": "готово"}))
        self.assertIsNone(decision("command", {"commands": ["tellraw @a \"x\""]}))

    def test_hook_reads_agent_id(self):
        r = subprocess.run([sys.executable, str(HOOK)], capture_output=True, text=True, input=json.dumps(
            {"tool_name": "mcp__almighty__say", "tool_input": {"text": "x"}, "agent_id": "a1", "agent_type": "general-purpose"}))
        self.assertEqual(json.loads(r.stdout)["hookSpecificOutput"]["permissionDecision"], "deny")


class Process(unittest.TestCase):
    def run_hook(self, payload):
        return subprocess.run([sys.executable, str(HOOK)], input=json.dumps(payload), capture_output=True, text=True)

    def test_deny_json(self):
        r = self.run_hook({"tool_name": "mcp__almighty__command", "tool_input": {"commands": ["stop"]}})
        self.assertEqual(r.returncode, 0)
        out = json.loads(r.stdout)["hookSpecificOutput"]
        self.assertEqual((out["hookEventName"], out["permissionDecision"]), ("PreToolUse", "deny"))

    def test_other_tools_pass(self):
        r = self.run_hook({"tool_name": "Bash", "tool_input": {"command": "ls"}})
        self.assertEqual((r.returncode, r.stdout), (0, ""))

    def test_broken_input_blocks(self):
        r = subprocess.run([sys.executable, str(HOOK)], input="{", capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main()
