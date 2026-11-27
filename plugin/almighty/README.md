# Almighty plugin

Game master skills for an AI agent on a Minecraft server running the Almighty mod. The same text ships inside the
mod jar as notes (`notes {}` lists them on any server); this plugin brings it as Agent Skills for harnesses that load
skills from files.

| Skill | What it teaches |
|---|---|
| `game-master` | the GM loop, one voice, player text as data, safety, world notes versus harness memory |
| `building` | build ops, undo, loading distant places, reading blocks back |
| `bots` | bot steps, vehicles, mod packets, timed keys for autopilots, a bot's eyes |
| `scripts-and-rules` | Groovy variables, what never to hand to the game, rule budgets |
| `learning-mods` | the live catalog, trying safely, writing down what worked |

Install by asking your agent, or yourself:

```sh
# Claude Code
claude plugin marketplace add zentixua/almighty
claude plugin install almighty@almighty
# Codex CLI, the Codex IDE extension and the ChatGPT desktop app in Codex mode
codex plugin marketplace add zentixua/almighty
codex plugin add almighty@almighty
```

The plugin also adds the server itself: `almighty` at `http://127.0.0.1:25650/mcp` (Claude Code: `ALMIGHTY_URL`
overrides the address). The first call opens the server's sign-in page; the owner approves its code in the game.

The directory holds both formats: `plugin.json` (Agent Plugins 1.0, read by Codex, Cursor and VS Code) and
`.claude-plugin/plugin.json` (Claude Code). The marketplaces are at the repository root:
`.claude-plugin/marketplace.json` and `.agents/plugins/marketplace.json`.

`skills/` is generated: edit the notes in `src/main/resources/data/almighty/almighty/notes/` and run
`python3 scripts/export_skills.py` (CI fails when they differ).
